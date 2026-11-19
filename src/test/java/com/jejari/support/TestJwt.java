package com.jejari.support;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.jejari.auth.jwt.JwtProperties;

/** 테스트 전용 JWT 키·설정. 실제 키가 아닙니다. */
public final class TestJwt {

	/** "0123456789abcdef0123456789abcdef" (32바이트) */
	public static final String K1 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
	/** "fedcba9876543210fedcba9876543210" (32바이트) */
	public static final String K2 = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

	private TestJwt() {
	}

	public static JwtProperties props(String activeKid, Map<String, String> secrets) {
		return new JwtProperties("jejari", Duration.ofMinutes(15), Duration.ofDays(14), Duration.ofSeconds(30),
				activeKid, new LinkedHashMap<>(secrets), true, Duration.ofHours(8), Duration.ofHours(12));
	}

	public static JwtProperties props() {
		return props("k1", Map.of("k1", K1));
	}
}
