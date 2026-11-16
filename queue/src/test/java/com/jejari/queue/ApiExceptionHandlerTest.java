package com.jejari.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jejari.queue.api.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void Redis가_끊기면_500이_아니라_503과_코드를_돌려준다() {
        var res = handler.handleRedisDown(new RedisConnectionFailureException("Unable to connect"));
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().code()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(res.getBody().traceId()).isNotBlank();
    }

    @Test
    void Redis_응답이_늦어도_503이다() {
        var res = handler.handleRedisDown(new QueryTimeoutException("Command timed out"));
        assertThat(res.getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void 연결이_끊겨_명령이_거절된_경우도_503이다() {
        // Redis를 끄면 Spring이 이 형태로 던진다 (실제 로그에서 확인한 예외)
        var e = new RedisSystemException("Redis exception",
                new io.lettuce.core.RedisException("Currently not connected. Commands are rejected."));
        assertThat(handler.handleRedisDown(e).getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void 연결과_상관없는_Redis_오류는_숨기지_않고_그대로_던진다() {
        var e = new RedisSystemException("Redis exception", new io.lettuce.core.RedisException("ERR Error running script"));
        assertThatThrownBy(() -> handler.handleRedisDown(e)).isSameAs(e);
    }
}
