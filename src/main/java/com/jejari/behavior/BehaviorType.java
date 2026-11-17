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
	CANCEL,
	/**
	 * 취소표 대기 신청 WL-01 (D) — 실패(409·422)도 남긴다.
	 * 취소표는 대기열 입장 토큰 없이 들어오는 유일한 경로라, 남기지 않으면 매크로가 쓰는 길 하나가
	 * 통째로 로그에 안 보인다 (concurrency.md §9.3 "봇은 매진 직후 바로 신청한다").
	 */
	WAITLIST_APPLY,
	/** 취소표 대기 취소 WL-02 (D). 신청↔취소를 반복하는 패턴을 보려면 둘 다 있어야 한다 */
	WAITLIST_CANCEL
}
