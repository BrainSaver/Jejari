package com.jejari.common.error;

/**
 * 예상된 실패. 서비스 코드에서 {@code throw new BusinessException(XxxErrorCode.YYY)}로 던지면
 * GlobalExceptionHandler가 { code, message, traceId } 응답으로 바꿉니다.
 */
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(errorCode.message());
		this.errorCode = errorCode;
	}

	/** 기본 메시지 대신 상황에 맞는 메시지를 줄 때. 개인정보를 넣지 않습니다. */
	public BusinessException(ErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public BusinessException(ErrorCode errorCode, Throwable cause) {
		super(errorCode.message(), cause);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}
}
