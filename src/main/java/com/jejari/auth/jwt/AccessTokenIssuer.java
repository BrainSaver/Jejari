package com.jejari.auth.jwt;

import java.time.Clock;
import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import com.jejari.auth.ActorType;

/**
 * 액세스 토큰 발급.
 *
 * <p>payload에는 식별자만 넣습니다: sub(actorId), role, sid(세션 ID), iss, iat, exp.
 * JWT는 서명만 되어 있고 암호화되지 않아 누구나 Base64로 읽을 수 있으므로
 * 이메일·이름·전화번호 같은 개인정보는 넣지 않습니다.
 */
@Component
public class AccessTokenIssuer {

	public static final String CLAIM_ROLE = "role";
	public static final String CLAIM_SESSION = "sid";

	private final JwtEncoder encoder;
	private final JwtProperties props;
	private final Clock clock;

	public AccessTokenIssuer(JwtEncoder encoder, JwtProperties props, Clock clock) {
		this.encoder = encoder;
		this.props = props;
		this.clock = clock;
	}

	public IssuedAccessToken issue(ActorType actorType, long actorId, String sessionId) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(props.accessTtl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(props.issuer())
				.subject(String.valueOf(actorId))
				.issuedAt(now)
				.expiresAt(expiresAt)
				.claim(CLAIM_ROLE, actorType.name())
				.claim(CLAIM_SESSION, sessionId)
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).keyId(props.activeKid()).build();
		String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedAccessToken(token, expiresAt);
	}

	public record IssuedAccessToken(String value, Instant expiresAt) {
	}
}
