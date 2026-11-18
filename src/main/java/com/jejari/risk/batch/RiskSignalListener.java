package com.jejari.risk.batch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

import com.jejari.event.EventEnvelope;
import com.jejari.event.EventTopics;
import com.jejari.event.EventType;
import com.jejari.risk.SubjectType;

import tools.jackson.databind.json.JsonMapper;

/**
 * 소비 그룹 {@code risk-signal} (기능설계서 8.4). 예약 확정·취소 이벤트가 오면 그 예약의 CI와 결제 수단을
 * 위험 점수 재계산 대상으로 표시한다. 실제 계산은 1분 배치가 한다 ("취소 시 즉시 재계산" = 다음 배치, 최대 1분).
 *
 * <ul>
 *   <li>표시(SADD)는 몇 번 해도 같아서 processed_event(inbox)를 쓰지 않는다. 재전달돼도 결과가 같다.</li>
 *   <li>값은 이벤트에서 믿지 않고 Oracle에서 다시 읽는다 (토픽에 누가 넣었는지 모름. 이벤트의 해시를 그대로 쓰면
 *       남의 해시를 넣어 재계산을 폭주시킬 수 있다).</li>
 *   <li>형식이 깨진 메시지·없는 예약은 건너뛴다 (컨슈머가 멈추지 않게). Oracle·Redis 장애는 5초마다 무한 재시도.</li>
 * </ul>
 */
@Component
@ConditionalOnExpression("${jejari.risk.signal-listener-enabled:true} and !${jejari.dev-mode:false}")
public class RiskSignalListener {
	private static final Logger log = LoggerFactory.getLogger(RiskSignalListener.class);
	public static final String CONTAINER_FACTORY = "riskSignalContainerFactory";

	private final JdbcTemplate jdbc;
	private final JsonMapper json;
	private final RiskDirtyQueue dirty;

	public RiskSignalListener(JdbcTemplate jdbc, JsonMapper json, RiskDirtyQueue dirty) {
		this.jdbc = jdbc;
		this.json = json;
		this.dirty = dirty;
	}

	@KafkaListener(id = EventTopics.GROUP_RISK_SIGNAL, topics = EventTopics.RESERVATION,
			containerFactory = CONTAINER_FACTORY)
	public void consume(List<ConsumerRecord<String, String>> records) {
		List<Long> reservationIds = new ArrayList<>();
		for (ConsumerRecord<String, String> r : records) {
			Long id = reservationId(r.value());
			if (id != null) {
				reservationIds.add(id);
			}
		}
		List<RiskSubject> subjects = new ArrayList<>();
		for (Long id : reservationIds) {
			subjects.addAll(subjectsOf(id));
		}
		dirty.markAll(subjects);
	}

	/** 확정·취소 이벤트면 예약 ID, 아니면(다른 이벤트·깨진 메시지) null */
	Long reservationId(String value) {
		if (value == null || value.length() > 65_536) {
			return null;
		}
		try {
			EventEnvelope e = EventEnvelope.parse(json, value);
			if (!EventType.RESERVATION_CONFIRMED.name().equals(e.eventType())
					&& !EventType.RESERVATION_CANCELED.name().equals(e.eventType())) {
				return null;
			}
			long id = Long.parseLong(e.aggregateId());
			return id > 0 ? id : null;
		} catch (RuntimeException ex) { // JSON 오류(JacksonException)·숫자 아님 등
			log.warn("risk-signal: 형식이 틀린 예약 이벤트를 건너뜀");
			return null;
		}
	}

	/** 예약의 CI와, 결제됐으면 그 결제 수단 */
	List<RiskSubject> subjectsOf(long reservationId) {
		List<RiskSubject> out = new ArrayList<>(2);
		jdbc.query("""
				SELECT r.ci_hash, pm.method_hash
				  FROM reservation r
				  LEFT JOIN payment p ON p.reservation_id = r.reservation_id AND p.status IN ('APPROVED', 'REFUNDED')
				  LEFT JOIN payment_method pm ON pm.method_id = p.method_id
				 WHERE r.reservation_id = ?""",
				rs -> {
					RiskSubject ci = RiskSubject.ofNullable(SubjectType.CI, rs.getString(1));
					RiskSubject pay = RiskSubject.ofNullable(SubjectType.PAYMENT, rs.getString(2));
					if (ci != null && !out.contains(ci)) {
						out.add(ci);
					}
					if (pay != null && !out.contains(pay)) {
						out.add(pay);
					}
				}, reservationId);
		return out;
	}

	@Bean(CONTAINER_FACTORY)
	static ConcurrentKafkaListenerContainerFactory<String, String> riskSignalContainerFactory(
			@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
		Map<String, Object> props = new HashMap<>();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
		props.put(ConsumerConfig.GROUP_ID_CONFIG, EventTopics.GROUP_RISK_SIGNAL);
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);

		ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
		f.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
		f.setBatchListener(true);
		f.getContainerProperties().setAckMode(AckMode.BATCH);
		f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
		return f;
	}
}
