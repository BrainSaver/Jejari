package com.jejari.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.jejari.common.policy.PolicyBounds.Rule;
import com.jejari.common.policy.PolicyBounds.Values;

/**
 * 인증 시도 제한 임시 해제 (ERD v39, 설계 docs/강동균/설계안-인증-시도제한-임시해제.md 8절 R1~R6 중 정책 판단 부분).
 * 정책 캐시(Redis 해시)를 흉내 내고 시계를 고정해 "읽을 때 비교로 자동 복구"를 확인한다.
 */
class AuthPolicyRelaxTest {

	static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 10, 0);
	static final String K6 = "a".repeat(64);
	static final String OTHER = "b".repeat(64);

	Map<Object, Object> cache;
	AuthPolicy policy;

	@BeforeEach
	void setUp() {
		cache = new HashMap<>();
		policy = at(NOW);
	}

	@SuppressWarnings("unchecked")
	AuthPolicy at(LocalDateTime now) {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		HashOperations<String, Object, Object> hash = mock(HashOperations.class);
		when(redis.opsForHash()).thenReturn(hash);
		when(hash.get(any(), any())).thenAnswer(inv -> cache.get(inv.getArgument(1)));
		Instant instant = now.atZone(SEOUL).toInstant();
		return new AuthPolicy(redis, Clock.fixed(instant, SEOUL));
	}

	void relax(String until, String ips) {
		cache.put(AuthPolicy.RATE_RELAX_UNTIL, until);
		cache.put(AuthPolicy.RATE_RELAX_IP_HASHES, ips);
	}

	@Test
	void 해제가_없으면_평소_한도() {
		assertThat(policy.limitFor(AuthPolicy.SIGNUP_RATE_PER_HOUR, K6)).isEqualTo(10);
		assertThat(policy.limitFor(AuthPolicy.LOGIN_RATE_PER_MIN, K6)).isEqualTo(20);
		assertThat(policy.relaxActive()).isFalse();
	}

	@Test
	void R1_해제_IP는_종료_시각_전에_해제_한도() {
		relax("2026-11-25 13:00", K6);

		assertThat(policy.relaxActive()).isTrue();
		assertThat(policy.limitFor(AuthPolicy.SIGNUP_RATE_PER_HOUR, K6)).isEqualTo(2000);
		assertThat(policy.limitFor(AuthPolicy.LOGIN_RATE_PER_MIN, K6)).isEqualTo(3000);
	}

	@Test
	void R2_해제_IP가_아니면_평소_한도() {
		relax("2026-11-25 13:00", K6);

		assertThat(policy.limitFor(AuthPolicy.SIGNUP_RATE_PER_HOUR, OTHER)).isEqualTo(10);
		assertThat(policy.limitFor(AuthPolicy.SIGNUP_RATE_PER_HOUR, null)).isEqualTo(10);
	}

	@Test
	void R3_종료_시각이_지나면_아무도_안_건드려도_평소_한도() {
		relax("2026-11-25 13:00", K6);
		AuthPolicy later = at(NOW.plusHours(3)); // 13:00 정각 = 종료

		assertThat(later.relaxActive()).isFalse();
		assertThat(later.limitFor(AuthPolicy.SIGNUP_RATE_PER_HOUR, K6)).isEqualTo(10);
	}

	@Test
	void 종료_시각이_24시간을_넘게_들어가_있으면_DB를_손으로_고쳤어도_해제하지_않는다() {
		relax("2026-11-26 10:01", K6);

		assertThat(policy.relaxActive()).isFalse();
		assertThat(policy.limitFor(AuthPolicy.SIGNUP_RATE_PER_HOUR, K6)).isEqualTo(10);
	}

	@Test
	void R6_비회원_조회는_해제_대상이_아니다() {
		relax("2026-11-25 13:00", K6);

		assertThat(policy.limitFor(AuthPolicy.GUEST_LOOKUP_RATE_PER_HOUR, K6)).isEqualTo(10);
	}

	@Test
	void 해제_한도를_평소보다_낮게_넣어도_더_조이지_않는다() {
		relax("2026-11-25 13:00", K6);
		cache.put(AuthPolicy.RATE_RELAX_SIGNUP_PER_HOUR, "5");

		assertThat(policy.limitFor(AuthPolicy.SIGNUP_RATE_PER_HOUR, K6)).isEqualTo(10);
	}

	@Test
	void 형식이_틀린_값은_해제하지_않는다() {
		relax("내일 저녁", K6);
		assertThat(policy.relaxActive()).isFalse();

		AuthPolicy p = at(NOW);
		relax("2026-11-25 13:00", "not-a-hash," + K6);
		assertThat(p.relaxIpHashes()).containsExactly(K6);
	}

	@Test
	void R5_저장_검사는_24시간을_넘거나_지난_시각을_거절한다() {
		Rule until = rule(AuthPolicy.RATE_RELAX_UNTIL);

		assertThat(until.ok().test(values(AuthPolicy.RATE_RELAX_UNTIL, "OFF"))).isTrue();
		assertThat(until.ok().test(values(AuthPolicy.RATE_RELAX_UNTIL, "2026-11-25 13:00"))).isTrue();
		assertThat(until.ok().test(values(AuthPolicy.RATE_RELAX_UNTIL, "2026-11-26 10:00"))).isTrue();
		assertThat(until.ok().test(values(AuthPolicy.RATE_RELAX_UNTIL, "2026-11-26 11:00"))).isFalse();
		assertThat(until.ok().test(values(AuthPolicy.RATE_RELAX_UNTIL, "2026-11-25 09:00"))).isFalse();
		assertThat(until.ok().test(values(AuthPolicy.RATE_RELAX_UNTIL, "내일"))).isFalse();
	}

	@Test
	void 저장_검사는_IP_해시를_3개까지_형식이_맞는_것만_받는다() {
		Rule ips = rule(AuthPolicy.RATE_RELAX_IP_HASHES);

		assertThat(ips.ok().test(values(AuthPolicy.RATE_RELAX_IP_HASHES, "NONE"))).isTrue();
		assertThat(ips.ok().test(values(AuthPolicy.RATE_RELAX_IP_HASHES, K6 + "," + OTHER))).isTrue();
		assertThat(ips.ok().test(values(AuthPolicy.RATE_RELAX_IP_HASHES, "203.0.113.7"))).as("원본 IP는 거절").isFalse();
		String four = String.join(",", K6, OTHER, "c".repeat(64), "d".repeat(64));
		assertThat(ips.ok().test(values(AuthPolicy.RATE_RELAX_IP_HASHES, four))).isFalse();
	}

	@Test
	void 관리자_화면_범위는_해제_한도에도_있다() {
		assertThat(policy.bounds()).containsKeys(AuthPolicy.RATE_RELAX_SIGNUP_PER_HOUR, AuthPolicy.RATE_RELAX_LOGIN_PER_MIN);
		assertThat(policy.bounds().get(AuthPolicy.RATE_RELAX_SIGNUP_PER_HOUR).max()).isEqualTo(5000);
	}

	private Rule rule(String key) {
		return policy.rules().stream().filter(r -> r.keys().contains(key)).findFirst().orElseThrow();
	}

	private static Values values(String key, String value) {
		return new Values() {
			@Override
			public java.math.BigDecimal num(String k) {
				return null;
			}

			@Override
			public String str(String k) {
				return k.equals(key) ? value : null;
			}
		};
	}
}
