package com.jejari.auth.jwt;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.jejari.auth.AuthErrorCode;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.common.error.ErrorCode;
import com.jejari.common.error.ErrorResponseWriter;

/**
 * 401 응답을 원인별 코드로 나눕니다.
 * <ul>
 *   <li>토큰 없음 → AUTH_REQUIRED</li>
 *   <li>만료 → AUTH_TOKEN_EXPIRED (클라이언트가 /auth/refresh 후 재시도)</li>
 *   <li>서명 불일치·형식 오류·클레임 이상 → AUTH_TOKEN_INVALID (다시 로그인)</li>
 * </ul>
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ErrorResponseWriter errorWriter;

	public JwtAuthenticationEntryPoint(ErrorResponseWriter errorWriter) {
		this.errorWriter = errorWriter;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
			throws IOException {
		response.setHeader("WWW-Authenticate", "Bearer");
		errorWriter.write(response, classify(e));
	}

	static ErrorCode classify(AuthenticationException e) {
		if (isExpired(e)) {
			return AuthErrorCode.AUTH_TOKEN_EXPIRED;
		}
		if (e instanceof InvalidBearerTokenException) {
			return AuthErrorCode.AUTH_TOKEN_INVALID;
		}
		return CommonErrorCode.AUTH_REQUIRED;
	}

	private static boolean isExpired(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof JwtValidationException jve) {
				return jve.getErrors().stream()
						.anyMatch(err -> AccessTokenExpiryValidator.EXPIRED.equals(err.getErrorCode()));
			}
		}
		return false;
	}
}
