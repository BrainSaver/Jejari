package com.jejari.event.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.event.EventCleanup;
import com.jejari.event.EventPolicy;
import com.jejari.event.inbox.InboxGuard;
import com.jejari.event.inbox.InboxProcessor;
import com.jejari.event.outbox.OutboxRecorder;
import com.jejari.event.relay.EventPublisher;
import com.jejari.event.relay.KafkaEventPublisher;
import com.jejari.event.relay.OutboxRelay;
import com.jejari.event.relay.OutboxRelayScheduler;

import tools.jackson.databind.json.JsonMapper;

/**
 * 이벤트 신뢰성(EVT-01~03) 빈 조립.
 *
 * <ul>
 * <li>{@link OutboxRecorder}, {@link InboxProcessor}: 다른 기능(예약·결제·취소·알림)이 주입받아 씁니다.</li>
 * <li>릴레이·정리 배치: {@code jejari.event.relay.enabled}, {@code jejari.event.cleanup-enabled}로 켜고 끕니다.
 * 테스트나 여러 대를 띄울 때 한 대에서만 켜기 위해서입니다 (1차는 인스턴스 1대).</li>
 * </ul>
 */
@Configuration
@EnableScheduling
public class EventConfig {

	@Bean
	EventPolicy eventPolicy(JdbcTemplate jdbc, @Value("${jejari.event.policy-cache-ms:10000}") long cacheMs) {
		return new EventPolicy(jdbc, cacheMs);
	}

	@Bean
	OutboxRecorder outboxRecorder(JdbcTemplate jdbc, JsonMapper json) {
		return new OutboxRecorder(jdbc, json);
	}

	@Bean
	InboxGuard inboxGuard(JdbcTemplate jdbc) {
		return new InboxGuard(jdbc);
	}

	@Bean
	InboxProcessor inboxProcessor(InboxGuard inboxGuard, PlatformTransactionManager tm) {
		return new InboxProcessor(inboxGuard, new TransactionTemplate(tm));
	}

	@Bean
	@ConditionalOnProperty(name = "jejari.event.relay.enabled", havingValue = "true")
	EventPublisher kafkaEventPublisher(KafkaTemplate<String, String> kafka,
			@Value("${jejari.event.relay.send-timeout:10s}") Duration timeout) {
		return new KafkaEventPublisher(kafka, timeout);
	}

	@Bean
	@ConditionalOnProperty(name = "jejari.event.relay.enabled", havingValue = "true")
	OutboxRelay outboxRelay(JdbcTemplate jdbc, PlatformTransactionManager tm, EventPublisher publisher,
			EventPolicy eventPolicy, JsonMapper json) {
		return new OutboxRelay(jdbc, new TransactionTemplate(tm), publisher, eventPolicy, json);
	}

	@Bean
	@ConditionalOnProperty(name = "jejari.event.relay.enabled", havingValue = "true")
	OutboxRelayScheduler outboxRelayScheduler(OutboxRelay outboxRelay) {
		return new OutboxRelayScheduler(outboxRelay);
	}

	@Bean
	@ConditionalOnProperty(name = "jejari.event.cleanup-enabled", havingValue = "true")
	EventCleanup eventCleanup(JdbcTemplate jdbc, EventPolicy eventPolicy) {
		return new EventCleanup(jdbc, eventPolicy);
	}
}
