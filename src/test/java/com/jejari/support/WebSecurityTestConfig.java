package com.jejari.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.auth.jwt.JwtAuthenticationEntryPoint;
import com.jejari.auth.jwt.JwtConfig;
import com.jejari.auth.web.TokenResponses;
import com.jejari.common.error.ErrorResponseWriter;
import com.jejari.common.error.GlobalExceptionHandler;
import com.jejari.common.security.SecurityConfig;
import com.jejari.common.web.TraceIdFilter;

/**
 * @WebMvcTest에 보안·JWT·공통 응답 설정을 붙입니다. 시계는 고정된 {@link MutableClock}입니다.
 * DB·Redis·Kafka 없이 돕니다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({SecurityConfig.class, ErrorResponseWriter.class, GlobalExceptionHandler.class, TraceIdFilter.class,
		JwtConfig.class, JwtAuthenticationEntryPoint.class, AccessTokenIssuer.class, TokenResponses.class,
		WebSecurityTestConfig.ClockBean.class})
@TestPropertySource(properties = {
		"jejari.jwt.active-kid=k1",
		"jejari.jwt.secrets.k1=" + TestJwt.K1,
		"jejari.jwt.refresh-cookie-secure=true"})
public @interface WebSecurityTestConfig {

	class ClockBean {
		@Bean
		MutableClock clock() {
			return new MutableClock();
		}
	}
}
