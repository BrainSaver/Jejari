package com.jejari.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import com.jejari.common.error.BusinessException;
import com.jejari.queue.config.QueueConfig;
import com.jejari.queue.dev.DevStubsConfig;
import com.jejari.queue.domain.QueueService;
import com.jejari.queue.domain.QueueStatus;
import com.jejari.support.WebSecurityTestConfig;

/**
 * 실제 앱과 같은 방식의 빈 연결을 확인합니다: 개발 모드 설정(jejari.queue.dev-on-sale-runs)이 읽히고,
 * 입장 스케줄러가 1초마다 자동으로 입장시키는지. 슬라이스 테스트는 이 부분을 거치지 않습니다.
 */
@Tag("infra")
@DataRedisTest
@Import({QueueConfig.class, DevStubsConfig.class, WebSecurityTestConfig.ClockBean.class})
@TestPropertySource(properties = {
		"jejari.dev-mode=true",
		"jejari.queue.dev-on-sale-runs=1001",
		"jejari.queue.scheduler-enabled=true",
		"jejari.queue.policy-cache-ms=0",
		"spring.data.redis.database=14"})
class QueueWiringInfraTest {

	@Autowired
	QueueService queue;
	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	void flush() {
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
	}

	@Test
	void 개발_모드_설정의_운행만_판매_중이다() {
		assertThat(queue.enter(1, 1001).state()).isEqualTo(QueueStatus.State.WAITING);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> queue.enter(2, 7777))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(QueueErrorCode.RUN_NOT_ON_SALE));
	}

	@Test
	void 입장_스케줄러가_알아서_입장시킨다() throws Exception {
		queue.enter(10, 1001);
		QueueStatus s = queue.status(10, 1001);
		long deadline = System.currentTimeMillis() + 5_000;
		while (s.state() == QueueStatus.State.WAITING && System.currentTimeMillis() < deadline) {
			Thread.sleep(200);
			s = queue.status(10, 1001);
		}
		assertThat(s.state()).isEqualTo(QueueStatus.State.ADMITTED);
		assertThat(s.passToken()).isNotBlank();
	}
}
