package com.jejari.replay.sink;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.jejari.common.config.AppPolicy;

import tools.jackson.databind.json.JsonMapper;

/**
 * 녹화 적재 컨슈머 설정. 그룹 {@code replay-sink}, 묶음 받기(최대 200 메시지), 성공한 뒤에만 커밋,
 * MongoDB 장애면 5초마다 다시 (행동 로그 적재와 같은 방식).
 */
@Configuration
@ConditionalOnProperty(name = "jejari.replay.enabled", havingValue = "true", matchIfMissing = true)
public class ReplaySinkConfig {

	public static final String CONTAINER_FACTORY = "replaySinkContainerFactory";

	@Bean
	ReplaySink replaySink(MongoTemplate mongo, JdbcTemplate jdbc, JsonMapper json, AppPolicy policy) {
		return new ReplaySink(mongo, jdbc, json, policy);
	}

	@Bean(CONTAINER_FACTORY)
	ConcurrentKafkaListenerContainerFactory<String, String> replaySinkContainerFactory(
			@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
		Map<String, Object> props = new HashMap<>();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
		props.put(ConsumerConfig.GROUP_ID_CONFIG, "replay-sink");
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);

		ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
		f.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
		f.setBatchListener(true);
		f.setConcurrency(2);
		f.getContainerProperties().setAckMode(AckMode.BATCH);
		f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
		return f;
	}
}
