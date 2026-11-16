package com.jejari.event.outbox;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.jejari.event.AggregateType;
import com.jejari.event.EventType;

import tools.jackson.databind.json.JsonMapper;

/**
 * EVT-01 Outbox 기록 (concurrency.md §11.1).
 *
 * <p>업무 트랜잭션 <b>안에서</b> 이벤트를 outbox_event에 함께 INSERT합니다. 예약 변경과 이벤트가 같이 커밋되거나
 * 같이 롤백되므로 "DB는 바뀌었는데 이벤트가 사라지는" 일이 없습니다. Kafka 발행은 {@code OutboxRelay}가 따로 합니다.
 *
 * <p>사용법: 상태를 바꾸는 트랜잭션에서 reservation 행 UPDATE·INSERT <b>뒤에</b> 부릅니다 (§0.5).
 * 같은 예약의 다음 트랜잭션은 reservation 행 락에서 기다리므로 다음 이벤트의 seq가 항상 더 큽니다.
 *
 * <pre>
 * &#64;Transactional
 * public void confirm(long reservationId) {
 *     … reservation UPDATE …
 *     outbox.recordReservation(reservationId, EventType.RESERVATION_CONFIRMED, new Confirmed(reservationId, …));
 * }
 * </pre>
 */
public class OutboxRecorder {

	/** ORA_HASH(…, 15) → 0~15. DDL CHECK(shard_no BETWEEN 0 AND 15), app_policy outbox.shard_count와 같아야 합니다. */
	public static final int MAX_SHARD = 15;

	/*
	 * :pkey는 항상 문자열로 바인딩하고 식에서도 TO_CHAR로 감쌉니다. ORA_HASH는 NUMBER와 VARCHAR2의 해시가 달라서,
	 * 코드 경로마다 타입이 다르면 같은 예약의 이벤트가 다른 샤드로 가 순서가 깨집니다 (T32).
	 * 그래서 이 클래스의 공개 메서드는 파티션 키를 String으로만 받습니다.
	 */
	private static final String INSERT = """
			INSERT INTO outbox_event (event_id, seq, aggregate_type, aggregate_id, partition_key, shard_no,
			                          event_type, payload, status, retry_count)
			VALUES (?, outbox_seq.NEXTVAL, ?, ?, ?, ORA_HASH(TO_CHAR(?), %d), ?, ?, 'PENDING', 0)
			""".formatted(MAX_SHARD);

	private final JdbcTemplate jdbc;
	private final JsonMapper json;

	public OutboxRecorder(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * 예약 흐름 이벤트. 집계 ID와 파티션 키 모두 reservation_id입니다.
	 *
	 * @return 기록한 event_id
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public String recordReservation(long reservationId, EventType type, Object payload) {
		String id = Long.toString(reservationId);
		return record(AggregateType.RESERVATION, id, id, type, payload);
	}

	/**
	 * 일반형. 트랜잭션 밖에서 부르면 {@code IllegalTransactionStateException}으로 실패합니다 (MANDATORY).
	 * 업무 변경 없이 이벤트만 남는 실수를 막기 위해서입니다.
	 *
	 * @return 기록한 event_id
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public String record(AggregateType aggregateType, String aggregateId, String partitionKey, EventType type,
			Object payload) {
		if (partitionKey == null || partitionKey.isBlank()) {
			throw new IllegalArgumentException("partition_key가 비었습니다");
		}
		String eventId = UUID.randomUUID().toString();
		String body = json.writeValueAsString(payload);
		jdbc.update(INSERT, eventId, aggregateType.name(), aggregateId, partitionKey, partitionKey, type.name(), body);
		return eventId;
	}
}
