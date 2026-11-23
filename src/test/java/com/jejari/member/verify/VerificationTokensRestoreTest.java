package com.jejari.member.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.jejari.common.error.BusinessException;
import com.jejari.common.pii.PiiCipher;
import com.jejari.member.AuthPolicy;
import com.jejari.member.verify.MockIdentityProvider.Provider;

/**
 * 가입 저장이 실패했을 때 지운 본인인증 토큰을 되돌리기 (11-23: DB 바쁨 503 뒤 다시 시도하면 "본인인증 실패"가 나던 것).
 * Redis는 Map으로 흉내 낸다. 인프라 없이 돕니다.
 */
class VerificationTokensRestoreTest {

	Map<String, String> store = new HashMap<>();
	Instant now = Instant.parse("2026-11-23T07:00:00Z");
	VerificationTokens tokens;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		ValueOperations<String, String> ops = mock(ValueOperations.class);
		when(ops.get(anyString())).thenAnswer(a -> store.get(a.<String>getArgument(0)));
		when(ops.getAndDelete(anyString())).thenAnswer(a -> store.remove(a.<String>getArgument(0)));
		org.mockito.Mockito.doAnswer(a -> store.put(a.getArgument(0), a.getArgument(1)))
				.when(ops).set(anyString(), anyString(), any(Duration.class));
		when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
				.thenAnswer(a -> store.putIfAbsent(a.getArgument(0), a.getArgument(1)) == null);
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.opsForValue()).thenReturn(ops);
		AuthPolicy policy = mock(AuthPolicy.class);
		when(policy.number(AuthPolicy.VERIFY_TOKEN_TTL_SEC)).thenReturn(600L);
		byte[] key = new byte[32];
		for (int i = 0; i < key.length; i++) {
			key[i] = (byte) (i * 31 + 7); // 시험용 가짜 키 (모든 바이트가 같으면 PiiCipher가 거절)
		}
		Clock clock = new Clock() {
			@Override public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
			@Override public Clock withZone(ZoneId z) { return this; }
			@Override public Instant instant() { return now; }
		};
		tokens = new VerificationTokens(redis, new PiiCipher(Map.of("k1", key), "k1"), policy, clock);
	}

	VerifiedIdentity id(Instant verifiedAt) {
		return new VerifiedIdentity("ci-hash-1", "테스트가나다", "01012345671", Provider.MOCK_PASS, verifiedAt);
	}

	@Test
	void 쓴_토큰을_되돌리면_같은_토큰으로_다시_가입할_수_있다() {
		String t = tokens.issue(id(now)).token();
		VerifiedIdentity used = tokens.consume(t);
		assertThatThrownBy(() -> tokens.peek(t)).isInstanceOf(BusinessException.class); // 지워짐

		assertThat(tokens.restore(t, used)).isTrue();
		VerifiedIdentity again = tokens.consume(t);
		assertThat(again).isEqualTo(used); // 이름·전화·CI·인증 시각 그대로
	}

	@Test
	void 유효_시간이_늘어나지_않는다_원래_인증_시각_기준() {
		String t = tokens.issue(id(now)).token();
		VerifiedIdentity used = tokens.consume(t);
		now = now.plusSeconds(600); // 10분이 지난 뒤 실패
		assertThat(tokens.restore(t, used)).isFalse();
		assertThatThrownBy(() -> tokens.peek(t)).isInstanceOf(BusinessException.class);
	}

	@Test
	void 이미_같은_토큰이_있으면_덮어쓰지_않는다() {
		String t = tokens.issue(id(now)).token();
		VerifiedIdentity used = tokens.peek(t);
		assertThat(tokens.restore(t, used)).isFalse();
	}
}
