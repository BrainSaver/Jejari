package com.jejari.notification;

/** notification.type 값 (ERD CHECK와 같음, 기능설계서 EVT-06). */
public enum NotificationType {
	/** 취소표 제안. 강동균의 제안 트랜잭션이 직접 INSERT (§9.1-6) */
	WAITLIST_OFFER,
	/** 결제 기한 임박. {@link PayReminderScanner} */
	PAY_REMINDER,
	/** 예약 만료(기한 초과·결제 실패). RESERVATION_EXPIRED 이벤트 */
	EXPIRED,
	/** 매수 한도 초과로 제안 건너뜀. 강동균의 제안 트랜잭션이 직접 INSERT */
	WAITLIST_SKIPPED,
	/** 환불 완료. REFUND_COMPLETED 이벤트 */
	REFUNDED
}
