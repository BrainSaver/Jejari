package com.jejari.member.me;

import org.springframework.http.HttpStatus;

import com.jejari.common.error.ErrorCode;

/** 내 정보·탈퇴 실패 코드 (AUTH-06) */
public enum MyAccountErrorCode implements ErrorCode {
	MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원 정보를 찾을 수 없습니다."),
	WITHDRAW_ACTIVE_RESERVATION(HttpStatus.CONFLICT, "진행 중인 예약이 있어 탈퇴할 수 없습니다. 예약을 먼저 취소해 주세요.");

	private final HttpStatus status;
	private final String message;

	MyAccountErrorCode(HttpStatus status, String message) {
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
