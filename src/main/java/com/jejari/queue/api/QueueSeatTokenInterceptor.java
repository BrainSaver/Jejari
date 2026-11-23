package com.jejari.queue.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.jejari.auth.AuthPrincipal;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.queue.domain.QueuePassService;
import com.jejari.queue.domain.SeatRateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;

/**
 * QUE-04 좌석 API 앞단. /api/v1/runs/{runId}/seats 로 오는 요청은 X-Queue-Token이 없거나 맞지 않으면
 * 컨트롤러가 실행되기 전에 403으로 막습니다. 로그인(JWT) 검사 뒤에 실행되므로 회원을 알고 있습니다.
 *
 * <p>POST /reservations는 운행 ID가 요청 본문에 있어 이 인터셉터로 막을 수 없습니다.
 * 예약 컨트롤러가 {@link QueuePassService#verify(long, long, String)}를 직접 호출합니다.
 */
public class QueueSeatTokenInterceptor implements HandlerInterceptor {

	public static final String HEADER = "X-Queue-Token";

	private final ObjectProvider<QueuePassService> pass;
	private final ObjectProvider<SeatRateLimiter> rate;

	public QueueSeatTokenInterceptor(ObjectProvider<QueuePassService> pass, ObjectProvider<SeatRateLimiter> rate) {
		this.pass = pass;
		this.rate = rate;
	}

	@Override
	@SuppressWarnings("unchecked")
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal me)) {
			throw new BusinessException(CommonErrorCode.AUTH_REQUIRED);
		}
		Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
		String runId = vars == null ? null : vars.get("runId");
		if (runId == null) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		long run;
		try {
			run = Long.parseLong(runId);
		}
		catch (NumberFormatException e) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		pass.getObject().verify(run, me.memberId(), request.getHeader(HEADER));
		// 토큰이 맞는 요청만 센다 (토큰 없는 요청으로 남의 한도를 쓰거나 키를 만들 수 없게). 너무 잦으면 429
		SeatRateLimiter limiter = rate.getIfAvailable();
		if (limiter != null) {
			limiter.check(SeatRateLimiter.Kind.MAP, run, me.memberId());
		}
		return true;
	}
}
