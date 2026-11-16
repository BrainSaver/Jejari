package com.jejari.event.relay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

import com.jejari.event.EventTopics;

/**
 * Kafka로 보내고 브로커 확인(acks=all)까지 기다립니다. 멱등 프로듀서(enable.idempotence=true)라서
 * 프로듀서 내부 재전송으로는 중복이 생기지 않습니다. 릴레이가 죽었다 살아나 다시 보내는 중복은 컨슈머의 Inbox가 거릅니다.
 */
public class KafkaEventPublisher implements EventPublisher {

	private final KafkaTemplate<String, String> kafka;
	private final Duration timeout;

	public KafkaEventPublisher(KafkaTemplate<String, String> kafka, Duration timeout) {
		this.kafka = kafka;
		this.timeout = timeout;
	}

	@Override
	public void publish(String topic, String key, String eventId, String eventType, String value) throws Exception {
		ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
		record.headers().add(EventTopics.HEADER_EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8));
		record.headers().add(EventTopics.HEADER_EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8));
		kafka.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}
}
