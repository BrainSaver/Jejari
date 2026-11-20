package com.jejari.common.error;

/**
 * "몇 초 뒤 다시"를 알려 주는 예상된 실패. GlobalExceptionHandler가 {@code Retry-After} 헤더를 붙인다.
 * 화면·클라이언트는 그 시간만큼 기다렸다가 자동으로 다시 보내면 된다 (로그인 대기실이 가득 찼을 때 등).
 */
public class RetryAfterException extends BusinessException {

	private final long retryAfterSec;

	public RetryAfterException(ErrorCode errorCode, long retryAfterSec) {
		super(errorCode);
		this.retryAfterSec = Math.max(1, retryAfterSec);
	}

	public long retryAfterSec() {
		return retryAfterSec;
	}
}
