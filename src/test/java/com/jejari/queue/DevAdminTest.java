package com.jejari.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.queue.dev.DevAdminController;
import com.jejari.queue.domain.QueueService;
import com.jejari.queue.port.QueueLogSink.QueueLogEvent;
import org.springframework.beans.factory.annotation.Autowired;

/** 개발 모드 전용 관리 API (부하 테스트가 쓰는 초기화·위험 점수·정책 설정). 컨트롤러를 직접 부릅니다. */
class DevAdminTest extends QueueRedisSupport {

	@Autowired
	QueueService queue;

	DevAdminController admin() {
		return new DevAdminController(redis);
	}

	@Test
	void reset은_대기열과_위험_점수와_정책만_지우고_다른_키는_둔다() {
		queue.enter(1, RUN);
		redis.opsForValue().set("risk:CI:x", "50");
		redis.opsForHash().put("policy:cache", "queue.admit_per_sec", "5");
		redis.opsForValue().set("keep:me", "1");

		Map<String, Object> out = admin().reset();

		assertThat((Long) out.get("queue")).isGreaterThan(0);
		assertThat(out.get("risk")).isEqualTo(1L);
		assertThat(redis.hasKey("queue:member:1")).isFalse();
		assertThat(redis.hasKey("risk:CI:x")).isFalse();
		assertThat(redis.hasKey("policy:cache")).isFalse();
		assertThat(redis.hasKey("keep:me")).isTrue();
	}

	@Test
	void 개발용_회원에게_위험_점수를_주면_진입_때_패널티로_반영된다() {
		admin().setDevMembersRisk(900, 2, 80);
		assertThat(redis.opsForValue().get("risk:CI:dev-ci-900")).isEqualTo("80");
		assertThat(redis.opsForValue().get("risk:CI:dev-ci-901")).isEqualTo("80");

		queue.enter(900, RUN);
		QueueLogEvent e = sink.events.get(0);
		assertThat(e.riskScore()).isEqualTo(80.0);
		assertThat(e.penaltyMs()).isEqualTo(80_000);
	}

	@Test
	void 위험_점수_하나를_직접_설정한다() {
		admin().setRisk(Map.of("subjectType", "DEVICE", "hash", "dev-1", "score", 42.5));
		assertThat(redis.opsForValue().get("risk:DEVICE:dev-1")).isEqualTo("42.5");
	}

	@Test
	void 정책을_바꾸면_Redis_해시에_들어간다() {
		Map<String, Object> out = admin().setPolicy("queue.admit_per_sec", Map.of("value", "7"));
		assertThat(redis.opsForHash().get("policy:cache", "queue.admit_per_sec")).isEqualTo("7");
		assertThat(out.get("appliesWithinMs")).isEqualTo(1000);
	}

	@Test
	void 잘못된_입력은_INVALID_REQUEST이고_아무것도_바꾸지_않는다() {
		DevAdminController admin = admin();
		List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> bad = List.of(
				() -> admin.setPolicy("auth.secret", Map.of("value", "1")),
				() -> admin.setPolicy("queue.admit_per_sec", Map.of("value", "a b;c")),
				() -> admin.setRisk(Map.of("subjectType", "CI", "hash", "h", "score", 500)),
				() -> admin.setRisk(Map.of("subjectType", "ALL", "hash", "h", "score", 5)),
				() -> admin.setDevMembersRisk(1, 0, 5));
		for (var call : bad) {
			assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
					e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST));
		}
		assertThat(redis.hasKey("policy:cache")).isFalse();
		assertThat(redis.keys("risk:*")).isEmpty();
	}
}
