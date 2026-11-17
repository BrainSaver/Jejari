package com.jejari.reservation;

import org.springframework.http.HttpStatus;

import com.jejari.common.error.ErrorCode;

/** 확정 예약 취소 실패 코드 (기능설계서 9장 CNL-01·02) */
public enum ReservationErrorCode implements ErrorCode {
	RESERVATION_NOT_CONFIRMED(HttpStatus.CONFLICT, "확정된 예약만 이 방법으로 취소할 수 있습니다."),
	CANCEL_NOT_ALLOWED(HttpStatus.CONFLICT, "지금은 취소할 수 없는 예약입니다.");

	private final HttpStatus status;
	private final String message;

	ReservationErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	@Override
	public String code() {
		return name();
	}

	@Override
	public HttpStatus status() {
		return status;
	}

	@Override
	public String message() {
		return message;
	}
}
