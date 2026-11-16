package com.jejari.auth.jwt;

import java.time.Clock;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * HS256 JWT 서명·검증. 서명과 검증은 Nimbus(Spring Security 리소스 서버)에 맡기고 직접 파싱하지 않습니다.
 *
 * <ul>
 *   <li>키 여러 개를 kid로 구분해 등록합니다. 서명은 active-kid, 검증은 토큰 헤더의 kid로 고릅니다.</li>
 *   <li>알고리즘은 HS256만 허용합니다 ("alg: none"이나 다른 알고리즘으로 바꿔치기한 토큰 거부).</li>
 *   <li>만료 검사는 Nimbus 기본 검사를 끄고 {@link AccessTokenExpiryValidator}가 앱 시계로 합니다.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

	@Bean
	JWKSource<SecurityContext> jwtKeySource(JwtProperties props) {
		List<JWK> keys = props.secrets().entrySet().stream()
				.map(e -> (JWK) new OctetSequenceKey.Builder(JwtProperties.decode(e.getKey(), e.getValue()))
						.keyID(e.getKey())
						.algorithm(JWSAlgorithm.HS256)
						.keyUse(KeyUse.SIGNATURE)
						.build())
				.toList();
		return new ImmutableJWKSet<>(new JWKSet(keys));
	}

	@Bean
	JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwtKeySource) {
		return new NimbusJwtEncoder(jwtKeySource);
	}

	@Bean
	JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwtKeySource, JwtProperties props, Clock clock) {
		DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
		processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.HS256, jwtKeySource));
		processor.setJWTClaimsSetVerifier((claims, context) -> {
			// 클레임 검사는 아래 Spring 검증기에서 합니다 (만료를 별도 코드로 구분하려고)
		});

		NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				new JwtIssuerValidator(props.issuer()),
				new AccessTokenExpiryValidator(clock, props.clockSkew())));
		return decoder;
	}
}
