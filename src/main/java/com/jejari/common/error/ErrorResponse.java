package com.jejari.common.error;

import com.jejari.common.web.TraceId;

/** 실패 응답 본문 (기능설계서 7장): { "code": "...", "message": "...", "traceId": "..." } */
public record ErrorResponse(String code, String message, String traceId) {

	public static ErrorResponse of(ErrorCode errorCode) {
		return of(errorCode, errorCode.message());
	}

	public static ErrorResponse of(ErrorCode errorCode, String message) {
		return new ErrorResponse(errorCode.code(), message, TraceId.current());
	}
}
