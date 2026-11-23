package com.jejari.replay;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * 녹화 묶음을 Kafka로 보낸다. 행동 로그({@code BehaviorPublisher})와 같은 원칙: <b>요청 스레드는 기다리지 않는다</b>.
 * 전용 스레드 1개 + 큐, 넘치면 버리고 센다 (녹화 몇 초 유실 &lt; 예매 응답 지연). 전용 프로듀서(acks=1, lz4).
 * 재전송으로 생기는 중복은 적재 쪽 {@code (session_id, seq)} 유니크 인덱스가 걸러 낸다.
 */
@Component
@ConditionalOnProperty(name = "jejari.replay.enabled", havingValue = "true", matchIfMissing = true)
public class ReplayPublisher implements DisposableBean {
	private static final Logger log = LoggerFactory.getLogger(ReplayPublisher.class);

	static final int QUEUE_CAPACITY = 10_000;
	private static final long WARN_INTERVAL_MS = 10_000;

	private final KafkaTemplate<String, String> kafka;
	private final DefaultKafkaProducerFactory<String, String> producerFactory;
	private final JsonMapper json;
	private final ThreadPoolExecutor sender;
	private final AtomicLong dropped = new AtomicLong();
	private final AtomicLong lastWarnAt = new AtomicLong();

	public ReplayPublisher(@Value("${spring.kafka.bootstrap-servers}") String bootstrap, JsonMapper json) {
		Map<String, Object> props = new HashMap<>();
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		props.put(ProducerConfig.ACKS_CONFIG, "1");
		props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
		props.put(ProducerConfig.LINGER_MS_CONFIG, 50);
		props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
		props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
		props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
		props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
		props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 2 * 1024 * 1024);
		this.producerFactory = new DefaultKafkaProducerFactory<>(props);
		this.kafka = new KafkaTemplate<>(producerFactory);
		this.json = json;
		this.sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
				r -> {
					Thread t = new Thread(r, "replay-send");
					t.setDaemon(true);
					return t;
				}, (r, ex) -> warn("녹화 보내기 큐가 가득 차 버림"));
	}

	/** 바로 돌아온다 */
	public void publish(ReplayMessage m) {
		try {
			sender.execute(() -> send(m));
		} catch (RejectedExecutionException ex) {
			warn("녹화를 버림 (종료 중)");
		}
	}

	private void send(ReplayMessage m) {
		try {
			kafka.send(ReplayMessage.TOPIC, m.sessionId(), json.writeValueAsString(m)).whenComplete((r, ex) -> {
				if (ex != null) {
					warn("녹화 Kafka 전송 실패: " + ex.getClass().getSimpleName());
				}
			});
		} catch (RuntimeException ex) {
			warn("녹화 Kafka 전송 실패: " + ex.getClass().getSimpleName());
		}
	}

	private void warn(String msg) {
		long n = dropped.incrementAndGet();
		long now = System.currentTimeMillis();
		long last = lastWarnAt.get();
		if (now - last >= WARN_INTERVAL_MS && lastWarnAt.compareAndSet(last, now)) {
			log.warn("{} (누적 {}건)", msg, n);
		}
	}

	@Override
	public void destroy() throws InterruptedException {
		sender.shutdown();
		sender.awaitTermination(5, TimeUnit.SECONDS);
		kafka.flush();
		producerFactory.destroy();
	}
}
