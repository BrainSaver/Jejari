package com.jejari.seat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.config.AppPolicy;

/**
 * 단체 할당석 (concurrency.md §18.1 만들기, §18.4 반납). 1분마다 두 일을 한다.
 *
 * <pre>
 * 만들기: 판매 오픈 10분 전 ~ 오픈 직후의 SCHEDULED 운행 → 좌석의 group.quota_rate만큼 BLOCKED 행 + Redis 키 GROUP
 * 반납:   출발 group.quota_release_days일 전이 지난 운행 → 남은 BLOCKED 행 삭제 + 값이 GROUP인 Redis 키 삭제
 * </pre>
 *
 * <p><b>왜 행을 미리 만드나</b>: BLOCKED 행은 예약이 없는 {@code seat_inventory} 행이다. 행과 Redis 키가 이미 있으므로
 * 일반 선점(§2)은 {@code hold.lua}에서 지고, Redis 키가 먼저 사라져도 DB 확인(§2.2-1)에서 거절된다.
 * 선점 코드를 단체용으로 따로 고치지 않아도 할당석이 일반 판매에서 빠진다.
 *
 * <p><b>만들기는 기본으로 켠다</b> ({@code jejari.seat.group-quota.create-enabled}, 환경변수 {@code JEJARI_GROUP_QUOTA_ENABLED}).
 * 처음에는 꺼 뒀다 — 단체 신청·승인 API 없이 켜면 좌석 5%가 출발 30일 전까지 아무도 못 사는 좌석이 된다.
 * API({@code com.jejari.group})가 들어와 2026-11-27에 켰다. 테스트는 build.gradle에서 끈다. 반납은 늘 돈다
 * (켰다가 끈 뒤 남은 할당석도 제때 풀리게).
 *
 * <p>앱이 여러 대여도 된다: 만들기는 운행에 BLOCKED 행이 하나도 없을 때만 하고, 두 대가 동시에 넣으면 한쪽이
 * PK 충돌로 롤백된 뒤 다음 주기에 "이미 있음"으로 건너뛴다. 반납 DELETE는 {@code status = 'BLOCKED'} 조건이라
 * 단체 승인(BLOCKED → HOLD)과 동시에 돌아도 배정된 좌석은 남는다 (§18.4-1, G6).
 */
