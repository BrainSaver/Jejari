package com.jejari.queue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.jejari.common.config.AppPolicy;
import com.jejari.common.error.RetryAfterException;
import com.jejari.queue.QueueErrorCode;
import com.jejari.support.FullStackInfraTest;

/** 좌석 속도 제한 Lua를 실제 Redis로: 버스트 → 충전 → 계속 두드리면 잠금 → 키 분리 */
@FullStackInfraTest
class SeatRateLimiterInfraTest {

	static final long RUN = 987001L;
	static final long M1 = 987101L, M2 = 987102L;

	@Autowired
	SeatRateLimiter limiter;
	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	@AfterEach
	void reset() {
		for (SeatRateLimiter.Kind k : SeatRateLimiter.Kind.values()) {
			for (long m : new long[] {M1, M2}) {
				redis.delete(SeatRateLimiter.key(k, RUN, m));
			}
		}
		redis.delete(SeatRateLimiter.key(SeatRateLimiter.Kind.MAP, RUN + 1, M1));
		for (String k : new String[] {AppPolicy.SEATMAP_RATE_BURST, AppPolicy.SEATMAP_RATE_PER_SEC, AppPolicy.SEATMAP_ABUSE_THRESHOLD,
				AppPolicy.SEATMAP_ABUSE_WINDOW_SEC, AppPolicy.SEATMAP_COOLDOWN_SEC}) {
			redis.opsForHash().delete("policy:cache", k);
		}
		sleep(1100); // AppPolicy는 값을 1초 들고 있다
	}

	static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	void policy(String key, String value) {
		redis.opsForHash().put("policy:cache", key, value);
		sleep(1100);
	}

	void hit(SeatRateLimiter.Kind kind, long run, long member, int times) {
		for (int i = 0; i < times; i++) {
			limiter.check(kind, run, member);
		}
	}

	@Test
	void 처음_10번은_그냥_지나가고_11번째부터_429다() {
		hit(SeatRateLimiter.Kind.MAP, RUN, M1, 10);
		assertThatThrownBy(() -> limiter.check(SeatRateLimiter.Kind.MAP, RUN, M1))
				.isInstanceOfSatisfying(RetryAfterException.class, e -> {
					assertThat(e.getErrorCode()).isEqualTo(QueueErrorCode.SEATMAP_RATE_LIMITED);
					assertThat(e.retryAfterSec()).isBetween(1L, 2L); // 초당 1번 충전 → 곧 다시
				});
	}

	@Test
	void 쉬면_초당_1번씩_다시_채워진다() {
		hit(SeatRateLimiter.Kind.MAP, RUN, M1, 10);
		assertThatThrownBy(() -> limiter.check(SeatRateLimiter.Kind.MAP, RUN, M1)).isInstanceOf(RetryAfterException.class);
		sleep(2100); // 약 2번어치 충전
		limiter.check(SeatRateLimiter.Kind.MAP, RUN, M1);
		limiter.check(SeatRateLimiter.Kind.MAP, RUN, M1);
		assertThatThrownBy(() -> limiter.check(SeatRateLimiter.Kind.MAP, RUN, M1)).isInstanceOf(RetryAfterException.class);
	}

	@Test
	void 천천히_누르는_사람은_절대_안_걸린다() {
		// 초당 1번 이하로 15번 → 버킷이 비지 않는다 (사람의 평범한 새로고침)
		for (int i = 0; i < 15; i++) {
			limiter.check(SeatRateLimiter.Kind.MAP, RUN, M1);
			sleep(1050);
		}
	}

	@Test
	void 한도에_붙어서_계속_두드리면_잠깐_쉬게_한다() {
		policy(AppPolicy.SEATMAP_RATE_BURST, "2");
		policy(AppPolicy.SEATMAP_ABUSE_THRESHOLD, "3");
		policy(AppPolicy.SEATMAP_COOLDOWN_SEC, "3");
		hit(SeatRateLimiter.Kind.MAP, RUN, M1, 2); // 물통을 비운다
		// 거절 3번째에 잠금
		for (int i = 0; i < 3; i++) {
			assertThatThrownBy(() -> limiter.check(SeatRateLimiter.Kind.MAP, RUN, M1)).isInstanceOf(RetryAfterException.class);
		}
		sleep(1300); // 물은 다시 찼지만 잠금 중이라 여전히 막힌다
		assertThatThrownBy(() -> limiter.check(SeatRateLimiter.Kind.MAP, RUN, M1))
				.isInstanceOfSatisfying(RetryAfterException.class, e -> assertThat(e.retryAfterSec()).isBetween(1L, 3L));
		sleep(2000); // 잠금(3초)이 끝났다
		limiter.check(SeatRateLimiter.Kind.MAP, RUN, M1);
	}

	@Test
	void 조회와_선점은_따로_센다() {
		hit(SeatRateLimiter.Kind.MAP, RUN, M1, 10);
		assertThatThrownBy(() -> limiter.check(SeatRateLimiter.Kind.MAP, RUN, M1)).isInstanceOf(RetryAfterException.class);
		limiter.check(SeatRateLimiter.Kind.HOLD, RUN, M1); // 조회가 막혀도 선점은 자기 한도
		// 선점도 똑같이 막힌다 (배치도 없이 선점만 두드리는 봇)
		hit(SeatRateLimiter.Kind.HOLD, RUN, M1, 9);
		assertThatThrownBy(() -> limiter.check(SeatRateLimiter.Kind.HOLD, RUN, M1)).isInstanceOf(RetryAfterException.class);
	}

	@Test
	void 회원마다_운행마다_따로_센다() {
		hit(SeatRateLimiter.Kind.MAP, RUN, M1, 10);
		assertThatThrownBy(() -> limiter.check(SeatRateLimiter.Kind.MAP, RUN, M1)).isInstanceOf(RetryAfterException.class);
		limiter.check(SeatRateLimiter.Kind.MAP, RUN, M2);        // 다른 회원은 영향 없음 (IP로 묶지 않는다)
		limiter.check(SeatRateLimiter.Kind.MAP, RUN + 1, M1);    // 같은 회원의 다른 운행도 영향 없음
	}

	@Test
	void 키가_영영_남지_않게_만료가_걸린다() {
		limiter.check(SeatRateLimiter.Kind.MAP, RUN, M1);
		Long ttl = redis.getExpire(SeatRateLimiter.key(SeatRateLimiter.Kind.MAP, RUN, M1), java.util.concurrent.TimeUnit.MILLISECONDS);
		assertThat(ttl).isPositive().isLessThanOrEqualTo(62_000L);
	}
}
