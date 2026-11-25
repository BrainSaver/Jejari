package com.jejari.common.error;

import jakarta.servlet.http.HttpServletRequest;

/** 다른 패키지 테스트에서 패키지 전용 판정 함수를 부르기 위한 통로 */
public final class GlobalExceptionHandlerAccess {

	private GlobalExceptionHandlerAccess() {
	}

	public static boolean isBrowserPage(HttpServletRequest request) {
		return GlobalExceptionHandler.isBrowserPage(request);
	}
}
