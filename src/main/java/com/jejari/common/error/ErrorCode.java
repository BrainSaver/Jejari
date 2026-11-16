package com.jejari.common.error;

import org.springframework.http.HttpStatus;

/**
 * 실패 응답 코드. 도메인마다 enum으로 구현합니다 (예: AuthErrorCode, ReservationErrorCode).
 * 코드 값과 HTTP 상태는 기능설계서 9장 표를 따릅니다.
 */
public interface ErrorCode {

	/** 응답의 code. enum이면 name()을 그대로 씁니다 (예: SEAT_ALREADY_TAKEN). */
	String code();

	HttpStatus status();

	/** 사용자에게 보여줄 기본 메시지. 개인정보·내부 정보를 넣지 않습니다. */
	String message();
}
