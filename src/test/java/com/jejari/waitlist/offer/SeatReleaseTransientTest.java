package com.jejari.waitlist.offer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisException;

/**
 * seat-release가 실패를 "잠깐의 연결 장애"(다시 시도)와 "다시 해도 같은 실패"(격리 보관)로 나누는지.
 * 2026-11-27 장애 주입: Redis 45초 정지 동안 RedisSystemException(원인 "Connection disconnected") 47건이 곧바로 격리 보관됐다.
 */
class SeatReleaseTransientTest {

	@Test
	void Redis_연결이_끊긴_것은_다시_시도한다() {
		// 11-27 실제 모양: Spring이 Lettuce 예외를 RedisSystemException으로 감쌈
		assertThat(SeatReleaseConsumer.transientInfra(
				new RedisSystemException("Redis exception", new RedisException("Connection disconnected")))).isTrue();
		assertThat(SeatReleaseConsumer.transientInfra(new RedisConnectionFailureException("refused"))).isTrue();
	}

	@Test
	void DB_커넥션을_못_얻은_것도_다시_시도한다() {
		assertThat(SeatReleaseConsumer.transientInfra(new CannotGetJdbcConnectionException("pool"))).isTrue();
	}

	@Test
	void 다시_해도_같은_실패는_격리_보관한다() {
		// Lua 스크립트 오류: 무한 재시도로 파티션을 막으면 안 된다
		assertThat(SeatReleaseConsumer.transientInfra(
				new RedisSystemException("script", new RedisCommandExecutionException("ERR Error running script")))).isFalse();
		assertThat(SeatReleaseConsumer.transientInfra(new DataIntegrityViolationException("dup"))).isFalse();
		assertThat(SeatReleaseConsumer.transientInfra(new IllegalStateException("bug"))).isFalse();
	}
}
