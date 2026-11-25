package com.jejari.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.jejari.queue.domain.PolicyProvider;
import com.jejari.queue.domain.RiskPenaltyService;
import com.jejari.queue.port.MemberIdentityPort;
import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;
import com.jejari.queue.port.RiskScoreFallbackPort;
import com.jejari.risk.RiskPolicy;
import com.jejari.risk.batch.RiskPolicySource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 결제 단계 차단 (11-23 보강 7번): 기준 이상이면 막고, 점수를 모르면 통과, 스위치로 끈다. Redis·DB 없이. */
class PaymentRiskGuardTest {

	static final MemberIdentity ACTIVE = new MemberIdentity(7, "ACTIVE", "ci-7", List.of("pay-1"), List.of("dev-1"));
	static final MemberIdentity RESTRICTED = new MemberIdentity(8, "RESTRICTED", "ci-8", List.of(), List.of());

	@SuppressWarnings("unchecked")
	static <T> ObjectProvider<T> provider(T value) {
		ObjectProvider<T> p = mock(ObjectProvider.class);
		when(p.getIfAvailable()).thenReturn(value);
		return p;
	}

	static class MutableClock extends Clock {
		Instant now = Instant.parse("2026-11-23T06:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneId.of("Asia/Seoul");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	MemberIdentityPort members = mock(MemberIdentityPort.class);
	RiskPenaltyService risk = mock(RiskPenaltyService.class);
	RiskPolicySource rules = mock(RiskPolicySource.class);
	SimpleMeterRegistry meters = new SimpleMeterRegistry();
	MutableClock clock = new MutableClock();

	@BeforeEach
	void setUp() {
		when(members.find(7)).thenReturn(Optional.of(ACTIVE));
		when(rules.current()).thenReturn(RiskPolicy.defaults()); // case_threshold 80
	}

	PaymentRiskGuard guard(boolean enabled) {
		return new PaymentRiskGuard(provider(members), provider(risk), rules, meters, clock, enabled);
	}

	double count(String result) {
		var c = meters.find("jejari.payment.risk.check").tag("result", result).counter();
		return c == null ? 0 : c.count();
	}

	@Nested
	@DisplayName("판정")
	class Decision {

		@Test
		void 기준_바로_아래는_통과한다() {
			when(risk.knownScore(ACTIVE)).thenReturn(OptionalDouble.of(79.99));
			var v = guard(true).check(7);
			assertThat(v.blocked()).isFalse();
			assertThat(v.threshold()).isEqualTo(80);
			assertThat(count("passed")).isEqualTo(1);
		}

		@Test
		void 기준과_같으면_막는다_케이스가_열리는_점수와_같게() {
			when(risk.knownScore(ACTIVE)).thenReturn(OptionalDouble.of(80));
			var v = guard(true).check(7);
			assertThat(v.blocked()).isTrue();
			assertThat(v.score()).isEqualTo(80);
			assertThat(count("blocked")).isEqualTo(1);
		}

		@Test
		void 관리자가_기준을_바꾸면_따라간다() {
			when(rules.current()).thenReturn(RiskPolicy.fromMap(Map.of("risk.rule.case_threshold", "60")));
			when(risk.knownScore(ACTIVE)).thenReturn(OptionalDouble.of(65));
			assertThat(guard(true).check(7).blocked()).isTrue();
		}

		@Test
		void 점수를_모르면_통과하고_unknown으로_센다() {
			when(risk.knownScore(ACTIVE)).thenReturn(OptionalDouble.empty());
			var v = guard(true).check(7);
			assertThat(v.blocked()).isFalse();
			assertThat(v.score()).isNaN();
			assertThat(count("unknown")).isEqualTo(1);
		}

		@Test
		void 회원_정보를_못_읽어도_통과한다() {
			when(members.find(7)).thenThrow(new IllegalStateException("DB down"));
			assertThat(guard(true).check(7).blocked()).isFalse();
			assertThat(count("unknown")).isEqualTo(1);
		}

		@Test
		void 회원이_없으면_통과한다_결제_트랜잭션이_따로_거절() {
			assertThat(guard(true).check(999).blocked()).isFalse();
		}

		@Test
		void 스위치를_끄면_점수를_보지도_않는다() {
			var v = guard(false).check(7);
			assertThat(v.blocked()).isFalse();
			assertThat(count("off")).isEqualTo(1);
			verify(risk, times(0)).knownScore(ACTIVE);
		}
	}

	@Nested
	@DisplayName("기준 캐시")
	class ThresholdCache {

		@Test
		void 기준은_30초_동안_다시_읽지_않는다() {
			when(risk.knownScore(ACTIVE)).thenReturn(OptionalDouble.of(10));
			PaymentRiskGuard g = guard(true);
			g.check(7);
			clock.now = clock.now.plus(Duration.ofSeconds(29));
			g.check(7);
			verify(rules, times(1)).current();
			clock.now = clock.now.plus(Duration.ofSeconds(1));
			g.check(7);
			verify(rules, times(2)).current();
		}
	}

	/** RiskPenaltyService.knownScore: 대기열 score()와 같은 규칙, 다만 못 읽으면 빈 값 */
	@Nested
	@DisplayName("점수 읽기")
	class ScoreRead {

		@SuppressWarnings("unchecked")
		ValueOperations<String, String> ops = mock(ValueOperations.class);
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		RiskScoreFallbackPort fallback = mock(RiskScoreFallbackPort.class);
		RiskPenaltyService svc;

		@BeforeEach
		void setUp() {
			when(redis.opsForValue()).thenReturn(ops);
			svc = new RiskPenaltyService(redis, mock(PolicyProvider.class), fallback, clock, Duration.ofMinutes(30));
		}

		@Test
		void 캐시가_있으면_CI_결제수단_기기_중_최댓값() {
			when(ops.multiGet(anyCollection())).thenReturn(Arrays.asList("20", "85", null, "2026-11-23T05:59:00Z"));
			assertThat(svc.knownScore(ACTIVE)).hasValue(85);
		}

		@Test
		void 이용_제한_계정은_100점() {
			assertThat(svc.knownScore(RESTRICTED)).hasValue(100);
		}

		@Test
		void Redis가_죽으면_빈_값_대기열처럼_100점이_아님() {
			when(ops.multiGet(anyCollection())).thenThrow(new RedisConnectionFailureException("down"));
			assertThat(svc.knownScore(ACTIVE)).isEmpty();
		}

		@Test
		void 동기화_표시가_없으면_Oracle에서_읽는다() {
			when(ops.multiGet(anyCollection())).thenReturn(Arrays.asList(null, null, null, null));
			when(fallback.maxScore(ACTIVE)).thenReturn(42.0);
			assertThat(svc.knownScore(ACTIVE)).hasValue(42);
		}

		@Test
		void 표시도_없고_Oracle도_실패하면_빈_값() {
			when(ops.multiGet(anyCollection())).thenReturn(Arrays.asList(null, null, null, null));
			when(fallback.maxScore(ACTIVE)).thenThrow(new IllegalStateException("oracle down"));
			assertThat(svc.knownScore(ACTIVE)).isEmpty();
		}
	}
}
