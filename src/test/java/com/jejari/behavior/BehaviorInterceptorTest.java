package com.jejari.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.jejari.auth.ActorType;
import com.jejari.auth.AuthPrincipal;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.member.web.ClientIpResolver;

import tools.jackson.databind.json.JsonMapper;

/** 요청 → mark → 요청 끝에 이벤트 1건. Kafka 없이 보내는 쪽만 가로채 확인한다. */
class BehaviorInterceptorTest {

	static final String SESSION = "6f1c2d3e-4b5a-4c6d-8e7f-0123456789ab";
	final List<BehaviorEvent> sent = new ArrayList<>();
	final Pseudonymizer hash = pseudonymizer();
	final Clock clock = Clock.fixed(Instant.parse("2026-11-20T01:00:00.123Z"), ZoneId.of("Asia/Seoul"));

	static Pseudonymizer pseudonymizer() {
		byte[] k = new byte[32];
		new SecureRandom().nextBytes(k);
		return Pseudonymizer.fromBase64(Base64.getEncoder().encodeToString(k));
	}

	BehaviorInterceptor interceptor(boolean withPublisher) {
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		if (withPublisher) {
			// 실제 Kafka에 붙지 않는 주소. publish만 가로챈다
			beans.addBean("pub", new BehaviorPublisher("localhost:1", JsonMapper.builder().build()) {
				@Override
				public void publish(BehaviorEvent e) {
					sent.add(e);
				}
			});
		}
		beans.addBean("hash", hash);
		return new BehaviorInterceptor(beans.getBeanProvider(BehaviorPublisher.class),
				beans.getBeanProvider(Pseudonymizer.class), new ClientIpResolver(""), clock);
	}

	@AfterEach
	void clear() {
		RequestContextHolder.resetRequestAttributes();
		SecurityContextHolder.clearContext();
	}

	MockHttpServletRequest request() {
		MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/runs/9001/seats/hold");
		req.setRemoteAddr("203.0.113.5");
		req.addHeader("User-Agent", "Mozilla/5.0\r\nX-Evil: 1");
		req.addHeader("X-Device-Id", "dev-1234567890");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
		return req;
	}

	@Test
	void 실패한_요청도_응답코드와_함께_한_건_보낸다() throws Exception {
		BehaviorInterceptor i = interceptor(true);
		MockHttpServletRequest req = request();
		SecurityContextHolder.getContext().setAuthentication(
				new TestingAuthenticationToken(new AuthPrincipal(ActorType.MEMBER, 1001L, SESSION), null));
		MockHttpServletResponse res = new MockHttpServletResponse();

		i.preHandle(req, res, null);
		BehaviorLog.mark(BehaviorType.SEAT_HOLD, 9001L, Map.of("seatIds", List.of(12, 13), "name", "홍길동"));
		res.setStatus(409);
		i.afterCompletion(req, res, null, null);

		assertThat(sent).hasSize(1);
		BehaviorEvent e = sent.get(0);
		assertThat(e.eventType()).isEqualTo("SEAT_HOLD");
		assertThat(e.responseStatus()).isEqualTo(409);
		assertThat(e.memberId()).isEqualTo(1001L);
		assertThat(e.sessionId()).isEqualTo(SESSION);
		assertThat(e.runId()).isEqualTo(9001L);
		assertThat(e.ts()).isEqualTo("2026-11-20T10:00:00.123+09:00");
		assertThat(e.ipHash()).isEqualTo(hash.ip("203.0.113.5")).doesNotContain("203.0.113.5");
		assertThat(e.deviceHash()).isEqualTo(hash.device("dev-1234567890"));
		assertThat(e.userAgent()).doesNotContain("\r", "\n");
		assertThat(e.meta()).containsKey("seatIds").doesNotContainKey("name");
		assertThat(e.key()).isEqualTo("m:1001");
	}

	@Test
	void 표시하지_않은_요청은_보내지_않는다() throws Exception {
		BehaviorInterceptor i = interceptor(true);
		MockHttpServletRequest req = request();
		i.preHandle(req, new MockHttpServletResponse(), null);
		i.afterCompletion(req, new MockHttpServletResponse(), null, null);
		assertThat(sent).isEmpty();
	}

	@Test
	void 비로그인_요청은_회원_없이_기기_해시로_키를_잡는다() throws Exception {
		BehaviorInterceptor i = interceptor(true);
		MockHttpServletRequest req = request();
		i.preHandle(req, new MockHttpServletResponse(), null);
		BehaviorLog.mark(BehaviorType.VIEW_TRAIN, null);
		i.afterCompletion(req, new MockHttpServletResponse(), null, null);
		assertThat(sent.get(0).memberId()).isNull();
		assertThat(sent.get(0).key()).startsWith("d:");
	}

	@Test
	void 이상한_기기_ID_헤더는_버린다() throws Exception {
		BehaviorInterceptor i = interceptor(true);
		MockHttpServletRequest req = new MockHttpServletRequest();
		req.addHeader("X-Device-Id", "<script>alert(1)</script>");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
		i.preHandle(req, new MockHttpServletResponse(), null);
		BehaviorLog.mark(BehaviorType.VIEW_TRAIN, null);
		i.afterCompletion(req, new MockHttpServletResponse(), null, null);
		assertThat(sent.get(0).deviceHash()).isNull();
	}

	@Test
	void 행동_로그가_꺼져_있으면_아무것도_안_한다() throws Exception {
		BehaviorInterceptor i = interceptor(false);
		MockHttpServletRequest req = request();
		i.preHandle(req, new MockHttpServletResponse(), null);
		BehaviorLog.mark(BehaviorType.VIEW_TRAIN, null);
		i.afterCompletion(req, new MockHttpServletResponse(), null, null);
		assertThat(sent).isEmpty();
	}

	@Test
	void 웹_요청_밖에서_mark는_무시() {
		BehaviorLog.mark(BehaviorType.PAY, 1L, Map.of());
	}

	@Test
	void Kafka가_없어도_publish는_바로_돌아온다() throws Exception {
		BehaviorPublisher real = new BehaviorPublisher("localhost:1", JsonMapper.builder().build());
		try {
			BehaviorEvent e = new BehaviorEvent(java.util.UUID.randomUUID().toString(), "VIEW_TRAIN",
					"2026-11-20T10:00:00+09:00", null, null, null, "a".repeat(64), null, "WEB", null, 200, Map.of());
			long t = System.nanoTime();
			for (int n = 0; n < 1000; n++) {
				real.publish(e);
			}
			assertThat((System.nanoTime() - t) / 1_000_000).as("1000건 ms").isLessThan(500);
			// 메타데이터 대기 2초 뒤 실패 → 처음 몇 번은 큐 뒤에 다시 넣어 재시도(retried), 그래도 안 되면 실패(failed)로 집계
			for (int w = 0; w < 50 && real.failedCount() + real.retriedCount() == 0; w++) {
				Thread.sleep(100);
			}
			assertThat(real.failedCount() + real.retriedCount()).isPositive();
		} finally {
			Thread t = new Thread(() -> {
				try {
					real.destroy();
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
			});
			t.setDaemon(true);
			t.start(); // 남은 큐를 비우느라 오래 걸릴 수 있어 테스트를 붙잡지 않는다
		}
	}
}
