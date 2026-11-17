package com.jejari.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import com.jejari.auth.ActorType;
import com.jejari.auth.AdminRole;
import com.jejari.auth.AuthPrincipal;
import com.jejari.support.MutableClock;
import com.jejari.support.TestJwt;

/** 관리자 토큰의 세부 권한(adm) 클레임과 권한 목록 (AUTH-05). */
class AdminTokenTest {

	final MutableClock clock = new MutableClock();
	final AccessTokenIssuer issuer = JwtTestFactory.issuer(TestJwt.props(), clock);
	final JwtPrincipalConverter converter = new JwtPrincipalConverter();

	Jwt decode(String token) {
		return JwtTestFactory.decoder(TestJwt.props(), clock).decode(token);
	}

	@Test
	void 관리자_토큰에는_세부_권한이_들어간다() {
		Jwt jwt = decode(issuer.issue(ActorType.ADMIN, 7L, "sid", AdminRole.REVIEWER).value());
		assertThat(jwt.getClaimAsString("adm")).isEqualTo("REVIEWER");

		JejariAuthentication auth = (JejariAuthentication) converter.convert(jwt);
		assertThat(auth.getPrincipal()).isEqualTo(new AuthPrincipal(ActorType.ADMIN, 7L, "sid", AdminRole.REVIEWER));
		assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
				.containsExactlyInAnyOrder("ROLE_ADMIN", "ADMIN_VIEWER", "ADMIN_REVIEWER");
	}

	@Test
	void 회원_토큰에는_adm이_없고_회원_권한만() {
		Jwt jwt = decode(issuer.issue(ActorType.MEMBER, 1L, "sid", AdminRole.SUPERVISOR).value()); // 회원이면 무시
		assertThat(jwt.getClaims()).doesNotContainKey("adm");
		assertThat(converter.convert(jwt).getAuthorities()).extracting(GrantedAuthority::getAuthority)
				.containsExactly("ROLE_MEMBER");
	}

	@Test
	void adm이_없는_관리자_토큰은_위조로_본다() {
		Jwt jwt = decode(issuer.issue(ActorType.ADMIN, 7L, "sid", null).value());
		assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(InvalidBearerTokenException.class);
	}

	@Test
	void adm_값이_이상하면_위조로_본다() {
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256")
				.claims(c -> c.putAll(Map.of("sub", "7", "role", "ADMIN", "sid", "s", "adm", "ROOT")))
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
		assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(InvalidBearerTokenException.class);
	}

	@Test
	void 상위_권한은_하위_권한을_모두_가진다() {
		assertThat(AdminRole.VIEWER.authorities()).containsExactly("ADMIN_VIEWER");
		assertThat(AdminRole.SUPERVISOR.authorities())
				.containsExactly("ADMIN_VIEWER", "ADMIN_REVIEWER", "ADMIN_SUPERVISOR");
	}
}
