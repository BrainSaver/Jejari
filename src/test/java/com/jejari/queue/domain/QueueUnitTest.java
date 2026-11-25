package com.jejari.queue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jejari.queue.QueueErrorCode;
import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Redis 없이 도는 대기열 단위 테스트: 점수 해석, 번호표·토큰 형식, 정책 기본값·캐시, 오류 코드. */
class QueueUnitTest {

	@Nested
	@DisplayName("점수 해석")
	class ScoreParsing {

		@ParameterizedTest(name = "[{index}] \"{0}\" → {1}점")
		@CsvSource({"0,0", "1,1", "12.5,12.5", "50,50", "99.999999,99.999999", "100,100", "100.0,100", "007,7", "0.000001,0.000001"})
		void 정상_형식은_그대로_읽는다(String raw, double expected) {
			assertThat(RiskPenaltyService.parse(raw)).isEqualTo(expected);
		}

		@ParameterizedTest(name = "[{index}] \"{0}\" → 100점")
		@ValueSource(strings = {"abc", "NaN", "Infinity", "-Infinity", "-1", "-0.5", "101", "100.5", "999", "1000", "1e2", "1E2",
				"0x10", "80d", "80f", "80L", " 80", "80 ", "8 0", "", " ", "1,5", "5.", ".5", "1.1234567", "٥٠", "５０", "50\n",
				"+50", "--5", "null", "true"})
		void 형식이_틀리거나_범위를_벗어나면_100점이다(String raw) {
			assertThat(RiskPenaltyService.parse(raw)).isEqualTo(100.0);
		}

		@Test
		void 아주_긴_값도_예외_없이_100점이다() {
			assertThat(RiskPenaltyService.parse("9".repeat(10_000))).isEqualTo(100.0);
		}

		@Test
		void 최댓값은_없는_값을_무시한다() {
			assertThat(RiskPenaltyService.maxOf(Arrays.asList(null, "20", null, "45.5", "3"))).isEqualTo(45.5);
		}

		@Test
		void 모두_없으면_0점이다() {
			assertThat(RiskPenaltyService.maxOf(Arrays.asList(null, null))).isZero();
			assertThat(RiskPenaltyService.maxOf(List.of())).isZero();
			assertThat(RiskPenaltyService.maxOf(null)).isZero();
		}

		@Test
		void 오염된_값_하나가_다른_정상_값보다_우선한다() {
			assertThat(RiskPenaltyService.maxOf(List.of("10", "garbage", "20"))).isEqualTo(100.0);
		}

		@Test
		void 값_순서는_결과에_영향이_없다() {
			List<String> v = new ArrayList<>(List.of("5", "80", "33.3", "79.99", "0"));
			double expected = RiskPenaltyService.maxOf(v);
			for (int i = 0; i < 20; i++) {
				java.util.Collections.shuffle(v);
				assertThat(RiskPenaltyService.maxOf(v)).isEqualTo(expected);
			}
			assertThat(expected).isEqualTo(80.0);
		}
	}

	@Nested
	@DisplayName("패널티 변환")
	class PenaltyConversion {
		private RiskPenaltyService svc(String perPoint) {
			StringRedisTemplate redis = mock(StringRedisTemplate.class);
			@SuppressWarnings("unchecked")
			HashOperations<String, Object, Object> h = mock(HashOperations.class);
			when(redis.opsForHash()).thenReturn(h);
			when(h.get(anyString(), anyString())).thenReturn(perPoint);
			return new RiskPenaltyService(redis, new PolicyProvider(redis, 0), m -> 0, java.time.Clock.systemUTC(),
					java.time.Duration.ofMinutes(30));
		}

		@ParameterizedTest(name = "{0}점 × {1}ms/점 = {2}ms")
		@CsvSource({"0,1000,0", "1,1000,1000", "80,1000,80000", "100,1000,100000", "12.5,1000,12500", "0.0005,1000,1",
				"80,10,800", "80,0,0", "100,60000,6000000", "33.3333,3,100"})
		void 점수에_단가를_곱한다(double score, String perPoint, long expected) {
			assertThat(svc(perPoint).penaltyMs(score)).isEqualTo(expected);
		}

