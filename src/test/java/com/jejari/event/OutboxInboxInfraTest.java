package com.jejari.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.event.inbox.InboxGuard;
import com.jejari.event.inbox.InboxProcessor;
import com.jejari.event.outbox.OutboxRecorder;
import com.jejari.event.relay.EventPublisher;
import com.jejari.event.relay.OutboxRelay;
import com.jejari.event.relay.OutboxRelay.Stop;

import tools.jackson.databind.json.JsonMapper;

/**
 * Outbox 기록·릴레이·Inbox를 실제 Oracle로 확인합니다 (docker compose up -d 뒤 ./gradlew infraTest).
 * Kafka 대신 실패를 마음대로 만들 수 있는 가짜 출구를 씁니다. 각 테스트는 끝나면 롤백됩니다.
 */
@Tag("infra")
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OutboxInboxInfraTest.Beans.class)
class OutboxInboxInfraTest {

	record Sent(String topic, String key, String eventId, String eventType, String value) {}

	static class FakePublisher implements EventPublisher {
		final List<Sent> sent = new CopyOnWriteArrayList<>();
		volatile Predicate<String> failWhenType = t -> false;

		@Override
		public void publish(String topic, String key, String eventId, String eventType, String value) {
			if (failWhenType.test(eventType)) {
				throw new IllegalStateException("브로커 응답 없음 (가짜)");
			}
			sent.add(new Sent(topic, key, eventId, eventType, value));
		}

		List<Sent> forKey(String key) {
			return sent.stream().filter(s -> s.key().equals(key)).toList();
		}
	}

	@TestConfiguration
	static class Beans {
		@Bean
		JsonMapper jsonMapper() {
			return JsonMapper.builder().build();
		}

		@Bean
		EventPolicy eventPolicy(JdbcTemplate jdbc) {
			return new EventPolicy(jdbc, 0);
		}

		@Bean
		OutboxRecorder outboxRecorder(JdbcTemplate jdbc, JsonMapper json) {
			return new OutboxRecorder(jdbc, json);
		}

		@Bean
		FakePublisher fakePublisher() {
			return new FakePublisher();
		}

		@Bean
		OutboxRelay outboxRelay(JdbcTemplate jdbc, PlatformTransactionManager tm, FakePublisher p, EventPolicy policy,
				JsonMapper json) {
			return new OutboxRelay(jdbc, new TransactionTemplate(tm), p, policy, json);
		}

		@Bean
		InboxGuard inboxGuard(JdbcTemplate jdbc) {
			return new InboxGuard(jdbc);
		}

		@Bean
		InboxProcessor inboxProcessor(InboxGuard g, PlatformTransactionManager tm) {
			return new InboxProcessor(g, new TransactionTemplate(tm));
		}
	}

	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	OutboxRecorder outbox;
	@Autowired
	OutboxRelay relay;
	@Autowired
	FakePublisher publisher;
	@Autowired
	InboxProcessor inbox;
	@Autowired
	JsonMapper json;
	@Autowired
	PlatformTransactionManager tm;

	long resv;

	@BeforeEach
	void setUp() {
		publisher.sent.clear();
		publisher.failWhenType = t -> false;
		// 다른 테스트·앱 실행이 남긴 행과 섞이지 않게 큰 임의 번호를 씁니다
		resv = ThreadLocalRandom.current().nextLong(9_000_000_000L, 9_999_999_999L);
	}

