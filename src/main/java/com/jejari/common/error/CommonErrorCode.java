package com.jejari.common.error;

import org.springframework.http.HttpStatus;

/** 특정 도메인에 속하지 않는 공통 실패 코드. */
public enum CommonErrorCode implements ErrorCode {

	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
	AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
	ACCESS_DENIED(HttpStatus.FORBIDDEN, "권한이 없습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 메서드입니다."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 형식입니다."),
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
	/** Redis 등 필수 연동이 끊겼거나 아직 연결되지 않았을 때. 오래 붙잡지 않고 바로 알려 클라이언트가 재시도 간격을 늘리게 합니다. */
	SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스를 잠시 이용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다.");

	private final HttpStatus status;
	private final String message;

	CommonErrorCode(HttpStatus status, String message) {
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
