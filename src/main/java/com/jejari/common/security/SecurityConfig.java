package com.jejari.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.http.HttpServletRequest;

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

	/**
	 * 화면 쪽 GET·HEAD인가: API({@code /api})와 운영 엔드포인트({@code /actuator})가 아니면 모두 화면·정적 파일이다.
	 * 데이터는 전부 {@code /api} 아래라 여기를 열어도 새는 것이 없다. 목록으로 열면 화면을 추가할 때 빠뜨려
	 * 새로고침·없는 주소에서 "로그인이 필요합니다" JSON이 보였다 (2026-11-25, {@code /guest}·{@code /favicon.ico}).
	 * 실제로 없는 경로는 MVC가 404로 답한다 ({@link com.jejari.common.error.GlobalExceptionHandler}).
	 */
	static boolean isPageRequest(HttpServletRequest req) {
		String method = req.getMethod();
		if (!"GET".equals(method) && !"HEAD".equals(method)) {
			return false;
		}
		String path = req.getRequestURI().substring(req.getContextPath().length());
		return !(path.equals("/api") || path.startsWith("/api/") || path.equals("/actuator") || path.startsWith("/actuator/"));
	}

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
						// HEAD도 GET과 같게 연다: 외부 상태 점검·링크 미리보기가 HEAD로 확인하는데 401이 오면 사이트가 죽은 것으로 보인다
						.requestMatchers(SecurityConfig::isPageRequest).permitAll()
						// 열차 조회(TRN-01~03)는 비회원도 (기능설계서 2장). 좌석 배치도 /runs/{runId}/seats는 여전히 로그인 + 입장 토큰
						.requestMatchers(HttpMethod.GET, "/api/v1/stations", "/api/v1/runs", "/api/v1/runs/{runId}").permitAll()
						// 예매 안내 챗봇: 비회원도. Groq 호출량은 ChatController가 IP별로 제한
						.requestMatchers(HttpMethod.GET, "/api/v1/chat/status").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/chat").permitAll()
						// 관리자 로그인(AUTH-05)만 토큰 없이. 나머지 관리자 API는 ADMIN, 세부 권한은 @PreAuthorize("hasAuthority('ADMIN_REVIEWER')") 등
						.requestMatchers(HttpMethod.POST, "/api/v1/admin/auth/login", "/api/v1/admin/auth/refresh").permitAll()
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
