package com.jejari.queue.api;

import com.jejari.queue.domain.ErrorCode;
import com.jejari.queue.domain.QueueException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(QueueException.class)
    public ResponseEntity<ErrorResponse> handle(QueueException e) {
        return ResponseEntity.status(e.code().status()).body(ErrorResponse.of(e.code()));
    }

    /**
     * Redis 연결 끊김·응답 지연: 500이 아니라 503으로 바로 알린다 (클라이언트가 재시도 간격을 늘릴 수 있게).
     * 연결 문제가 아닌 Redis 오류(스크립트 오류 등)는 숨기지 않고 그대로 서버 오류로 남긴다.
     */
    @ExceptionHandler({DataAccessResourceFailureException.class, QueryTimeoutException.class, RedisSystemException.class})
    public ResponseEntity<ErrorResponse> handleRedisDown(DataAccessException e) {
        if (!isRedisUnavailable(e)) {
            throw e;
        }
        log.error("Redis 사용 불가: {}", e.toString());
        return ResponseEntity.status(ErrorCode.SERVICE_UNAVAILABLE.status())
                .body(ErrorResponse.of(ErrorCode.SERVICE_UNAVAILABLE));
    }

    /** 연결 실패·연결 끊김 상태에서 거절된 명령·응답 시간 초과인가 */
    public static boolean isRedisUnavailable(DataAccessException e) {
        if (e instanceof DataAccessResourceFailureException || e instanceof QueryTimeoutException) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RedisConnectionException || t instanceof RedisCommandTimeoutException) {
                return true;
            }
            if (t instanceof io.lettuce.core.RedisException && t.getMessage() != null
                    && t.getMessage().contains("Currently not connected")) {
                return true;
            }
        }
        return false;
    }
}
