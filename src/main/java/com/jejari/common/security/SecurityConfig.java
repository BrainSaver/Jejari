package com.jejari.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import com.jejari.auth.AuthErrorCode;
import com.jejari.auth.jwt.JejariBearerTokenResolver;
import com.jejari.auth.jwt.JwtAuthenticationEntryPoint;
import com.jejari.auth.jwt.JwtPrincipalConverter;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.common.error.ErrorResponseWriter;

/**
 * 보안 설정.
 *
 * <ul>
 *   <li>세션 없음(STATELESS), CSRF·폼 로그인·HTTP Basic 끔. 쿠키 세션이 아니라 Bearer 토큰으로 인증하기 때문입니다.
 *       리프레시 쿠키는 SameSite=Strict + /api/v1/auth 경로 한정이라 CSRF 대상이 되지 않습니다.</li>
 *   <li>Authorization: Bearer {액세스 토큰}을 Spring Security 리소스 서버가 검증하고
 *       {@link JwtPrincipalConverter}가 AuthPrincipal로 바꿉니다.</li>
 *   <li>401·403도 { code, message, traceId } 형식.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ErrorResponseWriter errorWriter,
			JwtAuthenticationEntryPoint entryPoint) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.logout(logout -> logout.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
						.requestMatchers("/api/v1/auth/**").permitAll()
						// 메트릭은 내부망 Prometheus만 접근하도록 배포 때 포트·네트워크로 막습니다
						.requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
						.requestMatchers("/error").permitAll()
						.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
						.anyRequest().authenticated())
				.oauth2ResourceServer(rs -> rs
						.bearerTokenResolver(new JejariBearerTokenResolver())
						.jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtPrincipalConverter()))
						.authenticationEntryPoint(entryPoint))
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(entryPoint)
						.accessDeniedHandler((req, res, e) -> errorWriter.write(res,
								req.getRequestURI().startsWith("/api/v1/admin/")
										? AuthErrorCode.ADMIN_FORBIDDEN
										: CommonErrorCode.ACCESS_DENIED)));
		return http.build();
	}
}
