package com.jejari.notification;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

import com.jejari.event.EventEnvelope;
import com.jejari.event.EventTopics;
import com.jejari.event.dlq.DeadLetters;
import com.jejari.event.inbox.InboxProcessor;

import tools.jackson.databind.json.JsonMapper;

/**
 * 소비 그룹 {@code notification} (EVT-06, concurrency.md §12). 예약 토픽을 읽어 알림 행을 남깁니다.
 *
 * <p>Inbox({@code processed_event})와 알림 INSERT가 한 트랜잭션이라, 같은 이벤트가 두 번 와도 알림은 1건입니다.
 * 처리 중 예외가 나면 1초 간격으로 3번 다시 시도하고, 그래도 안 되면 다음 메시지로 넘어갑니다.
 * 알림 하나 때문에 뒤의 알림이 모두 멈추면 안 되기 때문입니다. 넘긴 메시지와 형식이 깨진 메시지는 버리지 않고
 * 격리 보관함({@link DeadLetters}, {@code jejari.reservation.DLT})에 옮긴다 → 원인을 고친 뒤 tools/dlq.sh로 다시 처리.
 */
@Component
@ConditionalOnProperty(name = "jejari.notification.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationConsumer {

	private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);
	public static final String CONTAINER_FACTORY = "notificationContainerFactory";

	private final JsonMapper json;
	private final InboxProcessor inbox;
	private final NotificationEventHandler handler;
	private DeadLetters deadLetters;   // 없으면(단위 시험) 예전처럼 로그만

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	void setDeadLetters(DeadLetters deadLetters) {
		this.deadLetters = deadLetters;
	}

	public NotificationConsumer(JsonMapper json, InboxProcessor inbox, NotificationEventHandler handler) {
		this.json = json;
		this.inbox = inbox;
		this.handler = handler;
	}

	@KafkaListener(id = EventTopics.GROUP_NOTIFICATION, topics = EventTopics.RESERVATION,
			containerFactory = CONTAINER_FACTORY)
	public void consume(ConsumerRecord<String, String> record) {
		if (DeadLetters.notForMe(record, EventTopics.GROUP_NOTIFICATION)) {
			return;   // 다른 그룹 몫으로 다시 넣은 메시지
		}
		EventEnvelope e;
		try {
			e = parseStrict(record.value());
		} catch (DeadLetters.Malformed m) {
			if (deadLetters != null) {
				deadLetters.park(EventTopics.GROUP_NOTIFICATION, record, DeadLetters.MALFORMED, m);
			}
			return;
		}
		if (e == null || !handler.handles(e.eventType())) {
			return;
		}
		inbox.process(EventTopics.GROUP_NOTIFICATION, e, handler::handle);
	}

	EventEnvelope parse(String value) {
		try {
			return parseStrict(value);
		} catch (DeadLetters.Malformed m) {
			return null;
		}
	}

	/** 형식이 깨졌으면 Malformed (격리 보관 대상) */
	EventEnvelope parseStrict(String value) {
		if (value == null || value.length() > 1_000_000) {
			throw new DeadLetters.Malformed(value == null ? "값 없음" : "너무 큼");
		}
		try {
			EventEnvelope e = EventEnvelope.parse(json, value);
			if (e.eventId() == null || e.eventId().isBlank()) {
				throw new DeadLetters.Malformed("eventId 없음");
			}
			return e;
		} catch (DeadLetters.Malformed m) {
			throw m;
		} catch (RuntimeException ex) {
			log.warn("notification: 형식이 틀린 이벤트 → 격리 보관");
			throw new DeadLetters.Malformed(ex.getClass().getSimpleName());
		}
	}

	@Bean(CONTAINER_FACTORY)
	static ConcurrentKafkaListenerContainerFactory<String, String> notificationContainerFactory(
			@Value("${spring.kafka.bootstrap-servers}") String bootstrap,
			org.springframework.beans.factory.ObjectProvider<DeadLetters> deadLetters) {
		Map<String, Object> props = new HashMap<>();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
		props.put(ConsumerConfig.GROUP_ID_CONFIG, EventTopics.GROUP_NOTIFICATION);
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

		ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
		f.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
		// 3번 다시 해도 실패하면 격리 보관함으로 (예전에는 로그만 남기고 버렸다)
		f.setCommonErrorHandler(new DefaultErrorHandler((rec, ex) -> {
			DeadLetters d = deadLetters.getIfAvailable();
			if (d != null) {
				d.park(EventTopics.GROUP_NOTIFICATION, rec, DeadLetters.RETRIES_EXHAUSTED, ex);
			} else {
				log.error("notification: 3번 실패해 건너뜀 (격리 보관함 없음) {}-{}@{}", rec.topic(), rec.partition(), rec.offset(), ex);
			}
		}, new FixedBackOff(1000L, 3)));
		return f;
	}
}
