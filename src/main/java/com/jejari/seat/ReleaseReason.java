package com.jejari.seat;

/**
 * 좌석 해제 이유 (concurrency.md §7.1 표). 모든 해제는 빈 좌석 이벤트를 남겨 취소표 제안(§9)으로 이어집니다.
 *
 * <pre>
 * 호출                 from             to        event_type             actor
 * §6.1 HOLD 만료        HOLD             EXPIRED   RESERVATION_EXPIRED    SYSTEM
 * §5.2 결제 실패        PAYMENT_PENDING  EXPIRED   RESERVATION_EXPIRED    SYSTEM
 * §7.4 결제 전 취소     HOLD             CANCELED  RESERVATION_CANCELED   USER
 * §7.5 결제 단계 차단   HOLD             CANCELED  RESERVATION_CANCELED   SYSTEM
 * </pre>
 * 재선점(PREEMPTED)은 {@link SeatHoldTx}가 선점 트랜잭션 안에서 같은 단계를 직접 실행합니다.
 */
public enum ReleaseReason {
	TIMEOUT("HOLD", "EXPIRED", "RESERVATION_EXPIRED", "SYSTEM"),
	PAY_FAILED("PAYMENT_PENDING", "EXPIRED", "RESERVATION_EXPIRED", "SYSTEM"),
	USER_BEFORE_PAY("HOLD", "CANCELED", "RESERVATION_CANCELED", "USER"),
	/** 결제 단계 차단 (PaymentRiskGuard, 11-23): 위험 점수가 케이스 기준 이상이라 결제를 막고 좌석을 바로 돌려줌. SYSTEM이라 위험 점수의 취소 수에 세지 않음 */
	RISK_BLOCKED("HOLD", "CANCELED", "RESERVATION_CANCELED", "SYSTEM");

	final String from;
	final String to;
	final String eventType;
	final String actor;

	ReleaseReason(String from, String to, String eventType, String actor) {
		this.from = from;
		this.to = to;
		this.eventType = eventType;
		this.actor = actor;
	}
}