		@Test
		void 점수가_높을수록_패널티가_단조_증가한다() {
			RiskPenaltyService s = svc("1000");
			long prev = -1;
			for (int p = 0; p <= 100; p++) {
				long ms = s.penaltyMs(p);
				assertThat(ms).isGreaterThan(prev);
				prev = ms;
			}
		}
	}

	@Nested
	@DisplayName("번호표 토큰")
	class TicketToken {

		@ParameterizedTest(name = "[{index}] {0} → {1}")
		@CsvSource({"000000000001:5,1", "000000000042:123456,42", "000000012345:9,12345", "999999999999:1,999999999999"})
		void 번호표_번호는_앞_12자리_seq다(String queueMember, long expected) {
			assertThat(QueueService.ticketNo(queueMember)).isEqualTo(expected);
		}

		@Test
		void 번호표_형식이_깨지면_예외다() {
			assertThatThrownBy(() -> QueueService.ticketNo("no-colon")).isInstanceOf(RuntimeException.class);
			assertThatThrownBy(() -> QueueService.ticketNo("abc:1")).isInstanceOf(NumberFormatException.class);
		}

		@Test
		void 토큰은_256비트_URL_안전_문자열이다() {
			String t = QueueAdmitter.newToken();
			assertThat(t).hasSize(43).matches("[A-Za-z0-9_-]{43}");
			assertThat(java.util.Base64.getUrlDecoder().decode(t)).hasSize(32);
		}

		@Test
		void 토큰_만_개는_서로_다르다() {
			Set<String> set = new HashSet<>();
			for (int i = 0; i < 10_000; i++) {
				set.add(QueueAdmitter.newToken());
			}
			assertThat(set).hasSize(10_000);
		}

		@Test
		void 토큰_문자_분포가_한쪽으로_치우치지_않는다() {
			int[] counts = new int[256];
			for (int i = 0; i < 2_000; i++) {
				for (char c : QueueAdmitter.newToken().toCharArray()) {
					counts[c]++;
				}
			}
			long distinct = Arrays.stream(counts).filter(c -> c > 0).count();
			assertThat(distinct).isEqualTo(64); // A-Z a-z 0-9 - _
		}
	}

	@Nested
	@DisplayName("정책 기본값과 캐시")
	class PolicyDefaultsAndCache {

		@SuppressWarnings("unchecked")
		private HashOperations<String, Object, Object> hash(StringRedisTemplate redis) {
			HashOperations<String, Object, Object> h = mock(HashOperations.class);
			when(redis.opsForHash()).thenReturn(h);
			return h;
		}

		@ParameterizedTest(name = "{0} 기본값 = {1}")
		@CsvSource({"queue.admit_per_sec,200", "queue.pass_ttl_sec,300", "queue.entry_ttl_sec,3600",
				"risk.penalty_ms_per_point,1000", "risk.member_penalty_rule,MAX"})
		void 정책이_없으면_ERD_예시값을_쓴다(String key, String expected) {
			StringRedisTemplate redis = mock(StringRedisTemplate.class);
			when(hash(redis).get(anyString(), anyString())).thenReturn(null);
			assertThat(new PolicyProvider(redis, 0).getString(key)).isEqualTo(expected);
		}

