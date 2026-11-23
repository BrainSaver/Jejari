package com.jejari.common.error;

import java.sql.SQLTransientConnectionException;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * DB 연결을 얻지 못했을 때(커넥션 풀이 바닥나 {@code connection-timeout} 5초를 넘김, 또는 DB가 응답하지 않음)
 * 500이 아니라 <b>503 {@code SERVICE_UNAVAILABLE} + Retry-After</b>로 알립니다.
 *
 * <p>11-23 강다인 님 제보: 합성 사람 가입이 한꺼번에 몰리자 풀(20)이 바닥나 {@code CannotGetJdbcConnectionException} → 500.
 * 서버 코드가 틀린 게 아니라 "지금 바쁨"이므로 클라이언트가 잠시 뒤 다시 시도하면 되는 503이 맞습니다
 * (Redis 장애를 503으로 바꾸는 {@link RedisExceptionHandler}와 같은 생각).
 *
 * <p>연결과 상관없는 DB 오류(제약 위반·SQL 오류 등)는 숨기지 않고 지금처럼 500으로 남깁니다.
 * 바쁠 때는 같은 오류가 초당 수백 번 나므로 경고는 10초에 한 번만 남기고 개수는 지표로 셉니다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class DatabaseBusyExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(DatabaseBusyExceptionHandler.class);
	/** 다시 시도할 때까지 기다릴 초. 풀 연결 대기(5초)보다 짧게: 바쁨은 보통 몇 초 안에 풀린다 */
	static final int RETRY_AFTER_SEC = 2;
	private static final long WARN_INTERVAL_MS = 10_000;
	private static final AtomicLong lastWarnAt = new AtomicLong();

	@ExceptionHandler({DataAccessException.class, TransactionException.class})
	public ResponseEntity<ErrorResponse> handle(Exception e) {
		if (!isBusy(e)) {
			log.error("unexpected error", e); // GlobalExceptionHandler와 같은 기록
			return ResponseEntity.status(CommonErrorCode.INTERNAL_ERROR.status())
					.body(ErrorResponse.of(CommonErrorCode.INTERNAL_ERROR));
		}
		long now = System.currentTimeMillis();
		long last = lastWarnAt.get();
		if (now - last >= WARN_INTERVAL_MS && lastWarnAt.compareAndSet(last, now)) {
			log.warn("DB 연결을 얻지 못함(풀 바닥 또는 DB 무응답) → 503 (10초에 한 번만 기록): {}", rootMessage(e));
		}
		return ResponseEntity.status(CommonErrorCode.SERVICE_UNAVAILABLE.status())
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SEC))
				.body(ErrorResponse.of(CommonErrorCode.SERVICE_UNAVAILABLE));
	}

	/** 연결을 얻는 단계에서 실패했는가 (JdbcTemplate·트랜잭션 시작·JPA 모두) */
	static boolean isBusy(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof CannotGetJdbcConnectionException || t instanceof SQLTransientConnectionException) {
				return true;
			}
			if (t instanceof CannotCreateTransactionException && t.getCause() == null) {
				return true; // 원인이 없으면 연결을 못 얻은 것으로 본다 (트랜잭션 시작 = 연결 얻기)
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}

	private static String rootMessage(Throwable e) {
		Throwable t = e;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t.getClass().getSimpleName() + ": " + t.getMessage();
	}
}