@Component
@ConditionalOnProperty(name = "jejari.seat.group-quota-scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class GroupQuotaScheduler {

	private static final Logger log = LoggerFactory.getLogger(GroupQuotaScheduler.class);

	/** 판매 오픈 몇 초 전부터 할당석을 만들지. 오픈 순간 이미 묶여 있어야 일반 선점과 겨루지 않는다 */
	static final long CREATE_LEAD_SEC = 600;
	private static final int BATCH_RUNS = 20;

	/** 만들 운행: 곧 열리는(또는 막 열린) SCHEDULED 운행 중 할당석이 아직 없는 것 */
	private static final String DUE_TO_CREATE = """
			SELECT r.run_id, r.train_id, e.group_quota_rate,
			       (SELECT MIN(s.departure_time) FROM train_stop s WHERE s.run_id = r.run_id) AS dep
			  FROM train_run r
			  LEFT JOIN sale_event e ON e.sale_event_id = r.sale_event_id
			 WHERE r.status = 'SCHEDULED'
			   AND r.sale_open_at <= SYSTIMESTAMP + NUMTODSINTERVAL(?, 'SECOND')
			   AND (r.sale_close_at IS NULL OR r.sale_close_at > SYSTIMESTAMP)
			   AND NOT EXISTS (SELECT 1 FROM seat_inventory i WHERE i.run_id = r.run_id AND i.status = 'BLOCKED')
			 ORDER BY r.sale_open_at
			 FETCH FIRST %d ROWS ONLY""".formatted(BATCH_RUNS);

	/**
	 * 반납할 운행: 출발 N일 전이 지났고 아직 떠나지 않은 운행 중 BLOCKED가 남은 것.
	 * seat_inventory는 PK가 run_id로 시작해 운행 하나씩 범위 조회다 (쓰기 폭주 표라 인덱스는 더하지 않음).
	 */
	private static final String DUE_TO_RELEASE = """
			SELECT r.run_id
			  FROM train_run r
			 WHERE r.status <> 'DEPARTED'
			   AND (SELECT MIN(s.departure_time) FROM train_stop s WHERE s.run_id = r.run_id)
			       <= SYSTIMESTAMP + NUMTODSINTERVAL(?, 'DAY')
			   AND EXISTS (SELECT 1 FROM seat_inventory i WHERE i.run_id = r.run_id AND i.status = 'BLOCKED')
			 FETCH FIRST %d ROWS ONLY""".formatted(BATCH_RUNS);

	/** §18.1-3 좌석 고르기: 일반실만, 객차 순서대로, 객차 안에서는 뒤쪽 좌석부터 (같은 객차 연속 좌석) */
	private static final String GENERAL_SEATS = """
			SELECT seat_id FROM seat WHERE train_id = ? AND seat_grade = 'GENERAL' ORDER BY car_no, seat_id DESC""";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final StringRedisTemplate redis;
	private final SeatScripts scripts;
	private final SeatMapService seatMap;
	private final AppPolicy policy;
	private final Clock clock;
	private final boolean createEnabled;

	GroupQuotaScheduler(JdbcTemplate jdbc, TransactionTemplate tx, StringRedisTemplate redis, SeatScripts scripts,
			SeatMapService seatMap, AppPolicy policy, Clock clock,
			@Value("${jejari.seat.group-quota.create-enabled:false}") boolean createEnabled) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.redis = redis;
		this.scripts = scripts;
		this.seatMap = seatMap;
		this.policy = policy;
		this.clock = clock;
		this.createEnabled = createEnabled;
	}

	@Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
	void tick() {
		try {
			if (createEnabled) {
				createDue();
			}
			releaseDue();
		} catch (RuntimeException e) {
			log.warn("단체 할당석 스캔 실패 (다음 주기에 다시)", e);
		}
	}

	/** §18.1. 만든 운행 수 */
	public int createDue() {
		record Due(long runId, long trainId, BigDecimal eventRate, LocalDateTime departure) {
		}
		List<Due> due = jdbc.query(DUE_TO_CREATE, (rs, i) -> new Due(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3),
				rs.getObject(4, LocalDateTime.class)), CREATE_LEAD_SEC);
		int made = 0;
		for (Due d : due) {
			try {
				if (create(d.runId(), d.trainId(), d.eventRate(), d.departure()) > 0) {
					made++;
				}
			} catch (DataAccessException e) {
				// 다른 앱이 같은 운행을 동시에 만들었으면 PK 충돌로 여기 온다. 다음 주기에 "이미 있음"으로 건너뜀
				log.info("운행 {} 할당석 만들기 실패 (다음 주기에 다시): {}", d.runId(), e.getMessage());
			}
		}
		return made;
	}

	/**
	 * 운행 하나의 할당석을 만든다.
	 *
	 * @param eventRate 행사 운행의 {@code sale_event.group_quota_rate} (null이면 정책 값)
	 * @param departure 기점 출발 시각 (null이면 반납 시각을 몰라 만들지 않음)
	 * @return 만든 BLOCKED 행 수
	 */
	int create(long runId, long trainId, BigDecimal eventRate, LocalDateTime departure) {
		// 1. 비율. 명절 행사는 0으로 꺼 둘 수 있다
		double rate = eventRate != null ? eventRate.doubleValue() : policy.getDouble(AppPolicy.GROUP_QUOTA_RATE);
		if (rate <= 0 || departure == null) {
			return 0;
		}
		// 4. 반납 시각이 이미 지났으면 만들 이유가 없다 (출발이 30일 안인 운행)
		LocalDateTime release = departure.minusDays(policy.getLong(AppPolicy.GROUP_QUOTA_RELEASE_DAYS));
		LocalDateTime now = LocalDateTime.now(clock);
		if (!release.isAfter(now)) {
			return 0;
		}
		// 2·3. 개수와 좌석. 개수는 운행 전체 좌석 기준, 고르는 건 일반실에서
		int total = jdbc.queryForObject("SELECT COUNT(*) FROM seat WHERE train_id = ?", Integer.class, trainId);
		int q = (int) Math.floor(total * rate);
		List<Long> general = jdbc.queryForList(GENERAL_SEATS, Long.class, trainId);
		List<Long> picked = new ArrayList<>(general.subList(0, Math.min(q, general.size())));
		if (picked.isEmpty()) {
			return 0;
		}
		picked.sort(null); // (run_id, seat_id, segment_no) 오름차순 INSERT (§0.4-3)

		// 5. DB. 이미 있는 좌석(그 사이 누가 선점)은 건너뛴다
		tx.executeWithoutResult(st -> jdbc.batchUpdate("""
				MERGE INTO seat_inventory t
				USING (SELECT ? AS run_id, ? AS train_id, ? AS seat_id, ? AS segment_no FROM dual) s
				   ON (t.run_id = s.run_id AND t.seat_id = s.seat_id AND t.segment_no = s.segment_no)
				 WHEN NOT MATCHED THEN
				   INSERT (run_id, train_id, seat_id, segment_no, reservation_id, status, hold_expires_at)
				   VALUES (s.run_id, s.train_id, s.seat_id, s.segment_no, NULL, 'BLOCKED', NULL)""",
				picked, 200, (ps, seatId) -> {
					ps.setLong(1, runId);
					ps.setLong(2, trainId);
					ps.setLong(3, seatId);
					ps.setInt(4, HoldPlan.SEGMENT_NO);
				}));
		// 실제로 BLOCKED가 된 좌석만 다시 읽는다. 고른 좌석 중 이미 남의 행(만료된 HOLD 등)이 있던 좌석에 GROUP 키를 걸면
		// DB는 BLOCKED가 아닌데 Redis만 반납 시각까지 막혀, 반납(§18.4)도 못 푸는 좌석이 된다.
		// (배치 영향 행 수는 드라이버에 따라 -2 "모름"이 올 수 있어 믿지 않는다)
		List<Long> blocked = jdbc.queryForList(
				"SELECT seat_id FROM seat_inventory WHERE run_id = ? AND status = 'BLOCKED' ORDER BY seat_id", Long.class, runId);
		int rows = blocked.size();

		// 6. COMMIT 뒤 Redis: 반납 시각에 저절로 사라지는 GROUP 키. 실패해도 DB가 최종이라 일반 선점은 §2.2-1에서 막힌다
		Duration ttl = Duration.between(now, release);
		try {
			for (long seatId : blocked) {
				redis.opsForValue().setIfAbsent(SeatKeys.hold(runId, seatId, HoldPlan.SEGMENT_NO), SeatKeys.GROUP, ttl);
			}
			seatMap.evict(runId);
		} catch (RuntimeException e) {
			log.warn("운행 {} 할당석 Redis 키 실패 (DB BLOCKED 행이 막음)", runId, e);
		}
		log.info("운행 {} 단체 할당석 {}석 (비율 {}, 반납 {})", runId, rows, rate, release);
		return rows;
	}

	/** §18.4. 반납한 운행 수 */
	public int releaseDue() {
		List<Long> due = jdbc.queryForList(DUE_TO_RELEASE, Long.class, policy.getLong(AppPolicy.GROUP_QUOTA_RELEASE_DAYS));
		for (long runId : due) {
			release(runId);
		}
		return due.size();
	}

	/** 운행 하나의 남은 할당석을 일반 판매로 돌린다. 지운 행 수 */
	int release(long runId) {
		record Out(List<Long> seatIds, int deleted) {
		}
		Out out = tx.execute(st -> {
			List<Long> seats = jdbc.queryForList(
					"SELECT seat_id FROM seat_inventory WHERE run_id = ? AND status = 'BLOCKED' ORDER BY seat_id", Long.class, runId);
			// HOLD·SOLD(이미 단체에 배정된 좌석)는 건드리지 않는다
			int n = jdbc.update("DELETE FROM seat_inventory WHERE run_id = ? AND status = 'BLOCKED'", runId);
			return new Out(seats, n);
		});
		try {
			List<String> keys = out.seatIds().stream().map(id -> SeatKeys.hold(runId, id, HoldPlan.SEGMENT_NO)).toList();
			if (!keys.isEmpty()) {
				redis.execute(scripts.release, keys, SeatKeys.GROUP); // 값이 GROUP인 키만 (단체에 배정돼 R로 바뀐 키는 남김)
			}
			seatMap.evict(runId);
		} catch (RuntimeException e) {
			log.warn("운행 {} 할당석 반납 후 Redis 정리 실패 (키는 반납 시각 TTL로 사라짐)", runId, e);
		}
		log.info("운행 {} 단체 할당석 {}석 반납 → 일반 판매", runId, out.deleted());
		return out.deleted();
	}
}