		@ParameterizedTest
		@ValueSource(strings = {"queue.unknown", "foo", "risk.", "QUEUE.ADMIT_PER_SEC"})
		void 모르는_키는_예외다(String key) {
			StringRedisTemplate redis = mock(StringRedisTemplate.class);
			when(hash(redis).get(anyString(), anyString())).thenReturn(null);
			assertThatThrownBy(() -> new PolicyProvider(redis, 0).getString(key)).isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void Redis_값이_기본값보다_우선한다() {
			StringRedisTemplate redis = mock(StringRedisTemplate.class);
			when(hash(redis).get("policy:cache", "queue.admit_per_sec")).thenReturn("7");
			assertThat(new PolicyProvider(redis, 0).getLong("queue.admit_per_sec")).isEqualTo(7);
		}

		@Test
		void 숫자가_아닌_값을_getLong으로_읽으면_예외다() {
			StringRedisTemplate redis = mock(StringRedisTemplate.class);
			when(hash(redis).get(anyString(), anyString())).thenReturn("many");
			assertThatThrownBy(() -> new PolicyProvider(redis, 0).getLong("queue.admit_per_sec"))
					.isInstanceOf(NumberFormatException.class);
		}

		@Test
		void 캐시가_켜져_있으면_같은_키를_한_번만_읽는다() {
			StringRedisTemplate redis = mock(StringRedisTemplate.class);
			HashOperations<String, Object, Object> h = hash(redis);
			when(h.get(anyString(), anyString())).thenReturn("5");
			PolicyProvider p = new PolicyProvider(redis, 60_000);
			for (int i = 0; i < 50; i++) {
				assertThat(p.getLong("queue.admit_per_sec")).isEqualTo(5);
			}
			verify(h, times(1)).get("policy:cache", "queue.admit_per_sec");
		}

		@Test
		void 캐시는_키마다_따로_저장한다() {
			StringRedisTemplate redis = mock(StringRedisTemplate.class);
			HashOperations<String, Object, Object> h = hash(redis);
			when(h.get("policy:cache", "queue.admit_per_sec")).thenReturn("5");
			when(h.get("policy:cache", "queue.pass_ttl_sec")).thenReturn("9");
			PolicyProvider p = new PolicyProvider(redis, 60_000);
			assertThat(p.getLong("queue.admit_per_sec")).isEqualTo(5);
			assertThat(p.getLong("queue.pass_ttl_sec")).isEqualTo(9);
			assertThat(p.getLong("queue.admit_per_sec")).isEqualTo(5);
		}

		@Test
		void 캐시를_끄면_매번_읽는다() {
			StringRedisTemplate redis = mock(StringRedisTemplate.class);
			HashOperations<String, Object, Object> h = hash(redis);
			when(h.get(anyString(), anyString())).thenReturn("5");
			PolicyProvider p = new PolicyProvider(redis, 0);
			for (int i = 0; i < 5; i++) {
				p.getLong("queue.admit_per_sec");
			}
			verify(h, times(5)).get("policy:cache", "queue.admit_per_sec");
		}
	}

	@Nested
	@DisplayName("위험 점수 합산")
	class RiskScoreSum {
		private final MemberIdentity ACTIVE = new MemberIdentity(1, "ACTIVE", "ci", List.of("p1", "p2"), List.of("d1"));

		private RiskPenaltyService svc(List<String> multiGet, com.jejari.queue.port.RiskScoreFallbackPort fb) {
			StringRedisTemplate redis = mock(StringRedisTemplate.class);
			@SuppressWarnings("unchecked")
			ValueOperations<String, String> v = mock(ValueOperations.class);
			@SuppressWarnings("unchecked")
			HashOperations<String, Object, Object> h = mock(HashOperations.class);
			when(redis.opsForValue()).thenReturn(v);
			when(redis.opsForHash()).thenReturn(h);
			when(h.get(anyString(), anyString())).thenReturn(null);
			when(v.multiGet(any())).thenReturn(multiGet);
			return new RiskPenaltyService(redis, new PolicyProvider(redis, 0), fb, java.time.Clock.systemUTC(),
					java.time.Duration.ofMinutes(30));
		}

		@Test
		void 동기화_표시가_있으면_CI_결제_기기_중_최댓값이다() {
			// 순서: CI, p1, p2, d1, synced_at
			double s = svc(Arrays.asList("10", "45", null, "30", "t"), m -> 99).score(ACTIVE);
			assertThat(s).isEqualTo(45.0);
		}

		@Test
		void RESTRICTED는_Redis를_읽지_않고_100점이다() {
			RiskPenaltyService s = svc(Arrays.asList("0", null, null, null, "t"), m -> 0);
			assertThat(s.score(new MemberIdentity(2, "RESTRICTED", "ci", List.of(), List.of()))).isEqualTo(100.0);
		}

		@Test
		void multiGet이_null이면_원본_조회로_넘어간다() {
			assertThat(svc(null, m -> 42).score(ACTIVE)).isEqualTo(42.0);
		}

		@ParameterizedTest(name = "원본 조회값 {0} → {1}점")
		@CsvSource({"0,0", "55.5,55.5", "100,100", "-0.1,100", "100.1,100", "NaN,100"})
		void 원본_조회값은_범위를_검사한다(double fromSource, double expected) {
			assertThat(svc(Arrays.asList(null, null, null, null, null), m -> fromSource).score(ACTIVE)).isEqualTo(expected);
		}

