package com.jejari.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import com.jejari.common.error.CommonErrorCode;
import com.jejari.common.error.ErrorResponseWriter;

/**
 * 임시 보안 설정 (JWT 인증 전까지).
 *
 * <ul>
 *   <li>세션 없음(STATELESS), CSRF·폼 로그인·HTTP Basic 끔. 쿠키 세션을 쓰지 않는 토큰 API라서입니다.</li>
 *   <li>/api/v1/auth/**, 헬스·메트릭은 열고 나머지는 인증 필요. 지금은 인증 수단이 없어 401이 납니다.</li>
 *   <li>401·403 응답도 { code, message, traceId } 형식.</li>
 * </ul>
 * JWT 필터는 이 체인에 addFilterBefore로 붙입니다 (1주차, AUTH-03~05).
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ErrorResponseWriter errorWriter) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.logout(logout -> logout.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/v1/auth/**").permitAll()
						// 메트릭은 내부망 Prometheus만 접근하도록 배포 때 포트·네트워크로 막습니다
						.requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
						.requestMatchers("/error").permitAll()
						.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
						.anyRequest().authenticated())
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint((req, res, e) -> errorWriter.write(res, CommonErrorCode.AUTH_REQUIRED))
						.accessDeniedHandler((req, res, e) -> errorWriter.write(res, CommonErrorCode.ACCESS_DENIED)));
		return http.build();
	}
}
