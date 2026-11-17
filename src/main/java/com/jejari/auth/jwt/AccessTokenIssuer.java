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
import com.jejari.auth.AdminRole;

/**
 * 액세스 토큰 발급.
 *
 * <p>payload에는 식별자만 넣습니다: sub(actorId), role, sid(세션 ID), iss, iat, exp. 관리자는 세부 권한 adm도.
 * JWT는 서명만 되어 있고 암호화되지 않아 누구나 Base64로 읽을 수 있으므로
 * 이메일·이름·전화번호 같은 개인정보는 넣지 않습니다.
 */
@Component
public class AccessTokenIssuer {

	public static final String CLAIM_ROLE = "role";
	public static final String CLAIM_SESSION = "sid";
	/** 관리자 세부 권한 (VIEWER / REVIEWER / SUPERVISOR). 회원 토큰에는 없음 */
	public static final String CLAIM_ADMIN_ROLE = "adm";

	private final JwtEncoder encoder;
	private final JwtProperties props;
	private final Clock clock;

	public AccessTokenIssuer(JwtEncoder encoder, JwtProperties props, Clock clock) {
		this.encoder = encoder;
		this.props = props;
		this.clock = clock;
	}

	public IssuedAccessToken issue(ActorType actorType, long actorId, String sessionId) {
		return issue(actorType, actorId, sessionId, null);
	}

	/** @param adminRole 관리자면 세부 권한, 회원이면 null */
	public IssuedAccessToken issue(ActorType actorType, long actorId, String sessionId, AdminRole adminRole) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(props.accessTtl());
		JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
				.issuer(props.issuer())
				.subject(String.valueOf(actorId))
				.issuedAt(now)
				.expiresAt(expiresAt)
				.claim(CLAIM_ROLE, actorType.name())
				.claim(CLAIM_SESSION, sessionId);
		if (actorType == ActorType.ADMIN && adminRole != null) {
			builder.claim(CLAIM_ADMIN_ROLE, adminRole.name());
		}
		JwtClaimsSet claims = builder.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).keyId(props.activeKid()).build();
		String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedAccessToken(token, expiresAt);
	}

	public record IssuedAccessToken(String value, Instant expiresAt) {
	}
}
