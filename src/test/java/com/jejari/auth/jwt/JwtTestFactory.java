package com.jejari.auth.jwt;

import java.time.Clock;

import org.springframework.security.oauth2.jwt.JwtDecoder;

/** 다른 패키지 테스트에서 Spring 없이 발급기·검증기를 만들 때. */
public final class JwtTestFactory {

	private static final JwtConfig CONFIG = new JwtConfig();

	private JwtTestFactory() {
	}

	public static AccessTokenIssuer issuer(JwtProperties props, Clock clock) {
		return new AccessTokenIssuer(CONFIG.jwtEncoder(CONFIG.jwtKeySource(props)), props, clock);
	}

	public static JwtDecoder decoder(JwtProperties props, Clock clock) {
		return CONFIG.jwtDecoder(CONFIG.jwtKeySource(props), props, clock);
	}
}
