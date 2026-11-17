package com.jejari.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import com.jejari.event.relay.KafkaEventPublisher;
import com.jejari.support.NoRunningApp;

/**
 * 실제 Kafka(docker compose)로 보내고 다시 읽어 봅니다: 키·헤더·값이 그대로 가는지,
 * 같은 키(reservation_id)는 같은 파티션에 보낸 순서대로 쌓이는지.
 */
@Tag("infra")
@ExtendWith(NoRunningApp.class)
class KafkaEventPublisherInfraTest {

	static final String BOOTSTRAP = System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:9092");

	@Test
	void 같은_키는_같은_파티션에_보낸_순서대로_헤더와_함께_도착한다() throws Exception {
		var pf = new DefaultKafkaProducerFactory<String, String>(Map.of(
				ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
				ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
				ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
				ProducerConfig.ACKS_CONFIG, "all",
				ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true));
		var publisher = new KafkaEventPublisher(new KafkaTemplate<>(pf), Duration.ofSeconds(10));
		String key = "kt-" + UUID.randomUUID();

		try (var consumer = new KafkaConsumer<String, String>(Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
				ConsumerConfig.GROUP_ID_CONFIG, "kt-" + UUID.randomUUID(),
				ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
				ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
				ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
			// 지금 끝 위치부터 읽도록 모든 파티션을 직접 배정
			List<TopicPartition> parts = consumer.partitionsFor(EventTopics.RESERVATION).stream()
					.map(p -> new TopicPartition(p.topic(), p.partition())).toList();
			consumer.assign(parts);
			consumer.seekToEnd(parts);
			parts.forEach(consumer::position);

			List<String> ids = new ArrayList<>();
			for (String type : List.of("SEAT_HELD", "PAYMENT_STARTED", "RESERVATION_CONFIRMED")) {
				String id = UUID.randomUUID().toString();
				ids.add(id);
				publisher.publish(EventTopics.RESERVATION, key, id, type, "{\"eventId\":\"" + id + "\"}");
			}

			List<ConsumerRecord<String, String>> got = new ArrayList<>();
			long deadline = System.currentTimeMillis() + 15_000;
			while (got.size() < 3 && System.currentTimeMillis() < deadline) {
				consumer.poll(Duration.ofMillis(500)).forEach(rec -> {
					if (key.equals(rec.key())) {
						got.add(rec);
					}
				});
			}

			assertThat(got).hasSize(3);
			assertThat(got).extracting(ConsumerRecord::partition).containsOnly(got.get(0).partition());
			assertThat(got).extracting(rec -> header(rec, EventTopics.HEADER_EVENT_ID)).containsExactlyElementsOf(ids);
			assertThat(got).extracting(rec -> header(rec, EventTopics.HEADER_EVENT_TYPE))
					.containsExactly("SEAT_HELD", "PAYMENT_STARTED", "RESERVATION_CONFIRMED");
			assertThat(got.get(0).value()).contains(ids.get(0));
		} finally {
			pf.destroy();
		}
	}

	private static String header(ConsumerRecord<String, String> rec, String name) {
		var h = rec.headers().lastHeader(name);
		return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
	}
}
