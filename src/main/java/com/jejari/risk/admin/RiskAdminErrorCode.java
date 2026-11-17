package com.jejari.risk.admin;

import org.springframework.http.HttpStatus;

import com.jejari.common.error.ErrorCode;

/** 위험 관리 화면(ADM-04~06) 실패 코드 */
public enum RiskAdminErrorCode implements ErrorCode {
	CASE_NOT_FOUND(HttpStatus.NOT_FOUND, "의심 케이스를 찾을 수 없습니다."),
	CASE_CLOSED(HttpStatus.CONFLICT, "이미 판정이 끝난 케이스입니다."),
	ACTION_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "조치 사유를 입력해 주세요."),
	ACTION_NOT_ALLOWED(HttpStatus.CONFLICT, "이 판정과 함께 할 수 없는 조치입니다.");

	private final HttpStatus status;
	private final String message;

	RiskAdminErrorCode(HttpStatus status, String message) {
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
