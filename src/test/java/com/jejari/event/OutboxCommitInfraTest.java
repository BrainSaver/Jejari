package com.jejari.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.event.inbox.InboxGuard;
import com.jejari.event.inbox.InboxProcessor;
import com.jejari.event.outbox.OutboxRecorder;
import com.jejari.event.relay.EventPublisher;
import com.jejari.event.relay.OutboxRelay.Stop;
import com.jejari.event.relay.OutboxRelay;
import com.jejari.support.NoRunningApp;

import tools.jackson.databind.json.JsonMapper;

/**
 * 실제로 커밋·롤백하며 확인하는 테스트. {@link OutboxInboxInfraTest}는 테스트 전체가 한 트랜잭션 안에서 돌고 롤백되므로
 * "업무가 롤백되면 이벤트도 사라지는가", "커밋 전에는 릴레이가 못 보는가", "두 릴레이가 동시에 돌면" 같은
 * 트랜잭션 경계·연결 사이의 동작은 여기서 봅니다.
 *
 * <p>행을 실제로 커밋하므로 각 테스트가 끝나면 자기가 만든 행을 지웁니다.
 * <b>앱(bootRun)을 켜 둔 채로 돌리지 마세요.</b> 앱의 릴레이가 이 테스트의 행을 먼저 가져갑니다.
 */
@Tag("infra")
@ExtendWith(NoRunningApp.class)
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(OutboxCommitInfraTest.Beans.class)
class OutboxCommitInfraTest {

	record Sent(String key, String eventId, String eventType, long seq) {}

	/** 보낸 기록을 남기고, 조건에 따라 실패하거나 "보낸 뒤 죽은 것처럼" 예외를 던지는 가짜 출구. */
	static class FakePublisher implements EventPublisher {
		final List<Sent> sent = new CopyOnWriteArrayList<>();
		volatile Predicate<String> failBeforeSend = t -> false;
		volatile Predicate<String> crashAfterSend = t -> false;
		volatile long delayMs = 0;
		final JsonMapper json = JsonMapper.builder().build();

		@Override
		public void publish(String topic, String key, String eventId, String eventType, String value)
				throws Exception {
			if (failBeforeSend.test(eventType)) {
				throw new IllegalStateException("브로커 응답 없음 (가짜)");
			}
			if (delayMs > 0) {
				Thread.sleep(delayMs);
			}
			sent.add(new Sent(key, eventId, eventType, EventEnvelope.parse(json, value).seq()));
			if (crashAfterSend.test(eventType)) {
				throw new IllegalStateException("보낸 직후 연결 끊김 (가짜)");
			}
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

		/** 동시 실행 테스트용 두 번째 릴레이 (다른 인스턴스라고 생각) */
		@Bean
		OutboxRelay secondRelay(JdbcTemplate jdbc, PlatformTransactionManager tm, FakePublisher p, EventPolicy policy,
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

		@Bean
		EventCleanup eventCleanup(JdbcTemplate jdbc, EventPolicy policy) {
			return new EventCleanup(jdbc, policy);
		}
	}

	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	PlatformTransactionManager tm;
	@Autowired
	OutboxRecorder outbox;
	@Autowired
	OutboxRelay outboxRelay;
	@Autowired
	OutboxRelay secondRelay;
	@Autowired
	FakePublisher publisher;
	@Autowired
	InboxProcessor inbox;
	@Autowired
	EventCleanup cleanup;
	@Autowired
	JsonMapper json;

	TransactionTemplate tx;
	final List<String> keys = new ArrayList<>();
	final List<String> eventIds = new ArrayList<>();
	String savedBatch;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(tm);
		publisher.sent.clear();
		publisher.failBeforeSend = t -> false;
		publisher.crashAfterSend = t -> false;
		publisher.delayMs = 0;
	}

	@AfterEach
	void cleanUp() {
		for (String k : keys) {
			jdbc.update("DELETE FROM outbox_event WHERE partition_key = ?", k);
		}
		for (String id : eventIds) {
			jdbc.update("DELETE FROM processed_event WHERE event_id = ?", id);
		}
		if (savedBatch != null) {
			jdbc.update("UPDATE app_policy SET policy_value = ? WHERE policy_key = 'outbox.relay_batch'", savedBatch);
		}
	}

