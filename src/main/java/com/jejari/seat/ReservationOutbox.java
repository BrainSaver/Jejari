package com.jejari.seat;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * 예약 흐름 이벤트를 outbox_event에 남긴다 (concurrency.md §11.1). 반드시 상태를 바꾸는 트랜잭션 안에서,
 * reservation 행을 INSERT·UPDATE한 <b>뒤에</b> 부릅니다 (§0.5). 발행은 릴레이(C 길동현)가 합니다.
 *
 * <p>partition_key는 reservation_id를 <b>문자열로</b> 바인딩하고 식에서도 TO_CHAR로 감쌉니다.
 * ORA_HASH는 NUMBER와 VARCHAR2의 해시가 달라서, 경로마다 타입이 다르면 같은 예약이 다른 샤드로 갑니다 (T32).
 *
 * <p>TODO(길동현): 결제·취소 쪽 outbox 기록 코드가 생기면 공용 모듈 하나로 합칩니다.
 */
@Component
class ReservationOutbox {

	private static final String INSERT = """
			INSERT INTO outbox_event (event_id, seq, aggregate_type, aggregate_id, partition_key, shard_no,
			                          event_type, payload, status, retry_count)
			VALUES (?, outbox_seq.NEXTVAL, 'RESERVATION', ?, ?, ORA_HASH(TO_CHAR(?), 15), ?, ?, 'PENDING', 0)""";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final JdbcTemplate jdbc;

	ReservationOutbox(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** @return event_id (reservation_history.event_id에 같이 남김) */
	String append(long reservationId, String eventType, Map<String, Object> payload) {
		String eventId = UUID.randomUUID().toString();
		String key = Long.toString(reservationId);
		jdbc.update(INSERT, eventId, key, key, key, eventType, JSON.writeValueAsString(payload));
		return eventId;
	}
}
