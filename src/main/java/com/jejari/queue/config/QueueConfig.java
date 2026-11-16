package com.jejari.queue.config;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.jejari.queue.domain.PolicyProvider;
import com.jejari.queue.domain.QueueAdmitScheduler;
import com.jejari.queue.domain.QueueAdmitter;
import com.jejari.queue.domain.QueuePassService;
import com.jejari.queue.domain.QueueService;
import com.jejari.queue.domain.RiskPenaltyService;
import com.jejari.queue.port.LoggingQueueLogSink;
import com.jejari.queue.port.MemberIdentityPort;
import com.jejari.queue.port.OnSaleRunProvider;
import com.jejari.queue.port.QueueLogSink;
import com.jejari.queue.port.UnavailableMemberIdentityPort;
import com.jejari.queue.port.UnavailableOnSaleRunProvider;

/**
 * 대기열(QUE-01~05) 빈 조립. 시계는 공통 ClockConfig의 Clock을 씁니다.
 *
 * <p>다른 파트가 채울 자리(회원 정보, 운행 정보, queue_log 적재)는 인터페이스(port)로 받고, 구현 빈이 없으면
 * 앱은 뜨되 대기열이 "준비 중"(503 또는 판매 중인 운행 없음)으로 동작합니다. 앱 전체의 기동을 막지 않기 위해서입니다.
 */
@Configuration
@EnableScheduling
public class QueueConfig {

	// ---- Lua 스크립트 (src/main/resources/lua/queue/) ----

	@Bean
	RedisScript<List> queueEnterScript() {
		return script("lua/queue/enter.lua");
	}

	@Bean
	RedisScript<List> queueAdmitScript() {
		return script("lua/queue/admit.lua");
	}

	@Bean
	RedisScript<List> queueLeaveScript() {
		return script("lua/queue/leave.lua");
	}

	@Bean
	RedisScript<List> queueStatusScript() {
		return script("lua/queue/status.lua");
	}

	private static RedisScript<List> script(String path) {
		DefaultRedisScript<List> s = new DefaultRedisScript<>();
		s.setLocation(new ClassPathResource(path));
		s.setResultType(List.class);
		return s;
	}

	// ---- 서비스 ----

	@Bean
	PolicyProvider queuePolicyProvider(StringRedisTemplate redis,
			@Value("${jejari.queue.policy-cache-ms:1000}") long cacheMs) {
		return new PolicyProvider(redis, cacheMs);
	}

	@Bean
	RiskPenaltyService queueRiskPenaltyService(StringRedisTemplate redis, PolicyProvider queuePolicyProvider) {
		return new RiskPenaltyService(redis, queuePolicyProvider);
	}

	@Bean
	QueueService queueService(StringRedisTemplate redis, RedisScript<List> queueEnterScript,
			RedisScript<List> queueLeaveScript, RedisScript<List> queueStatusScript, PolicyProvider queuePolicyProvider,
			RiskPenaltyService queueRiskPenaltyService, ObjectProvider<MemberIdentityPort> members,
			ObjectProvider<OnSaleRunProvider> runs, ObjectProvider<QueueLogSink> logSink, Clock clock) {
		return new QueueService(redis, queueEnterScript, queueLeaveScript, queueStatusScript, queuePolicyProvider,
				queueRiskPenaltyService,
				members.orderedStream().findFirst().orElseGet(UnavailableMemberIdentityPort::new),
				runs.orderedStream().findFirst().orElseGet(UnavailableOnSaleRunProvider::new),
				logSink.orderedStream().findFirst().orElseGet(LoggingQueueLogSink::new), clock);
	}

	@Bean
	QueueAdmitter queueAdmitter(StringRedisTemplate redis, RedisScript<List> queueAdmitScript,
			PolicyProvider queuePolicyProvider, ObjectProvider<QueueLogSink> logSink, Clock clock) {
		return new QueueAdmitter(redis, queueAdmitScript, queuePolicyProvider,
				logSink.orderedStream().findFirst().orElseGet(LoggingQueueLogSink::new), clock);
	}

	/** 판매 중인 운행마다 1초에 한 번 입장 처리. 테스트에서는 jejari.queue.scheduler-enabled=false로 끈다. */
	@Bean
	@ConditionalOnProperty(name = "jejari.queue.scheduler-enabled", havingValue = "true", matchIfMissing = true)
	QueueAdmitScheduler queueAdmitScheduler(ObjectProvider<OnSaleRunProvider> runs, QueueAdmitter queueAdmitter) {
		return new QueueAdmitScheduler(runs.orderedStream().findFirst().orElseGet(UnavailableOnSaleRunProvider::new),
				queueAdmitter);
	}

	@Bean
	QueuePassService queuePassService(StringRedisTemplate redis) {
		return new QueuePassService(redis);
	}
}
