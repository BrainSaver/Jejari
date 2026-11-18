package com.jejari.payment;

import org.springframework.http.HttpStatus;

import com.jejari.common.error.ErrorCode;

/**
 * 결제 실패 코드 (기능설계서 9장). 예약 상태 관련 코드(RESERVATION_NOT_FOUND·NOT_HOLD·EXPIRED, REQUEST_IN_PROGRESS)는
 * 좌석 쪽 {@code SeatErrorCode}를 같이 씁니다. 같은 이름의 코드를 두 enum에 두지 않기 위해서입니다.
 */
public enum PaymentErrorCode implements ErrorCode {
	SEAT_LOST(HttpStatus.CONFLICT, "좌석 선점이 이미 풀려 결제를 시작할 수 없습니다. 다시 예매해 주세요."),
	PAYMENT_DUPLICATED(HttpStatus.CONFLICT, "이미 결제를 시작했거나 결제가 끝난 예약입니다.");

	private final HttpStatus status;
	private final String message;

	PaymentErrorCode(HttpStatus status, String message) {
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
