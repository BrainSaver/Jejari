package com.jejari.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

import io.lettuce.core.RedisException;

/** Redis 연결 문제는 503, 연결과 상관없는 오류는 숨기지 않고 500. 인프라 없이 돕니다. */
class RedisExceptionHandlerTest {

	private final RedisExceptionHandler handler = new RedisExceptionHandler();

	@Test
	void 연결_실패는_503() {
		var res = handler.handle(new RedisConnectionFailureException("Unable to connect"));
		assertThat(res.getStatusCode().value()).isEqualTo(503);
		assertThat(res.getBody().code()).isEqualTo("SERVICE_UNAVAILABLE");
	}

	@Test
	void 응답_지연도_503() {
		assertThat(handler.handle(new QueryTimeoutException("Command timed out")).getStatusCode().value()).isEqualTo(503);
	}

	@Test
	void 끊긴_상태에서_거절된_명령도_503() {
		// Redis를 끄면 Spring이 이 형태로 던진다 (실제 로그에서 확인한 예외)
		var e = new RedisSystemException("Redis exception",
				new RedisException("Currently not connected. Commands are rejected."));
		assertThat(handler.handle(e).getStatusCode().value()).isEqualTo(503);
	}

	@Test
	void 연결과_상관없는_Redis_오류는_500으로_남긴다() {
		var e = new RedisSystemException("Redis exception", new RedisException("ERR Error running script"));
		var res = handler.handle(e);
		assertThat(res.getStatusCode().value()).isEqualTo(500);
		assertThat(res.getBody().code()).isEqualTo("INTERNAL_ERROR");
	}
}
