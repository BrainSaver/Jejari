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
 */
@Configuration
@ConditionalOnProperty(name = "jejari.dev-mode", havingValue = "true")
public class DevSecurityConfig {

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
