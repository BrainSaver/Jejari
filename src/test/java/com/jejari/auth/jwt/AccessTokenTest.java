package com.jejari.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import com.jejari.auth.ActorType;
import com.jejari.support.MutableClock;
import com.jejari.support.TestJwt;

/** 액세스 토큰 발급·검증 (Spring 컨텍스트 없이). */
class AccessTokenTest {

	final JwtConfig config = new JwtConfig();
	final MutableClock clock = new MutableClock();

	AccessTokenIssuer issuer(JwtProperties props) {
		return new AccessTokenIssuer(config.jwtEncoder(config.jwtKeySource(props)), props, clock);
	}

	JwtDecoder decoder(JwtProperties props) {
		return config.jwtDecoder(config.jwtKeySource(props), props, clock);
	}

	@Test
	void 발급한_토큰을_검증하면_식별자만_들어있다() {
		JwtProperties props = TestJwt.props();
		String token = issuer(props).issue(ActorType.MEMBER, 1001L, "sid-1").value();

		Jwt jwt = decoder(props).decode(token);

		assertThat(jwt.getSubject()).isEqualTo("1001");
		assertThat(jwt.getClaimAsString("role")).isEqualTo("MEMBER");
		assertThat(jwt.getClaimAsString("sid")).isEqualTo("sid-1");
		assertThat(jwt.getHeaders()).containsEntry("kid", "k1").containsEntry("alg", "HS256");
		assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "iat", "exp", "role", "sid");
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
	}

	@Test
	void 만료되면_token_expired_오류() {
		JwtProperties props = TestJwt.props();
		String token = issuer(props).issue(ActorType.MEMBER, 1L, "s").value();

		clock.advance(Duration.ofMinutes(15).plusSeconds(29)); // 오차 30초 안 → 통과
		decoder(props).decode(token);

		clock.advance(Duration.ofSeconds(2));
		assertThatThrownBy(() -> decoder(props).decode(token))
				.isInstanceOf(JwtValidationException.class)
				.satisfies(e -> assertThat(((JwtValidationException) e).getErrors())
						.anyMatch(err -> AccessTokenExpiryValidator.EXPIRED.equals(err.getErrorCode())));
	}

	@Test
	void 서명을_바꾸면_거부() {
		JwtProperties props = TestJwt.props();
		String token = issuer(props).issue(ActorType.MEMBER, 1L, "s").value();
		String[] parts = token.split("\\.");
		String forgedPayload = b64("{\"iss\":\"jejari\",\"sub\":\"1\",\"role\":\"ADMIN\",\"sid\":\"s\",\"exp\":9999999999}");

		assertThatThrownBy(() -> decoder(props).decode(parts[0] + "." + forgedPayload + "." + parts[2]))
				.isInstanceOf(BadJwtException.class);
	}

	@Test
	void alg_none_토큰은_거부() {
		String unsigned = b64("{\"alg\":\"none\"}") + "."
				+ b64("{\"iss\":\"jejari\",\"sub\":\"1\",\"role\":\"ADMIN\",\"sid\":\"s\",\"exp\":9999999999}") + ".";

		assertThatThrownBy(() -> decoder(TestJwt.props()).decode(unsigned)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void 키_교체_중에는_옛_키로_서명된_토큰도_검증된다() {
		String oldToken = issuer(TestJwt.props()).issue(ActorType.MEMBER, 1L, "s").value();
		JwtProperties rotated = TestJwt.props("k2", Map.of("k1", TestJwt.K1, "k2", TestJwt.K2));

		assertThat(decoder(rotated).decode(oldToken).getSubject()).isEqualTo("1");
		assertThat(decoder(rotated).decode(issuer(rotated).issue(ActorType.MEMBER, 2L, "s").value())
				.getHeaders()).containsEntry("kid", "k2");
	}

	@Test
	void 지운_키로_서명된_토큰은_거부() {
		String oldToken = issuer(TestJwt.props()).issue(ActorType.MEMBER, 1L, "s").value();
		JwtProperties onlyK2 = TestJwt.props("k2", Map.of("k2", TestJwt.K2));

		assertThatThrownBy(() -> decoder(onlyK2).decode(oldToken)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void 짧은_키는_기동_거부() {
		String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));
		assertThatThrownBy(() -> TestJwt.props("k1", Map.of("k1", shortKey)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("너무 짧습니다")
				.hasMessageNotContaining(shortKey);
	}

	@Test
	void 빈_키는_기동_거부() {
		assertThatThrownBy(() -> TestJwt.props("k1", Map.of("k1", "")))
				.isInstanceOf(IllegalStateException.class);
	}

	private static String b64(String json) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}
}
