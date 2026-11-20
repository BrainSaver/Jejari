package com.jejari.event.relay;

import java.sql.Timestamp;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.event.AggregateType;
import com.jejari.event.EventEnvelope;
import com.jejari.event.EventPolicy;
import com.jejari.event.outbox.OutboxRecorder;

import tools.jackson.databind.json.JsonMapper;

/**
 * EVT-02 Outbox 릴레이 (concurrency.md §11.3~§11.4).
 *
 * <p><b>1차 범위 (기능설계서 1.1)</b>: 인스턴스 1대가 샤드 0~15를 차례로 혼자 처리합니다. 담당자가 하나뿐이라
 * Redis 리스(§11.2)는 아직 없습니다. 인스턴스를 늘릴 때 {@link #relayShard(int)} 앞에 리스 획득만 붙이면 됩니다.
 *
 * <p>순서를 지키는 규칙 세 가지:
 * <ol>
 * <li>샤드 안에서 seq 순서대로 하나씩 보냅니다.</li>
 * <li>하나라도 실패하면 그 샤드의 이번 회차를 즉시 멈춥니다. 뒤 행은 같은 예약의 다음 이벤트일 수 있습니다.</li>
 * <li>맨 앞 행이 FAILED(재시도 소진)면 그 샤드는 관리자가 고칠 때까지 멈춥니다. 다른 샤드는 계속 돕니다.</li>
 * </ol>
 */
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	/** 이번 회차에 한 샤드에서 일어난 일. */
	public enum Stop {
		/** 보낼 행이 없거나 전부 보냄 */
		NONE,
		/** 맨 앞 행이 FAILED → 샤드 정지 (§11.4) */
		HEAD_FAILED,
		/** 발행 실패 → 이번 회차 중단, 다음 회차에 그 행부터 */
		PUBLISH_FAILED,
		/** FOR UPDATE WAIT 시간 초과 → 이번 회차 포기 */
		LOCK_TIMEOUT
	}

	public record ShardResult(int shard, int published, Stop stop) {}

	record Row(String eventId, long seq, String status, String aggregateType, String aggregateId,
			String partitionKey, String eventType, String payload, int retryCount, Timestamp createdAt) {}

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final EventPublisher publisher;
	private final EventPolicy policy;
	private final JsonMapper json;

	public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, EventPublisher publisher, EventPolicy policy,
			JsonMapper json) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.publisher = publisher;
		this.policy = policy;
		this.json = json;
	}

	/**
	 * 보낼 것이 있는 샤드만 한 바퀴 돕니다. 한 샤드의 문제는 다른 샤드를 막지 않습니다.
	 *
	 * <p>먼저 쿼리 한 번으로 PENDING·FAILED가 있는 샤드를 찾습니다 (concurrency.md §11.3). 전에는 보낼 것이 없어도 0.2초마다
	 * 16개 샤드에 트랜잭션을 하나씩 열어, 아무도 안 쓸 때도 Oracle 커넥션을 초당 약 76번 빌렸습니다 (전체의 93%, 2026-11-20 실측.
	 * A-10 흐름도에서 사용자가 없는데 웹 서버 → Oracle 점이 계속 흐르던 원인).
	 */
	public int relayAll() {
		int total = 0;
		for (int shard : shardsWithWork()) {
			try {
				total += relayShard(shard).published();
			} catch (DataAccessException e) {
				log.warn("outbox 샤드 {} 처리 중 DB 오류, 다음 회차에 다시 시도: {}", shard, e.toString());
			}
		}
		return total;
	}

	/**
	 * PENDING·FAILED 행이 있는 샤드 번호. 인덱스 ix_outbox_pending의 첫 식과 같은 식이라 그 인덱스만 읽습니다
	 * (PUBLISHED 행은 두 식이 모두 NULL이라 인덱스에 없음 → 쌓인 발행 완료 행 수와 상관없이 가볍다).
	 * 조건을 {@code IS NOT NULL}로 쓰면 Oracle이 테이블 전체를 읽는다 → 범위 조건 {@code >= 0}이라야 INDEX RANGE SCAN (실행 계획 확인).
	 * 이 확인이 실패하면 예전처럼 16개를 모두 봅니다 (한 번 못 본다고 발행을 건너뛰지 않게).
	 */
	List<Integer> shardsWithWork() {
		try {
			return jdbc.queryForList("""
					SELECT DISTINCT CASE WHEN status IN ('PENDING', 'FAILED') THEN shard_no END
					  FROM outbox_event
					 WHERE CASE WHEN status IN ('PENDING', 'FAILED') THEN shard_no END >= 0""", Integer.class);
		} catch (DataAccessException e) {
			log.warn("outbox 보낼 샤드 확인 실패, 16개를 모두 봅니다: {}", e.toString());
			return java.util.stream.IntStream.rangeClosed(0, OutboxRecorder.MAX_SHARD).boxed().toList();
		}
	}

	/** 한 샤드의 맨 앞 N개를 한 트랜잭션에서 처리하고 커밋합니다 (실패로 멈출 때도 그때까지 한 것은 커밋). */
	public ShardResult relayShard(int shard) {
		try {
			return tx.execute(status -> doShard(shard));
		} catch (PessimisticLockingFailureException e) {
			// 리스가 넘어가는 순간 이전 담당자가 아직 잡고 있는 경우 등. SKIP LOCKED로 건너뛰면 순서가 깨지므로 기다렸다 포기합니다
			log.debug("outbox 샤드 {} 락 대기 초과, 이번 회차 포기", shard);
			return new ShardResult(shard, 0, Stop.LOCK_TIMEOUT);
		}
	}

	private ShardResult doShard(int shard) {
		List<Row> rows = lockHead(shard);
		if (rows.isEmpty()) {
			return new ShardResult(shard, 0, Stop.NONE);
		}
		if ("FAILED".equals(rows.get(0).status())) {
			// 알림: 1차는 로그. 관리자가 원인을 고친 뒤 status='PENDING', retry_count=0으로 되돌리면 재개됩니다
			log.error("outbox 샤드 {} 정지: 맨 앞 이벤트 {} (seq {}, {})가 FAILED", shard, rows.get(0).eventId(),
					rows.get(0).seq(), rows.get(0).eventType());
			return new ShardResult(shard, 0, Stop.HEAD_FAILED);
		}
		int published = 0;
		for (Row r : rows) {
			try {
				publisher.publish(topicOf(r), r.partitionKey(), r.eventId(), r.eventType(), envelope(r));
			} catch (Exception e) {
				markFailure(r);
				log.warn("outbox 발행 실패 (샤드 {}, seq {}, {}회째), 이번 회차 중단: {}", shard, r.seq(),
						r.retryCount() + 1, e.toString());
				return new ShardResult(shard, published, Stop.PUBLISH_FAILED);
			}
			jdbc.update("UPDATE outbox_event SET status = 'PUBLISHED', published_at = SYSTIMESTAMP, "
					+ "updated_at = SYSTIMESTAMP WHERE event_id = ?", r.eventId());
			published++;
		}
		return new ShardResult(shard, published, Stop.NONE);
	}

	/*
	 * Oracle의 SELECT … FOR UPDATE는 커서를 여는 순간 조건에 맞는 행 전체를 잠급니다. 적체가 수만 건이면 수만 행이 잠기므로
	 * 안쪽 쿼리로 맨 앞 N개의 rowid만 뽑고 그 행만 잠급니다. 안쪽 CASE 식은 인덱스 ix_outbox_pending과 같은 식이라
	 * PUBLISHED 행은 인덱스에 없어 읽지 않습니다. 바깥 status 조건은 락을 기다리는 사이 다른 쪽이 발행을 끝낸 행을 뺍니다.
	 */
	private List<Row> lockHead(int shard) {
		long batch = policy.getLong(EventPolicy.RELAY_BATCH);
		long waitSec = policy.getLong(EventPolicy.LOCK_WAIT_SEC);
		String sql = """
				SELECT event_id, seq, status, aggregate_type, aggregate_id, partition_key, event_type, payload,
				       retry_count, created_at
				  FROM outbox_event
				 WHERE rowid IN (
				         SELECT rid FROM (
				           SELECT rowid AS rid FROM outbox_event
				            WHERE (CASE WHEN status IN ('PENDING', 'FAILED') THEN shard_no END) = ?
				            ORDER BY (CASE WHEN status IN ('PENDING', 'FAILED') THEN seq END))
				          WHERE ROWNUM <= ?)
				   AND status IN ('PENDING', 'FAILED')
				 ORDER BY seq
				   FOR UPDATE WAIT %d
				""".formatted(waitSec);
		return jdbc.query(sql, (rs, i) -> new Row(rs.getString("event_id"), rs.getLong("seq"),
				rs.getString("status"), rs.getString("aggregate_type"), rs.getString("aggregate_id"),
				rs.getString("partition_key"), rs.getString("event_type"), rs.getString("payload"),
				rs.getInt("retry_count"), rs.getTimestamp("created_at")), shard, batch);
	}

	private void markFailure(Row r) {
		long max = policy.getLong(EventPolicy.MAX_RETRY);
		jdbc.update("""
				UPDATE outbox_event
				   SET retry_count = retry_count + 1,
				       status = CASE WHEN retry_count + 1 >= ? THEN 'FAILED' ELSE status END,
				       updated_at = SYSTIMESTAMP
				 WHERE event_id = ?
				""", max, r.eventId());
	}

	private String envelope(Row r) {
		return new EventEnvelope(r.eventId(), r.eventType(), r.aggregateType(), r.aggregateId(), r.seq(),
				r.createdAt() == null ? null : r.createdAt().toLocalDateTime(), json.readTree(r.payload()))
				.toJson(json);
	}

	private static String topicOf(Row r) {
		return AggregateType.valueOf(r.aggregateType()).topic();
	}
}