	// ---- 트랜잭션 경계 ----

	@Test
	void 업무가_롤백되면_outbox_행도_사라진다() {
		long r = newKey();

		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			businessWrite(r); // 업무 쓰기 대용
			outbox.recordReservation(r, EventType.SEAT_HELD, Map.of());
			throw new IllegalStateException("업무 검증 실패");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(count(r)).isZero();
		assertThat(businessRows(r)).isZero();
	}

	@Test
	void 업무가_커밋되면_outbox_행도_함께_남는다() {
		long r = newKey();

		tx.executeWithoutResult(s -> {
			businessWrite(r);
			outbox.recordReservation(r, EventType.SEAT_HELD, Map.of());
		});

		assertThat(count(r)).isEqualTo(1);
		assertThat(businessRows(r)).isEqualTo(1);
	}

	@Test
	void 커밋_전에는_릴레이가_보지_못하고_커밋_뒤에_보낸다() throws Exception {
		long r = newKey();
		CountDownLatch recorded = new CountDownLatch(1);
		CountDownLatch mayCommit = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<?> business = pool.submit(() -> tx.executeWithoutResult(s -> {
				outbox.recordReservation(r, EventType.SEAT_HELD, Map.of());
				recorded.countDown();
				await(mayCommit); // 아직 커밋 안 함
			}));
			assertThat(recorded.await(10, TimeUnit.SECONDS)).isTrue();

			outboxRelay.relayShard(shardOf(r)); // 다른 연결
			assertThat(publisher.forKey(key(r))).isEmpty();

			mayCommit.countDown();
			business.get(10, TimeUnit.SECONDS);

			outboxRelay.relayShard(shardOf(r));
			assertThat(publisher.forKey(key(r))).hasSize(1);
		} finally {
			mayCommit.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	void 중간에_실패해도_앞에서_보낸_것은_PUBLISHED로_커밋된다() {
		long r = newKey();
		recordCommitted(r, EventType.SEAT_HELD, EventType.PAYMENT_STARTED, EventType.RESERVATION_CONFIRMED);
		publisher.failBeforeSend = "RESERVATION_CONFIRMED"::equals;

		var result = outboxRelay.relayShard(shardOf(r));

		assertThat(result).isEqualTo(new OutboxRelay.ShardResult(shardOf(r), 2, Stop.PUBLISH_FAILED));
		// 새 연결로 읽어도(자동 커밋) 앞의 둘은 PUBLISHED, 마지막은 재시도 1회로 남아 있어야 함
		assertThat(statuses(r)).containsExactly("PUBLISHED", "PUBLISHED", "PENDING");
		assertThat(jdbc.queryForObject("SELECT MAX(retry_count) FROM outbox_event WHERE partition_key = ?",
				Integer.class, key(r))).isEqualTo(1);

		// 다음 회차에는 실패했던 것만 보냄 (앞의 둘을 다시 보내지 않음)
		publisher.failBeforeSend = t -> false;
		outboxRelay.relayShard(shardOf(r));
		assertThat(publisher.forKey(key(r))).extracting(Sent::eventType)
				.containsExactly("SEAT_HELD", "PAYMENT_STARTED", "RESERVATION_CONFIRMED");
	}

	// ---- 동시성 ----

	@Test
	void 릴레이_둘이_동시에_돌아도_한_번씩_순서대로_보낸다() throws Exception {
		long r = newKey();
		int n = 40;
		tx.executeWithoutResult(s -> {
			for (int i = 0; i < n; i++) {
				outbox.recordReservation(r, EventType.SEAT_HELD, Map.of("i", i));
			}
		});
		publisher.delayMs = 5; // 첫 릴레이가 락을 잡고 있는 동안 둘째가 기다리게

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch go = new CountDownLatch(1);
		try {
			Future<?> a = pool.submit(() -> {
				await(go);
				return outboxRelay.relayShard(shardOf(r));
			});
			Future<?> b = pool.submit(() -> {
				await(go);
				return secondRelay.relayShard(shardOf(r));
			});
			go.countDown();
			a.get(30, TimeUnit.SECONDS);
			b.get(30, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}

		List<Sent> mine = publisher.forKey(key(r));
		assertThat(mine).hasSize(n);
		assertThat(mine).extracting(Sent::eventId).doesNotHaveDuplicates();
		assertThat(mine).isSortedAccordingTo(Comparator.comparingLong(Sent::seq));
		assertThat(statuses(r)).containsOnly("PUBLISHED");
	}

	@Test
	void 다른_쪽이_잠그고_있으면_기다리다_이번_회차를_포기한다() throws Exception {
		long r = newKey();
		recordCommitted(r, EventType.SEAT_HELD);
		long waitSec = jdbc.queryForObject(
				"SELECT TO_NUMBER(policy_value) FROM app_policy WHERE policy_key = 'relay.lock_wait_sec'", Long.class);

		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			pool.submit(() -> tx.executeWithoutResult(s -> {
				jdbc.queryForList("SELECT event_id FROM outbox_event WHERE partition_key = ? FOR UPDATE", key(r));
				locked.countDown();
				await(release);
			}));
			assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

			long t0 = System.nanoTime();
			var result = outboxRelay.relayShard(shardOf(r));
			long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

			assertThat(result.stop()).isEqualTo(Stop.LOCK_TIMEOUT);
			assertThat(waitedMs).isGreaterThanOrEqualTo(waitSec * 1000 - 500); // SKIP LOCKED처럼 건너뛰지 않고 기다렸음
			assertThat(publisher.forKey(key(r))).isEmpty();
		} finally {
			release.countDown();
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}

		outboxRelay.relayShard(shardOf(r)); // 락이 풀리면 다음 회차에 보냄
		assertThat(publisher.forKey(key(r))).hasSize(1);
	}

	// ---- 끝까지 이어서: 재발송 → Inbox ----

	@Test
	void 보낸_뒤_표시_전에_끊기면_같은_event_id로_다시_가고_Inbox가_한_번만_처리한다() {
		long r = newKey();
		recordCommitted(r, EventType.RESERVATION_CONFIRMED);
		publisher.crashAfterSend = t -> true;
		outboxRelay.relayShard(shardOf(r)); // 브로커는 받았지만 릴레이는 실패로 앎
		publisher.crashAfterSend = t -> false;
		outboxRelay.relayShard(shardOf(r)); // 다시 보냄

		List<Sent> mine = publisher.forKey(key(r));
		assertThat(mine).hasSize(2);
		assertThat(mine.get(0).eventId()).isEqualTo(mine.get(1).eventId());

		AtomicInteger notified = new AtomicInteger();
		for (Sent s : mine) {
			eventIds.add(s.eventId());
			var e = new EventEnvelope(s.eventId(), s.eventType(), "RESERVATION", key(r), s.seq(), null,
					json.createObjectNode());
			inbox.process(EventTopics.GROUP_NOTIFICATION, e, ev -> notified.incrementAndGet());
		}
		assertThat(notified).hasValue(1);
	}

	// ---- 샤드·배치 ----

	@Test
	void 한_샤드가_멈춰도_다른_샤드는_계속_보낸다() {
		long stuck = newKey();
		long other = newKey();
		while (shardOf(other) == shardOf(stuck)) {
			keys.remove(key(other));
			other = newKey();
		}
		recordCommitted(stuck, EventType.SEAT_HELD);
		recordCommitted(other, EventType.SEAT_HELD);
		jdbc.update("UPDATE outbox_event SET status = 'FAILED' WHERE partition_key = ?", key(stuck));

		outboxRelay.relayAll();

		assertThat(publisher.forKey(key(stuck))).isEmpty();
		assertThat(publisher.forKey(key(other))).hasSize(1);
	}

	@Test
	void 한_번에_relay_batch만큼만_가져간다() {
		savedBatch = jdbc.queryForObject("SELECT policy_value FROM app_policy WHERE policy_key = 'outbox.relay_batch'",
				String.class);
		jdbc.update("UPDATE app_policy SET policy_value = '2' WHERE policy_key = 'outbox.relay_batch'");
		long r = newKey();
		recordCommitted(r, EventType.SEAT_HELD, EventType.PAYMENT_STARTED, EventType.RESERVATION_CONFIRMED,
				EventType.RESERVATION_CANCELED, EventType.RESERVATION_EXPIRED);

		assertThat(outboxRelay.relayShard(shardOf(r)).published()).isEqualTo(2);
		assertThat(outboxRelay.relayShard(shardOf(r)).published()).isEqualTo(2);
		assertThat(outboxRelay.relayShard(shardOf(r)).published()).isEqualTo(1);
		assertThat(publisher.forKey(key(r))).extracting(Sent::eventType).containsExactly("SEAT_HELD",
				"PAYMENT_STARTED", "RESERVATION_CONFIRMED", "RESERVATION_CANCELED", "RESERVATION_EXPIRED");
	}

	// ---- 정리 ----

	@Test
	void 정리_배치는_보관_기간이_지난_행만_지운다() {
		long oldPub = newKey();
		long newPub = newKey();
		long pending = newKey();
		recordCommitted(oldPub, EventType.SEAT_HELD);
		recordCommitted(newPub, EventType.SEAT_HELD);
		recordCommitted(pending, EventType.SEAT_HELD);
		jdbc.update("UPDATE outbox_event SET status = 'PUBLISHED', published_at = SYSTIMESTAMP - INTERVAL '25' HOUR "
				+ "WHERE partition_key = ?", key(oldPub));
		jdbc.update("UPDATE outbox_event SET status = 'PUBLISHED', published_at = SYSTIMESTAMP - INTERVAL '23' HOUR "
				+ "WHERE partition_key = ?", key(newPub));
		jdbc.update("UPDATE outbox_event SET created_at = SYSTIMESTAMP - INTERVAL '3' DAY WHERE partition_key = ?",
				key(pending)); // 오래됐어도 안 보낸 건 지우면 안 됨

		String oldInbox = uuid();
		String newInbox = uuid();
		jdbc.update("INSERT INTO processed_event (consumer_group, event_id, processed_at) "
				+ "VALUES ('cleanup-test', ?, SYSTIMESTAMP - INTERVAL '9' DAY)", oldInbox);
		jdbc.update("INSERT INTO processed_event (consumer_group, event_id, processed_at) "
				+ "VALUES ('cleanup-test', ?, SYSTIMESTAMP - INTERVAL '7' DAY)", newInbox);

		cleanup.run();

		assertThat(count(oldPub)).isZero();
		assertThat(count(newPub)).isEqualTo(1);
		assertThat(count(pending)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id IN (?, ?)", Integer.class,
				oldInbox, newInbox)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id = ?", Integer.class,
				newInbox)).isEqualTo(1);
	}

