package com.jejari.waitlist;

import org.springframework.http.HttpStatus;

import com.jejari.common.error.ErrorCode;

/** 취소표 대기 실패 코드 (기능설계서 9장, U-10) */
public enum WaitlistErrorCode implements ErrorCode {
	WAITLIST_NOT_SOLD_OUT(HttpStatus.CONFLICT, "아직 남은 좌석이 있습니다. 바로 예매해 주세요."),
	WAITLIST_DUPLICATED(HttpStatus.CONFLICT, "이 운행은 이미 취소표를 기다리고 있습니다."),
	WAITLIST_LIMIT(HttpStatus.valueOf(422), "취소표 대기는 정해진 건수까지만 신청할 수 있습니다."),
	WAITLIST_RESTRICTED(HttpStatus.FORBIDDEN, "이용 제한 중이라 취소표 대기를 신청할 수 없습니다."),
	WAITLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "취소표 대기 건을 찾을 수 없습니다."),
	WAITLIST_NOT_WAITING(HttpStatus.CONFLICT, "기다리는 중인 대기만 취소할 수 있습니다. 제안받은 좌석은 예매 내역에서 취소해 주세요.");

	private final HttpStatus status;
	private final String message;

	WaitlistErrorCode(HttpStatus status, String message) {
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