		@Test
		void 원본_조회가_어떤_예외를_던져도_100점이다() {
			assertThat(svc(Arrays.asList(null, null, null, null, null), m -> {
				throw new IllegalStateException("oracle down");
			}).score(ACTIVE)).isEqualTo(100.0);
			assertThat(svc(Arrays.asList(null, null, null, null, null), m -> {
				throw new org.springframework.dao.QueryTimeoutException("timeout");
			}).score(ACTIVE)).isEqualTo(100.0);
		}
	}

	@Nested
	@DisplayName("오류 코드")
	class ErrorCodes {

		@ParameterizedTest
		@EnumSource(QueueErrorCode.class)
		void 모든_코드는_이름과_같은_code와_메시지를_가진다(QueueErrorCode c) {
			assertThat(c.code()).isEqualTo(c.name());
			assertThat(c.message()).isNotBlank();
			assertThat(c.status().is4xxClientError()).isTrue();
		}

		@ParameterizedTest(name = "{0} → HTTP {1}")
		@CsvSource({"RUN_NOT_ON_SALE,409", "QUEUE_DUPLICATED,409", "QUEUE_TOKEN_REQUIRED,403", "QUEUE_TOKEN_INVALID,403",
				"QUEUE_NOT_FOUND,404"})
		void 기능설계서의_HTTP_상태와_같다(QueueErrorCode c, int status) {
			assertThat(c.status().value()).isEqualTo(status);
		}

		@Test
		void 코드_이름은_서로_겹치지_않는다() {
			Set<String> names = new HashSet<>();
			for (QueueErrorCode c : QueueErrorCode.values()) {
				assertThat(names.add(c.code())).isTrue();
			}
		}
	}

	@Nested
	@DisplayName("입력 방어")
	class InputGuard {

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = {" ", "\t", "\n"})
		void 비어_있는_토큰은_TOKEN_REQUIRED다(String token) {
			StringRedisTemplate redis = mock(StringRedisTemplate.class);
			assertThatThrownBy(() -> new QueuePassService(redis).verify(1, 1, token))
					.isInstanceOfSatisfying(com.jejari.common.error.BusinessException.class,
							e -> assertThat(e.getErrorCode()).isEqualTo(QueueErrorCode.QUEUE_TOKEN_REQUIRED));
		}

		@Test
		void 저장된_토큰이_없으면_TOKEN_INVALID다() {
			StringRedisTemplate redis = mock(StringRedisTemplate.class);
			@SuppressWarnings("unchecked")
			ValueOperations<String, String> v = mock(ValueOperations.class);
			when(redis.opsForValue()).thenReturn(v);
			when(v.get(anyString())).thenReturn(null);
			assertThatThrownBy(() -> new QueuePassService(redis).verify(1, 1, "x"))
					.isInstanceOfSatisfying(com.jejari.common.error.BusinessException.class,
							e -> assertThat(e.getErrorCode()).isEqualTo(QueueErrorCode.QUEUE_TOKEN_INVALID));
		}

		@ParameterizedTest
		@ValueSource(strings = {"ABC", "abc ", " abc", "abcd", "ab", "a", "abc\u0000"})
		void 한_글자라도_다르면_거절한다(String given) {
			StringRedisTemplate redis = mock(StringRedisTemplate.class);
			@SuppressWarnings("unchecked")
			ValueOperations<String, String> v = mock(ValueOperations.class);
			when(redis.opsForValue()).thenReturn(v);
			when(v.get("queue:pass:1:1")).thenReturn("abc");
			assertThatThrownBy(() -> new QueuePassService(redis).verify(1, 1, given))
					.isInstanceOfSatisfying(com.jejari.common.error.BusinessException.class,
							e -> assertThat(e.getErrorCode()).isEqualTo(QueueErrorCode.QUEUE_TOKEN_INVALID));
		}

		@Test
		void 같은_토큰이면_통과한다() {
			StringRedisTemplate redis = mock(StringRedisTemplate.class);
			@SuppressWarnings("unchecked")
			ValueOperations<String, String> v = mock(ValueOperations.class);
			when(redis.opsForValue()).thenReturn(v);
			when(v.get("queue:pass:7:9")).thenReturn("tok");
			new QueuePassService(redis).verify(7, 9, "tok");
		}
	}
}
