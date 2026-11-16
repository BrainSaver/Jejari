package com.jejari.seat;

import org.springframework.http.HttpStatus;

import com.jejari.common.error.ErrorCode;

/** 좌석 선점 실패 코드 (기능설계서 9장). 판매 전 운행은 QueueErrorCode.RUN_NOT_ON_SALE을 함께 씁니다. */
public enum SeatErrorCode implements ErrorCode {
	SEAT_ALREADY_TAKEN(HttpStatus.CONFLICT, "다른 사람이 먼저 선택한 좌석입니다."),
	LIMIT_PER_RESERVATION(HttpStatus.valueOf(422), "한 번에 예매할 수 있는 매수를 넘었습니다."),
	LIMIT_PER_RUN(HttpStatus.valueOf(422), "이 열차에서 예매할 수 있는 매수를 넘었습니다."),
	LIMIT_PER_DAY(HttpStatus.valueOf(422), "오늘 예매할 수 있는 매수를 넘었습니다."),
	LIMIT_PER_HOLIDAY(HttpStatus.valueOf(422), "이번 명절에 예매할 수 있는 매수를 넘었습니다."),
	REQUEST_IN_PROGRESS(HttpStatus.ACCEPTED, "같은 요청을 처리하고 있습니다. 잠시 후 예매 내역을 확인해 주세요.");

	private final HttpStatus status;
	private final String message;

	SeatErrorCode(HttpStatus status, String message) {
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