	// ---- EVT-01 기록 ----

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void 트랜잭션_밖에서_기록하면_실패한다() {
		assertThatThrownBy(() -> outbox.recordReservation(resv, EventType.SEAT_HELD, Map.of("r", resv)))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void 기록하면_PENDING_행이_생기고_샤드는_문자열_해시다() {
		String id = outbox.recordReservation(resv, EventType.SEAT_HELD, Map.of("reservationId", resv));

		Map<String, Object> row = jdbc.queryForMap(
				"SELECT status, retry_count, partition_key, shard_no, aggregate_type, event_type FROM outbox_event WHERE event_id = ?",
				id);
		assertThat(row.get("STATUS")).isEqualTo("PENDING");
		assertThat(((Number) row.get("RETRY_COUNT")).intValue()).isZero();
		assertThat(row.get("PARTITION_KEY")).isEqualTo(Long.toString(resv));
		assertThat(row.get("AGGREGATE_TYPE")).isEqualTo("RESERVATION");
		assertThat(((Number) row.get("SHARD_NO")).intValue()).isEqualTo(shardOf(resv));
	}

	@Test
	void T32_숫자와_문자열의_ORA_HASH는_다르다() {
		// 이 차이 때문에 파티션 키를 항상 문자열로 바인딩합니다. 섞이면 같은 예약이 다른 샤드로 갑니다
		Integer differs = jdbc.queryForObject("""
				SELECT COUNT(*) FROM (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 200)
				 WHERE ORA_HASH(n, 15) <> ORA_HASH(TO_CHAR(n), 15)
				""", Integer.class);
		assertThat(differs).isPositive();

		// 같은 예약의 이벤트는 몇 번을 기록해도 같은 샤드
		for (EventType t : List.of(EventType.SEAT_HELD, EventType.PAYMENT_STARTED, EventType.RESERVATION_CONFIRMED)) {
			outbox.recordReservation(resv, t, Map.of());
		}
		Integer shards = jdbc.queryForObject(
				"SELECT COUNT(DISTINCT shard_no) FROM outbox_event WHERE partition_key = ?", Integer.class,
				Long.toString(resv));
		assertThat(shards).isEqualTo(1);
	}

	// ---- EVT-02 릴레이 ----

	@Test
	void 릴레이는_기록한_순서대로_보내고_PUBLISHED로_바꾼다() {
		outbox.recordReservation(resv, EventType.SEAT_HELD, Map.of("step", 1));
		outbox.recordReservation(resv, EventType.PAYMENT_STARTED, Map.of("step", 2));
		outbox.recordReservation(resv, EventType.RESERVATION_CONFIRMED, Map.of("step", 3));

		var result = relay.relayShard(shardOf(resv));

		assertThat(result.stop()).isEqualTo(Stop.NONE);
		List<Sent> mine = publisher.forKey(Long.toString(resv));
		assertThat(mine).extracting(Sent::eventType)
				.containsExactly("SEAT_HELD", "PAYMENT_STARTED", "RESERVATION_CONFIRMED");
		assertThat(mine).extracting(Sent::topic).containsOnly(EventTopics.RESERVATION);

		EventEnvelope first = EventEnvelope.parse(json, mine.get(0).value());
		assertThat(first.eventId()).isEqualTo(mine.get(0).eventId());
		assertThat(first.aggregateId()).isEqualTo(Long.toString(resv));
		assertThat(first.payload().path("step").asInt()).isEqualTo(1);

		assertThat(statuses()).containsOnly("PUBLISHED");
		Integer noTime = jdbc.queryForObject(
				"SELECT COUNT(*) FROM outbox_event WHERE partition_key = ? AND published_at IS NULL", Integer.class,
				Long.toString(resv));
		assertThat(noTime).isZero();
	}

	@Test
	void 발행이_실패하면_그_샤드의_뒤_이벤트는_보내지_않는다() {
		outbox.recordReservation(resv, EventType.SEAT_HELD, Map.of());
		outbox.recordReservation(resv, EventType.PAYMENT_STARTED, Map.of());
		publisher.failWhenType = "SEAT_HELD"::equals;

		var result = relay.relayShard(shardOf(resv));

		assertThat(result.stop()).isEqualTo(Stop.PUBLISH_FAILED);
		assertThat(publisher.forKey(Long.toString(resv))).isEmpty(); // 뒤의 PAYMENT_STARTED가 앞지르지 않음
		assertThat(statuses()).containsExactly("PENDING", "PENDING");
		assertThat(retryCounts()).containsExactly(1, 0);

		// 브로커가 살아나면 다음 회차에 앞 행부터 순서대로
		publisher.failWhenType = t -> false;
		relay.relayShard(shardOf(resv));
		assertThat(publisher.forKey(Long.toString(resv))).extracting(Sent::eventType)
				.containsExactly("SEAT_HELD", "PAYMENT_STARTED");
	}

	@Test
	void 재시도를_다_쓰면_FAILED가_되고_그_샤드는_멈춘다() {
		outbox.recordReservation(resv, EventType.SEAT_HELD, Map.of());
		outbox.recordReservation(resv, EventType.PAYMENT_STARTED, Map.of());
		publisher.failWhenType = "SEAT_HELD"::equals;
		int max = jdbc.queryForObject("SELECT TO_NUMBER(policy_value) FROM app_policy WHERE policy_key = 'outbox.max_retry'",
				Integer.class);

		for (int i = 0; i < max; i++) {
			relay.relayShard(shardOf(resv));
		}
		assertThat(statuses()).containsExactly("FAILED", "PENDING");

		// 브로커가 살아나도 관리자가 되돌리기 전에는 그 샤드를 처리하지 않음
		publisher.failWhenType = t -> false;
		var result = relay.relayShard(shardOf(resv));
		assertThat(result.stop()).isEqualTo(Stop.HEAD_FAILED);
		assertThat(publisher.forKey(Long.toString(resv))).isEmpty();

		// 관리자 조치 (§11.4): PENDING, retry_count = 0 → 재개
		jdbc.update("UPDATE outbox_event SET status = 'PENDING', retry_count = 0 WHERE partition_key = ? AND status = 'FAILED'",
				Long.toString(resv));
		relay.relayShard(shardOf(resv));
		assertThat(publisher.forKey(Long.toString(resv))).extracting(Sent::eventType)
				.containsExactly("SEAT_HELD", "PAYMENT_STARTED");
	}

	// ---- EVT-03 Inbox ----

	@Test
	void 같은_이벤트가_두_번_와도_한_번만_처리한다() {
		EventEnvelope e = envelope();
		AtomicInteger handled = new AtomicInteger();

		boolean first = inbox.process(EventTopics.GROUP_NOTIFICATION, e, ev -> handled.incrementAndGet());
		boolean second = inbox.process(EventTopics.GROUP_NOTIFICATION, e, ev -> handled.incrementAndGet());

		assertThat(first).isTrue();
		assertThat(second).isFalse();
		assertThat(handled).hasValue(1);
	}

	@Test
	void 그룹이_다르면_각자_한_번씩_처리한다() {
		EventEnvelope e = envelope();
		AtomicInteger handled = new AtomicInteger();

		inbox.process(EventTopics.GROUP_NOTIFICATION, e, ev -> handled.incrementAndGet());
		inbox.process(EventTopics.GROUP_SEAT_RELEASE, e, ev -> handled.incrementAndGet());

		assertThat(handled).hasValue(2);
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void 업무_처리가_실패하면_도장도_롤백돼_다시_처리된다() {
		EventEnvelope e = envelope();
		AtomicInteger handled = new AtomicInteger();
		try {
			assertThatThrownBy(() -> inbox.process(EventTopics.GROUP_NOTIFICATION, e, ev -> {
				throw new IllegalStateException("알림 저장 실패");
			})).isInstanceOf(IllegalStateException.class);

			boolean retried = inbox.process(EventTopics.GROUP_NOTIFICATION, e, ev -> handled.incrementAndGet());

			assertThat(retried).isTrue();
			assertThat(handled).hasValue(1);
		} finally {
			// 이 테스트는 실제로 커밋하므로 직접 지웁니다
			new TransactionTemplate(tm).executeWithoutResult(s -> jdbc.update(
					"DELETE FROM processed_event WHERE event_id = ?", e.eventId()));
		}
	}

	// ---- 도우미 ----

	private int shardOf(long reservationId) {
		return jdbc.queryForObject("SELECT ORA_HASH(TO_CHAR(?), 15) FROM dual", Integer.class,
				Long.toString(reservationId));
	}

	private List<String> statuses() {
		return jdbc.queryForList("SELECT status FROM outbox_event WHERE partition_key = ? ORDER BY seq", String.class,
				Long.toString(resv));
	}

	private List<Integer> retryCounts() {
		return jdbc.queryForList("SELECT retry_count FROM outbox_event WHERE partition_key = ? ORDER BY seq",
				Integer.class, Long.toString(resv));
	}

	private EventEnvelope envelope() {
		return new EventEnvelope(java.util.UUID.randomUUID().toString(), "RESERVATION_CONFIRMED", "RESERVATION",
				Long.toString(resv), 1, null, json.createObjectNode());
	}
}
