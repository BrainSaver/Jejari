package com.jejari.waitlist.offer;

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

import com.jejari.event.EventEnvelope;
import com.jejari.event.EventTopics;
import com.jejari.event.EventType;
import com.jejari.event.inbox.InboxGuard;
import com.jejari.seat.SeatHoldService;
import com.jejari.seat.SeatReleaseService;
import com.jejari.waitlist.offer.WaitlistOfferTx.Offered;

import tools.jackson.databind.json.JsonMapper;

/**
 * 소비 그룹 {@code seat-release} (concurrency.md §9.1, §14.3). 빈 좌석 이벤트
 * ({@code RESERVATION_EXPIRED} · {@code RESERVATION_CANCELED})를 받아 두 가지를 한다.
 *
 * <ol>
 *   <li><b>Redis 보상의 보장 경로</b> (§14.3). 해제한 쪽이 커밋 직후 바로 하는 "빠른 경로"가
 *       앱이 죽어 유실될 수 있어, 이 소비자가 같은 두 스크립트를 다시 돌린다. 두 번 돌려도 결과가 같다.</li>
 *   <li><b>취소표 제안</b> (§9). 풀린 좌석을 대기 순서대로 대기자에게 먼저 준다.</li>
 * </ol>
 *
 * <p>순서가 중요하다. <b>Redis 보상을 트랜잭션 밖에서 먼저</b> 하고(1번), 그래야 좌석 키가
 * {@code REOFFER:R}이 되어 일반 사용자의 {@code hold.lua}를 막는다. 그다음 트랜잭션 T를 열어
 * 도장·제안·알림을 한꺼번에 커밋한다 (§9.1-1·2).
 *
 * <p>T가 롤백되면 제안 예약의 Redis(한도·좌석 키)를 되돌리고 이벤트 재전달을 기다린다 (§9.1-9).
 *
 * <p>개발 모드에서는 만들지 않는다. 부하 테스트용 개발 앱이 실제 대기자에게 제안해 버리면
 * 시나리오가 섞인다 ({@code RiskSignalListener}와 같은 이유).
 */
@Component
@ConditionalOnProperty(name = "jejari.waitlist.offer-enabled", havingValue = "true", matchIfMissing = true)
public class SeatReleaseConsumer {

	private static final Logger log = LoggerFactory.getLogger(SeatReleaseConsumer.class);
	public static final String CONTAINER_FACTORY = "seatReleaseContainerFactory";

	private final JsonMapper json;
	private final TransactionTemplate tx;
	private final InboxGuard inbox;
	private final WaitlistOfferTx offerTx;
	private final SeatReleaseService release;
	private final SeatHoldService seatHold;

	public SeatReleaseConsumer(JsonMapper json, TransactionTemplate tx, InboxGuard inbox, WaitlistOfferTx offerTx,
			SeatReleaseService release, SeatHoldService seatHold) {
		this.json = json;
		this.tx = tx;
		this.inbox = inbox;
		this.offerTx = offerTx;
		this.release = release;
		this.seatHold = seatHold;
	}

	/** 이벤트에서 읽은 빈 좌석 */
	record FreedSeats(String eventId, long reservationId, long runId, String from, String to, List<Long> seatIds) {
	}

	@KafkaListener(id = EventTopics.GROUP_SEAT_RELEASE, topics = EventTopics.RESERVATION,
			containerFactory = CONTAINER_FACTORY)
	public void consume(List<ConsumerRecord<String, String>> records) {
		for (ConsumerRecord<String, String> record : records) {
			if (com.jejari.event.dlq.DeadLetters.notForMe(record, EventTopics.GROUP_SEAT_RELEASE)) {
				continue;   // 다른 그룹 몫으로 다시 넣은 메시지
			}
			FreedSeats freed;
			try {
				freed = parseStrict(record.value());
			} catch (com.jejari.event.dlq.DeadLetters.Malformed m) {
				park(record, com.jejari.event.dlq.DeadLetters.MALFORMED, m);
				continue;
			}
			if (freed == null) {
				continue;   // 빈 좌석과 상관없는 이벤트
			}
			try {
				handle(freed);
			} catch (RuntimeException e) {
				// 한 건 실패가 나머지를 막지 않게 넘어간다. 예전 주석은 "재전달은 재시작 때"였지만 AckMode.BATCH라 이 묶음이 끝나면
				// 오프셋이 커밋되어 다시 오지 않는다 → 그 좌석의 취소표 제안이 사라졌다. 지금은 격리 보관함에 옮겨 다시 처리할 수 있게
				log.warn("seat-release: 예약 {} 처리 실패 → 격리 보관", freed.reservationId(), e);
				park(record, com.jejari.event.dlq.DeadLetters.HANDLER_FAILED, e);
			}
		}
	}

