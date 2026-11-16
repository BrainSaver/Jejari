package com.jejari.auth.jwt;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 설정 ({@code jejari.jwt.*}, application.yml).
 *
 * <p>키 교체: secrets에 새 키(k2)를 추가 → active-kid를 k2로 → 액세스 토큰 TTL(15분)이 지난 뒤 k1 삭제.
 * 그 사이 k1로 서명된 토큰도 kid 헤더로 찾아 검증됩니다.
 *
 * @param issuer             iss 클레임
 * @param accessTtl          액세스 토큰 유효시간
 * @param refreshTtl         리프레시 토큰(로그인 세션) 유효시간. 로그인 시점부터 고정, 재발급해도 늘지 않음
 * @param clockSkew          서버 간 시계 오차 허용
 * @param activeKid          새 토큰 서명에 쓸 키 ID
 * @param secrets            kid → Base64 비밀키 (32바이트 이상). 값은 .env로만 넣습니다
 * @param refreshCookieSecure 리프레시 쿠키 Secure 속성. HTTPS가 아닌 로컬에서만 false
 */
@ConfigurationProperties("jejari.jwt")
public record JwtProperties(
		String issuer,
		Duration accessTtl,
		Duration refreshTtl,
		Duration clockSkew,
		String activeKid,
		Map<String, String> secrets,
		boolean refreshCookieSecure) {

	/** HS256 키 최소 길이 (RFC 7518: 해시 출력 크기 이상). */
	static final int MIN_SECRET_BYTES = 32;

	public JwtProperties {
		if (secrets == null || secrets.isEmpty()) {
			throw new IllegalStateException("jejari.jwt.secrets가 비어 있습니다. .env에 JWT_SECRET_K1을 넣으세요 (.env.example 참고)");
		}
		if (activeKid == null || !secrets.containsKey(activeKid)) {
			throw new IllegalStateException("jejari.jwt.active-kid(" + activeKid + ")에 해당하는 키가 secrets에 없습니다");
		}
		secrets.forEach((kid, secret) -> decode(kid, secret)); // 기동 시 길이 검사
	}

	/** Base64 비밀키를 바이트로. 32바이트 미만이면 기동을 멈춥니다. 키 값은 오류 메시지에 넣지 않습니다. */
	static byte[] decode(String kid, String secret) {
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(secret == null ? "" : secret.trim());
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException("JWT 키 " + kid + "가 Base64 형식이 아닙니다");
		}
		if (bytes.length < MIN_SECRET_BYTES) {
			throw new IllegalStateException("JWT 키 " + kid + "가 너무 짧습니다 (" + bytes.length
					+ "바이트, 최소 " + MIN_SECRET_BYTES + "). openssl rand -base64 32 로 만드세요");
		}
		return bytes;
	}
}
