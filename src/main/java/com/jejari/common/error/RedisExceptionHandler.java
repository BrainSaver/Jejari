package com.jejari.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;

/**
 * Redis가 끊기거나 응답이 늦을 때 500이 아니라 503 {@code SERVICE_UNAVAILABLE}로 바로 알립니다.
 *
 * <p>연결과 상관없는 Redis 오류(스크립트 오류 등)는 숨기지 않고 일반 서버 오류로 남깁니다.
 * 컨트롤러 밖(인터셉터)에서 난 예외도 잡도록 GlobalExceptionHandler의 {@code Exception} 처리보다 먼저 봅니다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RedisExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(RedisExceptionHandler.class);

	@ExceptionHandler({RedisConnectionFailureException.class, QueryTimeoutException.class, RedisSystemException.class})
	public ResponseEntity<ErrorResponse> handle(Exception e) {
		ErrorCode code = isUnavailable(e) ? CommonErrorCode.SERVICE_UNAVAILABLE : CommonErrorCode.INTERNAL_ERROR;
		log.error("Redis 오류 → {}: {}", code.code(), e.toString());
		return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
	}

	/** 연결 실패·끊긴 상태에서 거절된 명령·응답 시간 초과인가 */
	static boolean isUnavailable(Throwable e) {
		if (e instanceof RedisConnectionFailureException || e instanceof QueryTimeoutException) {
			return true;
		}
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof RedisConnectionException || t instanceof RedisCommandTimeoutException) {
				return true;
			}
			if (t instanceof RedisException && t.getMessage() != null
					&& t.getMessage().contains("Currently not connected")) {
				return true;
			}
		}
		return false;
	}
}
