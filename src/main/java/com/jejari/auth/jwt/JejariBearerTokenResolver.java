package com.jejari.auth.jwt;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * 인증 경로(/api/v1/auth/**)에서는 Authorization 헤더를 읽지 않습니다. 단 로그아웃은 예외(현재 세션을 알아야 함).
 *
 * <p>이유: 프론트가 모든 요청에 Bearer 헤더를 붙이면, 액세스 토큰이 만료된 상태로 /auth/refresh를 부를 때
 * 필터가 먼저 만료(401)로 막아 재발급이 영원히 안 됩니다. 로그인도 같은 문제가 생깁니다.
 */
public class JejariBearerTokenResolver implements BearerTokenResolver {

	private static final String AUTH_PATH = "/api/v1/auth/";
	private static final String LOGOUT_PATH = "/api/v1/auth/logout";

	private final DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();

	@Override
	public String resolve(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		if (path.startsWith(AUTH_PATH) && !path.equals(LOGOUT_PATH)) {
			return null;
		}
		return delegate.resolve(request);
	}
}
