package com.jejari.auth.jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 만료 검사. 만료는 별도 오류 코드로 내보내서 응답을 AUTH_TOKEN_EXPIRED(재발급하면 됨)와
 * AUTH_TOKEN_INVALID(다시 로그인)로 나눌 수 있게 합니다. 앱 시계(Clock)를 쓰므로 테스트에서 시간을 고정할 수 있습니다.
 */
public class AccessTokenExpiryValidator implements OAuth2TokenValidator<Jwt> {

	/** 만료 오류 코드. JwtAuthenticationEntryPoint가 이 값을 보고 AUTH_TOKEN_EXPIRED로 응답합니다. */
	public static final String EXPIRED = "token_expired";

	private final Clock clock;
	private final Duration clockSkew;

	public AccessTokenExpiryValidator(Clock clock, Duration clockSkew) {
		this.clock = clock;
		this.clockSkew = clockSkew;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		Instant exp = jwt.getExpiresAt();
		if (exp == null) {
			return OAuth2TokenValidatorResult.failure(
					new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "exp 클레임 없음", null));
		}
		if (clock.instant().isAfter(exp.plus(clockSkew))) {
			return OAuth2TokenValidatorResult.failure(new OAuth2Error(EXPIRED, "access token expired", null));
		}
		return OAuth2TokenValidatorResult.success();
	}
}
