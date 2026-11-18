package com.jejari.train.admin;

import org.springframework.http.HttpStatus;

import com.jejari.common.error.ErrorCode;

/** 기준 데이터·운행 관리 화면(ADM-01·02, A-02·A-03) 실패 코드 */
public enum TrainAdminErrorCode implements ErrorCode {
	ALREADY_EXISTS(HttpStatus.CONFLICT, "같은 값이 이미 등록돼 있습니다."),
	IN_USE(HttpStatus.CONFLICT, "이미 쓰이고 있어 바꿀 수 없습니다."),
	REFERENCE_NOT_FOUND(HttpStatus.BAD_REQUEST, "가리키는 대상이 없습니다."),
	INVALID_VALUE(HttpStatus.BAD_REQUEST, "값이 올바르지 않습니다."),
	RUN_NOT_SCHEDULED(HttpStatus.CONFLICT, "판매가 시작된 운행은 바꿀 수 없습니다.");

	private final HttpStatus status;
	private final String message;

	TrainAdminErrorCode(HttpStatus status, String message) {
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
