package com.jejari.queue;

import org.springframework.http.HttpStatus;

import com.jejari.common.error.ErrorCode;

/**
 * 대기열 실패 코드 (기능설계서 9장). 로그인 필요(AUTH_REQUIRED)·이용 정지(AUTH_BANNED)·503은
 * 공통 코드(CommonErrorCode, AuthErrorCode)를 씁니다.
 */
public enum QueueErrorCode implements ErrorCode {
	RUN_NOT_ON_SALE(HttpStatus.CONFLICT, "판매 중인 운행이 아닙니다."),
	QUEUE_DUPLICATED(HttpStatus.CONFLICT, "이미 다른 열차를 기다리고 있거나, 같은 본인인증의 다른 계정이 대기 중입니다."),
	QUEUE_TOKEN_REQUIRED(HttpStatus.FORBIDDEN, "입장 토큰이 필요합니다."),
	QUEUE_TOKEN_INVALID(HttpStatus.FORBIDDEN, "입장 토큰이 만료됐거나 맞지 않습니다."),
	/** 설계서에 없던 코드: 대기 중이 아닌데 번호표를 조회함 */
	QUEUE_NOT_FOUND(HttpStatus.NOT_FOUND, "대기 중이 아닙니다.");

	private final HttpStatus status;
	private final String message;

	QueueErrorCode(HttpStatus status, String message) {
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
