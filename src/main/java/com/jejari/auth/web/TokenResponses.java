package com.jejari.auth.web;

import java.time.Clock;
import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.jejari.auth.ActorType;
import com.jejari.auth.jwt.JwtProperties;
import com.jejari.auth.session.AuthTokenService.IssuedTokens;

/**
 * 토큰 응답 만들기. 로그인(강다인)·관리자 로그인·재발급이 같은 형식을 쓰도록 여기 모읍니다.
 *
 * <ul>
 *   <li>액세스 토큰: 응답 본문 {@code { accessToken, tokenType, expiresIn }}</li>
 *   <li>리프레시 토큰: HttpOnly 쿠키. JavaScript가 읽을 수 없어 XSS로 훔쳐 가기 어렵고,
 *       Path를 /api/v1/auth로 좁혀 다른 API 요청에는 실리지 않습니다. SameSite=Strict로 다른 사이트발 요청에 안 붙습니다.</li>
 *   <li><b>회원과 관리자 쿠키를 나눈다</b>: 회원 {@code jejari_rt}(/api/v1/auth), 관리자 {@code jejari_art}(/api/v1/admin/auth).
 *       하나를 같이 쓰면 한 브라우저에서 회원으로 로그인하는 순간 관리자 로그인이 덮어써졌다 (2026-11-19).</li>
 *   <li><b>브라우저를 닫으면 사라지는 쿠키</b>(Max-Age 없음): 관리자, 그리고 "로그인 상태 유지"를 고르지 않은 회원.
 *       공용 PC에서 로그아웃을 잊어도 다음 사람이 들어오지 못하게. 서버 세션도 8·12시간이면 끝난다.</li>
 * </ul>
 */
@Component
public class TokenResponses {

	public static final String REFRESH_COOKIE = "jejari_rt";
	static final String REFRESH_COOKIE_PATH = "/api/v1/auth";
	public static final String ADMIN_REFRESH_COOKIE = "jejari_art";
	static final String ADMIN_REFRESH_COOKIE_PATH = "/api/v1/admin/auth";

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
				.header(HttpHeaders.SET_COOKIE,
						cookie(tokens.actorType(), tokens.refreshToken(), tokens.persistent() ? refreshLeft : null).toString())
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(new TokenResponse(tokens.accessToken(), "Bearer", expiresIn));
	}

	/** 회원 쿠키 삭제 헤더 (로그아웃). */
	public String clearCookie() {
		return clearCookie(ActorType.MEMBER);
	}

	/** 그 종류의 쿠키 삭제 헤더 */
	public String clearCookie(ActorType actorType) {
		return cookie(actorType, "", Duration.ZERO).toString();
	}

	/** @param maxAge null이면 브라우저를 닫으면 사라지는 쿠키 (Max-Age 없음) */
	private ResponseCookie cookie(ActorType actorType, String value, Duration maxAge) {
		boolean admin = actorType == ActorType.ADMIN;
		ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(admin ? ADMIN_REFRESH_COOKIE : REFRESH_COOKIE, value)
				.httpOnly(true)
				.secure(props.refreshCookieSecure())
				.sameSite("Strict")
				.path(admin ? ADMIN_REFRESH_COOKIE_PATH : REFRESH_COOKIE_PATH);
		if (maxAge != null) {
			b.maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge);
		}
		return b.build();
	}

	/** @param expiresIn 액세스 토큰 남은 초 */
	public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}
}
