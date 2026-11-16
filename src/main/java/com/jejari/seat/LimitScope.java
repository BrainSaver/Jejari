package com.jejari.seat;

/** 카운터로 세는 매수 제한 범위 (purchase_counter.scope). 1회 한도(PER_RESERVATION)는 카운터 없이 값만 비교합니다. */
public enum LimitScope {
	PER_DAY(SeatErrorCode.LIMIT_PER_DAY),
	PER_HOLIDAY(SeatErrorCode.LIMIT_PER_HOLIDAY),
	PER_RUN(SeatErrorCode.LIMIT_PER_RUN);

	final SeatErrorCode error;

	LimitScope(SeatErrorCode error) {
		this.error = error;
	}
}