	private com.jejari.event.dlq.DeadLetters deadLetters;   // 없으면(단위 시험) 예전처럼 로그만

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	void setDeadLetters(com.jejari.event.dlq.DeadLetters deadLetters) {
		this.deadLetters = deadLetters;
	}

	private void park(ConsumerRecord<String, String> record, String reason, Throwable cause) {
		if (deadLetters != null) {
			deadLetters.park(EventTopics.GROUP_SEAT_RELEASE, record, reason, cause);
		}
	}

	void handle(FreedSeats freed) {
		// 1. Redis 보상 (T 밖, 멱등). 좌석 키를 REOFFER:R로 바꿔 일반 사용자를 막고 한도를 되돌린다
		release.compensateAfterRelease(freed.reservationId());

		// 2~8. 도장·제안·알림을 한 트랜잭션으로
		List<Offered> offered = tx.execute(st -> {
			if (!inbox.firstTime(EventTopics.GROUP_SEAT_RELEASE, freed.eventId())) {
				return List.of();   // 이미 처리한 이벤트 (§12)
			}
			return offerTx.offer(freed.runId(), freed.from(), freed.to(), freed.seatIds(), freed.reservationId());
		});

		if (offered != null && !offered.isEmpty()) {
			log.info("취소표 제안 {}건 (예약 {}가 비운 좌석)", offered.size(), freed.reservationId());
		}
	}

	/** 빈 좌석 이벤트면 내용을, 아니면 null (형식이 깨진 것도 null) */
	FreedSeats parse(String value) {
		try {
			return parseStrict(value);
		} catch (com.jejari.event.dlq.DeadLetters.Malformed m) {
			return null;
		}
	}

	/** 빈 좌석 이벤트면 내용을, 다른 이벤트면 null, 형식이 깨졌으면 Malformed (격리 보관 대상) */
	FreedSeats parseStrict(String value) {
		if (value == null || value.length() > 1_000_000) {
			throw new com.jejari.event.dlq.DeadLetters.Malformed(value == null ? "값 없음" : "너무 큼");
		}
		try {
			EventEnvelope e = EventEnvelope.parse(json, value);
			if (!EventType.RESERVATION_EXPIRED.name().equals(e.eventType())
					&& !EventType.RESERVATION_CANCELED.name().equals(e.eventType())) {
				return null;
			}
			Map<?, ?> p = e.payloadAs(json, Map.class);
			long runId = ((Number) p.get("runId")).longValue();
			List<Long> seatIds = new ArrayList<>();
			for (Object o : (List<?>) p.get("seats")) {
				seatIds.add(((Number) ((Map<?, ?>) o).get("seatId")).longValue());
			}
			if (seatIds.isEmpty()) {
				return null;
			}
			return new FreedSeats(e.eventId(), Long.parseLong(e.aggregateId()), runId,
					(String) p.get("from"), (String) p.get("to"), seatIds);
		} catch (RuntimeException ex) {
			log.warn("seat-release: 형식이 틀린 이벤트 → 격리 보관");
			throw new com.jejari.event.dlq.DeadLetters.Malformed(ex.getClass().getSimpleName());
		}
	}

	@Bean(CONTAINER_FACTORY)
	static ConcurrentKafkaListenerContainerFactory<String, String> seatReleaseContainerFactory(
			@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
		Map<String, Object> props = new HashMap<>();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
		props.put(ConsumerConfig.GROUP_ID_CONFIG, EventTopics.GROUP_SEAT_RELEASE);
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		// 제안은 DB 트랜잭션을 여는 무거운 처리라 한 번에 적게 가져온다 (위험 신호 표시는 500)
		props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);

		ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
		f.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
		f.setBatchListener(true);
		f.getContainerProperties().setAckMode(AckMode.BATCH);
		f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
		return f;
	}
}
