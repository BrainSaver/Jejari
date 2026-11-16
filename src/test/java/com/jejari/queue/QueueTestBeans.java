package com.jejari.queue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.jejari.queue.dev.DevMemberIdentityPort;
import com.jejari.queue.dev.DevOnSaleRunProvider;
import com.jejari.queue.dev.DevRiskScoreFallbackPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.jejari.queue.port.QueueLogSink;

/** 테스트용 대용품: 가짜 회원·운행, 로그 수집기. 시계는 팀 공용 MutableClock(WebSecurityTestConfig.ClockBean)을 씁니다. */
@TestConfiguration
public class QueueTestBeans {

	public static class RecordingSink implements QueueLogSink {
		public final List<QueueLogEvent> events = new CopyOnWriteArrayList<>();

		@Override
		public void record(QueueLogEvent e) {
			events.add(e);
		}
	}

	@Bean
	DevMemberIdentityPort devMemberIdentityPort() {
		return new DevMemberIdentityPort();
	}

	@Bean
	DevOnSaleRunProvider devOnSaleRunProvider() {
		return new DevOnSaleRunProvider();
	}

	@Bean
	DevRiskScoreFallbackPort devRiskScoreFallbackPort(StringRedisTemplate redis) {
		return new DevRiskScoreFallbackPort(redis);
	}

	@Bean
	RecordingSink recordingSink() {
		return new RecordingSink();
	}
}
