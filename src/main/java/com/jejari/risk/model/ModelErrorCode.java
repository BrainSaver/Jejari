package com.jejari.risk.model;

import org.springframework.http.HttpStatus;

import com.jejari.common.error.ErrorCode;

/** A-09 모델 관리 실패 코드. */
public enum ModelErrorCode implements ErrorCode {
	MODEL_NOT_FOUND(HttpStatus.NOT_FOUND, "모델 버전을 찾을 수 없습니다."),
	MODEL_NOT_SCORED(HttpStatus.CONFLICT, "이 버전으로 매긴 점수가 아직 없어 사용 중으로 바꿀 수 없습니다.");

	private final HttpStatus status;
	private final String message;

	ModelErrorCode(HttpStatus status, String message) {
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
