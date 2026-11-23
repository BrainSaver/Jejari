package com.jejari.member.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.jejari.auth.AuthErrorCode;
import com.jejari.common.error.RetryAfterException;
import com.jejari.member.AuthPolicy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class LoginGateTest {

	LoginGate gate;

	LoginGate gate(int threads, int capacity, long maxWaitMs) {
		AuthPolicy policy = mock(AuthPolicy.class);
		when(policy.number(AuthPolicy.MAX_CONCURRENT_HASH)).thenReturn((long) threads);
		return gate = new LoginGate(policy, new SimpleMeterRegistry(), capacity, maxWaitMs);
	}

	@AfterEach
	void stop() {
		gate.destroy();
	}

	@Test
	void 일꾼이_처리하고_요청_스레드는_바로_돌아온다() throws Exception {
		LoginGate g = gate(2, 10, 5000);
		CountDownLatch release = new CountDownLatch(1);
		var f = g.submit(() -> {
			await(release);
			return "ok";
		});
		assertThat(f).isNotDone();          // 요청 스레드는 기다리지 않음
		release.countDown();
		assertThat(f.get(2, TimeUnit.SECONDS)).isEqualTo("ok");
	}

	@Test
	void 대기실이_가득_차면_바로_429와_Retry_After() {
		LoginGate g = gate(1, 2, 5000);
		CountDownLatch release = new CountDownLatch(1);
		g.submit(() -> await(release));     // 일꾼 1명 사용 중
		g.submit(() -> "a");
		g.submit(() -> "b");                // 줄 2칸 가득
		assertThatThrownBy(() -> g.submit(() -> "c")).isInstanceOfSatisfying(RetryAfterException.class, e -> {
			assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_LOGIN_BUSY);
			assertThat(e.retryAfterSec()).isBetween(1L, LoginGate.MAX_RETRY_AFTER_SEC);
		});
		release.countDown();
	}

	@Test
	void 줄에서_너무_오래_기다린_요청은_계산하지_않고_돌려보낸다() throws Exception {
		LoginGate g = gate(1, 10, 50);
		CountDownLatch release = new CountDownLatch(1);
		g.submit(() -> await(release));
		boolean[] ran = { false };
		var late = g.submit(() -> ran[0] = true);
		Thread.sleep(120);                  // 최대 대기 50ms를 넘김
		release.countDown();
		assertThatThrownBy(() -> late.get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(RetryAfterException.class);
		assertThat(ran[0]).isFalse();       // 비싼 해시를 계산하지 않음
	}

	@Test
	void traceId가_일꾼_스레드로_넘어간다() throws Exception {
		LoginGate g = gate(1, 10, 5000);
		MDC.put("traceId", "t-123");
		try {
			assertThat(g.submit(() -> MDC.get("traceId")).get(2, TimeUnit.SECONDS)).isEqualTo("t-123");
		} finally {
			MDC.clear();
		}
	}

	@Test
	void 처리_중_실패는_그대로_전달() {
		LoginGate g = gate(1, 10, 5000);
		var f = g.submit(() -> {
			throw new IllegalStateException("x");
		});
		assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
	}

	private static String await(CountDownLatch l) {
		try {
			l.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return "done";
	}
}
