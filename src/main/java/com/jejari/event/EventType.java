package com.jejari.event;

/**
 * outbox_event.event_type 값. 예약 흐름 이벤트는 모두 reservation_id를 파티션 키로 씁니다 (concurrency.md §11.1).
 *
 * <p>이름은 concurrency.md의 "outbox `…`" 표기와 같습니다. 새 이벤트는 여기에 추가하고 기능설계서 8장 표도 함께 고칩니다.
 */
public enum EventType {

	/** 좌석 선점 완료 (§2.2) */
	SEAT_HELD,
	/** 결제 시작: HOLD → PAYMENT_PENDING (§4) */
	PAYMENT_STARTED,
	/** 결제 승인으로 예약 확정 (§5.1) */
	RESERVATION_CONFIRMED,
	/** 기한 초과·결제 실패로 예약 해제 (§7.1). 원인은 payload의 reason (TIMEOUT / PAY_FAILED) */
	RESERVATION_EXPIRED,
	/** 결제 전·확정 후 취소 (§7.4, §8). 원인은 payload의 reason (USER_BEFORE_PAY / USER / ADMIN) */
	RESERVATION_CANCELED,
	/** 환불 완료 (§7.3, CNL-03) */
	REFUND_COMPLETED,
	/** 취소표 제안 (§9). 대기 토픽, 파티션 키 = waitlist_id */
	WAITLIST_OFFERED,
	/** 매수 한도 초과로 제안 건너뜀 (§9). 대기 토픽, 파티션 키 = waitlist_id */
	WAITLIST_SKIPPED
}
