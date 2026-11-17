package com.jejari.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
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
 *   <li>메서드 보안: 관리자 세부 권한은 {@code @PreAuthorize("hasAuthority('ADMIN_REVIEWER')")}처럼 붙임
 *       (SUPERVISOR는 하위 권한을 모두 가짐, {@link com.jejari.auth.AdminRole}). 거부되면 GlobalExceptionHandler가 403으로.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

	/** 프론트엔드 화면 경로와 빌드 결과물. API(/api/**)는 여기에 없다 */
	static final String[] SPA_PAGES = { "/", "/index.html", "/assets/**", "/favicon.svg", "/admin", "/admin/**",
			"/queue/**", "/reservations", "/reservations/**", "/waitlist", "/notifications", "/me", "/login", "/signup",
			"/runs/**", "/pay/**" };

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
						// 프론트엔드 화면·정적 파일 (SpaForwardController). 화면 안의 데이터는 API가 따로 막는다
						.requestMatchers(HttpMethod.GET, SPA_PAGES).permitAll()
						// 열차 조회(TRN-01~03)는 비회원도 (기능설계서 2장). 좌석 배치도 /runs/{runId}/seats는 여전히 로그인 + 입장 토큰
						.requestMatchers(HttpMethod.GET, "/api/v1/stations", "/api/v1/runs", "/api/v1/runs/{runId}").permitAll()
						// 관리자 로그인(AUTH-05)만 토큰 없이. 나머지 관리자 API는 ADMIN, 세부 권한은 @PreAuthorize("hasAuthority('ADMIN_REVIEWER')") 등
						.requestMatchers(HttpMethod.POST, "/api/v1/admin/auth/login").permitAll()
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
