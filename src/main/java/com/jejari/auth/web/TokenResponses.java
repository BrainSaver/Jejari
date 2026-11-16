package com.jejari.auth.web;

import java.time.Clock;
import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.jejari.auth.jwt.JwtProperties;
import com.jejari.auth.session.AuthTokenService.IssuedTokens;

/**
 * 토큰 응답 만들기. 로그인(강다인)·관리자 로그인·재발급이 같은 형식을 쓰도록 여기 모읍니다.
 *
 * <ul>
 *   <li>액세스 토큰: 응답 본문 {@code { accessToken, tokenType, expiresIn }}</li>
 *   <li>리프레시 토큰: HttpOnly 쿠키. JavaScript가 읽을 수 없어 XSS로 훔쳐 가기 어렵고,
 *       Path를 /api/v1/auth로 좁혀 다른 API 요청에는 실리지 않습니다. SameSite=Strict로 다른 사이트발 요청에 안 붙습니다.</li>
 * </ul>
 */
@Component
public class TokenResponses {

	public static final String REFRESH_COOKIE = "jejari_rt";
	static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

	private final JwtProperties props;
	private final Clock clock;

	public TokenResponses(JwtProperties props, Clock clock) {
		this.props = props;
		this.clock = clock;
	}

	/** 로그인·재발급 성공 응답 (200 + 본문 + Set-Cookie). */
	public ResponseEntity<TokenResponse> ok(IssuedTokens tokens) {
		Duration refreshLeft = Duration.between(clock.instant(), tokens.refreshExpiresAt());
		long expiresIn = Duration.between(clock.instant(), tokens.accessExpiresAt()).toSeconds();
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, cookie(tokens.refreshToken(), refreshLeft).toString())
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(new TokenResponse(tokens.accessToken(), "Bearer", expiresIn));
	}

	/** 쿠키 삭제 헤더 (로그아웃). */
	public String clearCookie() {
		return cookie("", Duration.ZERO).toString();
	}

	private ResponseCookie cookie(String value, Duration maxAge) {
		return ResponseCookie.from(REFRESH_COOKIE, value)
				.httpOnly(true)
				.secure(props.refreshCookieSecure())
				.sameSite("Strict")
				.path(REFRESH_COOKIE_PATH)
				.maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
				.build();
	}

	/** @param expiresIn 액세스 토큰 남은 초 */
	public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}
}
