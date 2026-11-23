package com.jejari.queue.dev;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import com.jejari.common.error.CommonErrorCode;
import com.jejari.common.error.ErrorResponseWriter;

/**
 * 개발 모드(jejari.dev-mode=true)에서만 켜지는 보안 필터 체인. 운영에서는 이 설정이 통째로 빠집니다.
 *
 * <p>X-Member-Id 헤더가 있거나 /api/v1/dev/** 인 요청만 따로 받아 JWT 없이 통과시킵니다(부하 테스트·수동 확인용).
 * 그 밖의 요청은 평소처럼 JWT 체인(SecurityConfig)이 처리합니다.
 *
 * <p><b>공용 서버에서는 켜지지 않게 막습니다.</b> 개발 모드에서는 누구나 X-Member-Id 헤더 하나로 아무 회원이 될 수 있어서,
 * 서버 .env에 한 줄만 잘못 들어가도 모든 회원 계정이 열립니다. 서버의 .env에는 setup-server.sh가 채운 JEJARI_DOMAIN이 있으므로
 * 그 값이 있으면 기동을 멈춥니다 ({@link #refuseOnServer}). 부하 시험·락 비교는 모두 각자 PC에서 돕니다.
 */
@Configuration
@ConditionalOnProperty(name = "jejari.dev-mode", havingValue = "true")
public class DevSecurityConfig {

	public DevSecurityConfig(org.springframework.core.env.Environment env) {
		refuseOnServer(env.getProperty("JEJARI_DOMAIN"));
	}

	static void refuseOnServer(String domain) {
		if (domain != null && !domain.isBlank()) {
			throw new IllegalStateException("개발 모드(jejari.dev-mode=true)는 공용 서버에서 켤 수 없습니다 (JEJARI_DOMAIN="
					+ domain + "). 개발 모드에서는 X-Member-Id 헤더만으로 아무 회원이 될 수 있습니다. 서버 .env·실행 인자에서 dev-mode를 빼세요");
		}
	}

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	SecurityFilterChain devSecurityFilterChain(HttpSecurity http, ErrorResponseWriter errorWriter) throws Exception {
		http
				.securityMatcher(request -> request.getHeader(DevMemberHeaderFilter.HEADER) != null
						|| request.getRequestURI().startsWith("/api/v1/dev/"))
				.csrf(csrf -> csrf.disable())
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.logout(logout -> logout.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.addFilterBefore(new DevMemberHeaderFilter(), AuthorizationFilter.class)
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/v1/dev/**").permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint((req, res, e) -> errorWriter.write(res, CommonErrorCode.AUTH_REQUIRED)));
		return http.build();
	}
}
