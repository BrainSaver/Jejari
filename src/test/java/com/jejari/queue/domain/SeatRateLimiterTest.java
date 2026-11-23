package com.jejari.queue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.jejari.common.config.AppPolicy;
import com.jejari.common.error.RetryAfterException;
import com.jejari.queue.QueueErrorCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 좌석 속도 제한의 응답 해석과 "Redis가 안 돼도 예매를 막지 않는다" (인프라 없이) */
class SeatRateLimiterTest {

	StringRedisTemplate redis;
	AppPolicy policy;
	SeatRateLimiter limiter;

	@BeforeEach
	void setUp() {
		redis = mock(StringRedisTemplate.class);
		policy = mock(AppPolicy.class);
		doReturn(10L).when(policy).getLong(AppPolicy.SEATMAP_RATE_BURST);
		doReturn(1L).when(policy).getLong(AppPolicy.SEATMAP_RATE_PER_SEC);
		doReturn(20L).when(policy).getLong(AppPolicy.SEATMAP_ABUSE_THRESHOLD);
		doReturn(60L).when(policy).getLong(AppPolicy.SEATMAP_ABUSE_WINDOW_SEC);
		doReturn(60L).when(policy).getLong(AppPolicy.SEATMAP_COOLDOWN_SEC);
		limiter = new SeatRateLimiter(redis, policy, Clock.fixed(Instant.parse("2026-11-23T00:00:00Z"), ZoneOffset.UTC),
				new SimpleMeterRegistry());
	}

	@SuppressWarnings("unchecked")
	private void scriptReturns(List<Long> r) {
		doReturn(r).when(redis).execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void 허용이면_그냥_지나간다() {
		scriptReturns(List.of(1L, 0L, 0L));
		assertThatCode(() -> limiter.check(SeatRateLimiter.Kind.MAP, 9001, 1)).doesNotThrowAnyException();
	}

	@Test
	void 한도를_넘으면_429와_다시_할_초를_올림해서_알려_준다() {
		scriptReturns(List.of(0L, 2500L, 0L)); // 2.5초 뒤 다시 → 3초
		assertThatThrownBy(() -> limiter.check(SeatRateLimiter.Kind.MAP, 9001, 1))
				.isInstanceOfSatisfying(RetryAfterException.class, e -> {
					assertThat(e.retryAfterSec()).isEqualTo(3);
					assertThat(e.getErrorCode()).isEqualTo(QueueErrorCode.SEATMAP_RATE_LIMITED);
				});
	}

	@Test
	void 잠금_중이면_잠금_시간만큼을_알려_준다() {
		scriptReturns(List.of(0L, 60_000L, 1L));
		assertThatThrownBy(() -> limiter.check(SeatRateLimiter.Kind.HOLD, 9001, 1))
				.isInstanceOfSatisfying(RetryAfterException.class, e -> assertThat(e.retryAfterSec()).isEqualTo(60));
	}

	@SuppressWarnings("unchecked")
	@Test
	void Redis가_안_돼도_예매를_막지_않고_통과시킨다() {
		doThrow(new RuntimeException("Redis 끊김")).when(redis).execute(any(RedisScript.class), anyList(), any(), any(), any(),
				any(), any(), any());
		assertThatCode(() -> limiter.check(SeatRateLimiter.Kind.MAP, 9001, 1)).doesNotThrowAnyException();
		assertThatCode(() -> limiter.check(SeatRateLimiter.Kind.HOLD, 9001, 1)).doesNotThrowAnyException();
	}

	@Test
	void 정책_값을_못_읽어도_통과시킨다() {
		doThrow(new IllegalStateException("정책 읽기 실패")).when(policy).getLong(AppPolicy.SEATMAP_RATE_BURST);
		assertThatCode(() -> limiter.check(SeatRateLimiter.Kind.MAP, 9001, 1)).doesNotThrowAnyException();
	}

	@Test
	void 키는_종류_운행_회원마다_다르다() {
		assertThat(SeatRateLimiter.key(SeatRateLimiter.Kind.MAP, 9001, 5)).isEqualTo("rate:seatmap:9001:5");
		assertThat(SeatRateLimiter.key(SeatRateLimiter.Kind.HOLD, 9001, 5)).isEqualTo("rate:seathold:9001:5");
		assertThat(SeatRateLimiter.key(SeatRateLimiter.Kind.MAP, 9002, 5)).isNotEqualTo(SeatRateLimiter.key(SeatRateLimiter.Kind.MAP, 9001, 5));
	}
}
