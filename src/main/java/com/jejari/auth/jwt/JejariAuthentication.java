package com.jejari.auth.jwt;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import com.jejari.auth.AuthPrincipal;

/** 검증된 액세스 토큰으로 만든 인증 정보. principal이 {@link AuthPrincipal}입니다. */
public class JejariAuthentication extends AbstractAuthenticationToken {

	private final AuthPrincipal principal;
	private final Jwt jwt;

	public JejariAuthentication(AuthPrincipal principal, Jwt jwt) {
		super(List.of(new SimpleGrantedAuthority(principal.actorType().authority())));
		this.principal = principal;
		this.jwt = jwt;
		setAuthenticated(true);
	}

	@Override
	public AuthPrincipal getPrincipal() {
		return principal;
	}

	/** 원본 토큰. 로그·응답에 그대로 쓰지 않습니다. */
	@Override
	public Jwt getCredentials() {
		return jwt;
	}

	@Override
	public String getName() {
		return principal.actorType() + ":" + principal.actorId();
	}
}
