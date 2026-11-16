package com.jejari.seat;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.jejari.event.EventType;
import com.jejari.event.outbox.OutboxRecorder;

/**
 * 좌석 선점·해제가 남기는 예약 흐름 이벤트. 실제 기록은 팀 공용 {@link OutboxRecorder}(EVT-01, C 길동현)가 합니다
 * (concurrency.md §11.1: partition_key = reservation_id 문자열, shard_no = ORA_HASH(TO_CHAR(:pkey), 15)).
 * 반드시 상태를 바꾸는 트랜잭션 안에서, reservation 행을 INSERT·UPDATE한 <b>뒤에</b> 부릅니다 (§0.5).
 */
@Component
class ReservationOutbox {

	private final OutboxRecorder recorder;

	ReservationOutbox(OutboxRecorder recorder) {
		this.recorder = recorder;
	}

	/** @return event_id (reservation_history.event_id에 같이 남김) */
	String append(long reservationId, String eventType, Map<String, Object> payload) {
		return recorder.recordReservation(reservationId, EventType.valueOf(eventType), payload);
	}
}
