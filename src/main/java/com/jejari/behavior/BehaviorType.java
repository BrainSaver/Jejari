package com.jejari.behavior;

/** behavior_log.event_type (ERD). 남기는 곳은 행동로그-이벤트.md 표 참고. */
public enum BehaviorType {
	/** 운행 검색 TRN-02 (B) */
	VIEW_TRAIN,
	/** 좌석 배치도 RSV-01 (B) */
	VIEW_SEAT,
	/** 대기열 진입 QUE-01 (A) */
	QUEUE_ENTER,
	/** 좌석 선점 RSV-02 (B) — 실패(409)도 남긴다 */
	SEAT_HOLD,
	/** 결제 시작 PAY-01 (C) */
	PAY,
	/** 결제 전 취소 RSV-05 (B), 확정 취소 CNL-02 (D) */
	CANCEL
}
