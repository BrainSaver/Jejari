package com.jejari.common.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 traceId를 정해 MDC(로그)와 응답 헤더 X-Trace-Id에 넣습니다.
 *
 * <p>Spring Security보다 먼저 돌아야 인증 실패 응답에도 traceId가 들어가므로 가장 높은 우선순위입니다.
 * 요청에 X-Trace-Id가 있으면 이어받습니다 (k6·게이트웨이에서 요청과 로그를 맞춰 볼 때).
 * 로그 위조를 막기 위해 영문·숫자·하이픈 8~64자만 받고, 아니면 새로 만듭니다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

	private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9-]{8,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String incoming = request.getHeader(TraceId.HEADER);
		String traceId = (incoming != null && ALLOWED.matcher(incoming).matches())
				? incoming
				: UUID.randomUUID().toString().replace("-", "");
		MDC.put(TraceId.MDC_KEY, traceId);
		response.setHeader(TraceId.HEADER, traceId);
		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove(TraceId.MDC_KEY);
		}
	}
}