	// ---- 도우미 ----

	private long newKey() {
		long r = ThreadLocalRandom.current().nextLong(9_000_000_000L, 9_999_999_999L);
		keys.add(key(r));
		return r;
	}

	private static String key(long r) {
		return Long.toString(r);
	}

	private String uuid() {
		String id = java.util.UUID.randomUUID().toString();
		eventIds.add(id);
		return id;
	}

	/** 업무 쓰기 대용: processed_event에 표시 행을 남깁니다 (이 테스트 전용 그룹). */
	private void businessWrite(long r) {
		String id = "biz-" + r;
		eventIds.add(id);
		jdbc.update("INSERT INTO processed_event (consumer_group, event_id, processed_at) VALUES ('biz-test', ?, SYSTIMESTAMP)",
				id);
	}

	private int businessRows(long r) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id = ?", Integer.class, "biz-" + r);
	}

	private void recordCommitted(long r, EventType... types) {
		tx.executeWithoutResult(s -> {
			for (EventType t : types) {
				outbox.recordReservation(r, t, Map.of());
			}
		});
	}

	private int count(long r) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE partition_key = ?", Integer.class, key(r));
	}

	private List<String> statuses(long r) {
		return jdbc.queryForList("SELECT status FROM outbox_event WHERE partition_key = ? ORDER BY seq", String.class,
				key(r));
	}

	private int shardOf(long r) {
		return jdbc.queryForObject("SELECT ORA_HASH(TO_CHAR(?), 15) FROM dual", Integer.class, key(r));
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(30, TimeUnit.SECONDS)) {
				throw new IllegalStateException("대기 시간 초과");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
