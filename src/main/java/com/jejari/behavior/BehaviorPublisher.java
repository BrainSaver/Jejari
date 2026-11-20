package com.jejari.behavior;

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * 행동 로그를 Kafka로 보낸다. <b>요청 스레드는 절대 기다리지 않는다.</b>
 *
 * <ul>
 *   <li>전용 스레드 1개 + {@value #QUEUE_CAPACITY}건 큐. Kafka가 느리거나 죽어도 예매 API 응답은 그대로다.
 *       큐가 넘치면 버리고 개수를 센다 (메모리가 끝없이 늘지 않게). 로그 몇 건 유실 &lt; 예매 응답 지연.</li>
 *   <li>행동 로그 전용 프로듀서. 팀 공용 프로듀서(acks=all·멱등, 예약 이벤트용)와 설정을 섞지 않는다:
 *       메타데이터 대기 {@code max.block.ms}=2초, 전송 제한 10초, 묶어 보내기(linger 20ms)·lz4 압축.</li>
 *   <li>중복 전송(재시도)은 적재 컨슈머가 event_id 유니크 인덱스로 걸러 내므로 acks=1로 충분하다.</li>
 * </ul>
 */
@Component
@ConditionalOnExpression("${jejari.behavior.enabled:true} and !'${jejari.hmac.key:}'.isBlank()")
public class BehaviorPublisher implements DisposableBean {
	private static final Logger log = LoggerFactory.getLogger(BehaviorPublisher.class);

	static final int QUEUE_CAPACITY = 50_000;
	private static final long WARN_INTERVAL_MS = 10_000;

	private final KafkaTemplate<String, String> kafka;
	private final DefaultKafkaProducerFactory<String, String> producerFactory;
	private final JsonMapper json;
	private final ThreadPoolExecutor sender;
	private final AtomicLong dropped = new AtomicLong();
	private final AtomicLong failed = new AtomicLong();
	private final AtomicLong sent = new AtomicLong();
	private final AtomicLong lastWarnAt = new AtomicLong();

	public BehaviorPublisher(@Value("${spring.kafka.bootstrap-servers}") String bootstrap, JsonMapper json) {
		Map<String, Object> props = new HashMap<>();
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		props.put(ProducerConfig.ACKS_CONFIG, "1");
		props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
		props.put(ProducerConfig.LINGER_MS_CONFIG, 20);
		props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
		props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
		props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
		props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
		props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 16L * 1024 * 1024);
		this.producerFactory = new DefaultKafkaProducerFactory<>(props);
		this.kafka = new KafkaTemplate<>(producerFactory);
		this.json = json;
		this.sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
				r -> {
					Thread t = new Thread(r, "behavior-log-send");
					t.setDaemon(true);
					return t;
				},
				(r, ex) -> warn(dropped, "행동 로그 보내기 큐가 가득 차 버림"));
	}

	/** 바로 돌아온다 */
	public void publish(BehaviorEvent e) {
		try {
			sender.execute(() -> send(e));
		} catch (RejectedExecutionException ex) {
			warn(dropped, "행동 로그를 버림 (종료 중)");
		}
	}

	private void send(BehaviorEvent e) {
		try {
			kafka.send(BehaviorEvent.TOPIC, e.key(), json.writeValueAsString(e)).whenComplete((r, ex) -> {
				if (ex != null) {
					warn(failed, "행동 로그 Kafka 전송 실패: " + ex.getClass().getSimpleName());
				} else {
					sent.incrementAndGet();
				}
			});
		} catch (RuntimeException ex) {
			// 메타데이터를 못 받거나(Kafka 다운, 2초) 버퍼가 가득
			warn(failed, "행동 로그 Kafka 전송 실패: " + ex.getClass().getSimpleName());
		}
	}

	private void warn(AtomicLong counter, String msg) {
		long n = counter.incrementAndGet();
		long now = System.currentTimeMillis();
		long last = lastWarnAt.get();
		if (now - last >= WARN_INTERVAL_MS && lastWarnAt.compareAndSet(last, now)) {
			log.warn("{} (누적 버림 {}건, 실패 {}건)", msg, dropped.get(), failed.get());
		}
	}

	public long droppedCount() {
		return dropped.get();
	}

	public long failedCount() {
		return failed.get();
	}

	/** Kafka가 받았다고 답한 건수 (A-10 트래픽 관제 지표, admin/traffic/PipelineMetrics) */
	public long sentCount() {
		return sent.get();
	}

	/** 보내기 큐에 쌓여 있는 건수 (최대 {@value #QUEUE_CAPACITY}) */
	public int queueSize() {
		return sender.getQueue().size();
	}

	@Override
	public void destroy() throws InterruptedException {
		sender.shutdown();
		sender.awaitTermination(5, TimeUnit.SECONDS);
		kafka.flush();
		producerFactory.destroy();
	}
}
