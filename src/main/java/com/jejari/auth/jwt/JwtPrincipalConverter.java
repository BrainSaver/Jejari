package com.jejari.auth.jwt;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import com.jejari.auth.ActorType;
import com.jejari.auth.AdminRole;
import com.jejari.auth.AuthPrincipal;

/** 서명·만료 검증을 통과한 JWT를 {@link AuthPrincipal}로 바꿉니다. 클레임이 이상하면 위조로 보고 거부합니다. */
public class JwtPrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		try {
			ActorType actorType = ActorType.valueOf(jwt.getClaimAsString(AccessTokenIssuer.CLAIM_ROLE));
			long actorId = Long.parseLong(jwt.getSubject());
			String sessionId = jwt.getClaimAsString(AccessTokenIssuer.CLAIM_SESSION);
			if (sessionId == null) {
				throw new IllegalArgumentException("sid 없음");
			}
			AdminRole adminRole = null;
			if (actorType == ActorType.ADMIN) {
				// 관리자 토큰은 세부 권한이 반드시 있어야 함 (없으면 위조·손상으로 봄)
				adminRole = AdminRole.valueOf(jwt.getClaimAsString(AccessTokenIssuer.CLAIM_ADMIN_ROLE));
			}
			return new JejariAuthentication(new AuthPrincipal(actorType, actorId, sessionId, adminRole), jwt);
		} catch (RuntimeException e) {
			throw new InvalidBearerTokenException("invalid claims");
		}
	}
}
