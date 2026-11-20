package com.jejari.event.dlq;

import java.time.Duration;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.jejari.behavior.BehaviorEvent;
import com.jejari.event.EventTopics;
import com.jejari.replay.ReplayMessage;

/**
 * 격리 보관함 토픽. 앱이 뜰 때 없으면 만든다 (Spring KafkaAdmin) — 서버에 따로 손대지 않아도 배포만으로 생기게.
 * docker/kafka/create-topics.sh에도 같은 설정이 있다. 보관 30일 (원래 토픽은 7일): 원인을 고치고 다시 넣을 시간을 넉넉히
 */
@Configuration
public class DeadLetterConfig {

	static final long RETENTION_MS = Duration.ofDays(30).toMillis();

	@Bean
	NewTopic reservationDlt() {
		return dlt(EventTopics.RESERVATION);
	}

	@Bean
	NewTopic behaviorDlt() {
		return dlt(BehaviorEvent.TOPIC);
	}

	@Bean
	NewTopic replayDlt() {
		return dlt(ReplayMessage.TOPIC);
	}

	private static NewTopic dlt(String topic) {
		return TopicBuilder.name(topic + DeadLetters.SUFFIX).partitions(1).replicas(1)
				.config("retention.ms", String.valueOf(RETENTION_MS)).build();
	}
}
