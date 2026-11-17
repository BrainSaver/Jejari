package com.jejari.behavior.sink;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import com.jejari.risk.batch.RiskDirtyQueue;
import org.springframework.util.backoff.FixedBackOff;

import tools.jackson.databind.json.JsonMapper;

/**
 * 행동 로그 적재 컨슈머 설정. 컨슈머 그룹 {@code behavior-sink}.
 *
 * <ul>
 *   <li>묶음으로 받기 (최대 {@code behavior.batch_size}건, 기동 시 읽음)</li>
 *   <li>자동 커밋 끔 → 리스너가 성공한 뒤에만 커밋 (AckMode.BATCH)</li>
 *   <li>실패하면(MongoDB 장애) 5초마다 무한 재시도. 형식 오류 메시지는 리스너가 걸러 내므로 여기서 막히지 않는다</li>
 *   <li>스레드 3개 (토픽 파티션 12개)</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "jejari.behavior.enabled", havingValue = "true", matchIfMissing = true)
public class BehaviorSinkConfig {

	public static final String CONTAINER_FACTORY = "behaviorSinkContainerFactory";
	static final String GROUP_ID = "behavior-sink";

	@Bean
	BehaviorPolicy behaviorPolicy(StringRedisTemplate redis) {
		return new BehaviorPolicy(redis);
	}

	@Bean
	BehaviorSink behaviorSink(MongoTemplate mongo, JdbcTemplate jdbc, JsonMapper json, BehaviorPolicy policy,
			RiskDirtyQueue riskDirty) {
		return new BehaviorSink(mongo, jdbc, json, policy, riskDirty);
	}

	@Bean(CONTAINER_FACTORY)
	ConcurrentKafkaListenerContainerFactory<String, String> behaviorSinkContainerFactory(
			@Value("${spring.kafka.bootstrap-servers}") String bootstrap, BehaviorPolicy policy) {
		Map<String, Object> props = new HashMap<>();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
		props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, policy.batchSize());

		ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
		f.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
		f.setBatchListener(true);
		f.setConcurrency(3);
		f.getContainerProperties().setAckMode(AckMode.BATCH);
		f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
		return f;
	}
}
