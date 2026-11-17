package com.jejari.risk.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jejari.risk.SubjectType;
import com.jejari.risk.cache.RiskScoreCache;
import com.jejari.support.FullStackInfraTest;
import com.mongodb.client.model.Filters;

/**
 * RISK-02 1분 배치를 실제 Oracle·Redis·MongoDB로 확인한다. {@code docker compose up -d} 뒤 {@code ./gradlew infraTest}.
 * 테스트가 만든 해시·회원·예약만 지운다.
 */
@FullStackInfraTest
class RiskScoreBatchInfraTest {

	static final long MEMBER_BASE = 960000;
	static final AtomicLong NEXT_ID = new AtomicLong(MEMBER_BASE + 1);
	static final long ADMIN_ID = 960999;

	@Autowired
	RiskScoreBatch batch;
	@Autowired
	RiskDirtyQueue dirty;
	@Autowired
	RiskSignalListener listener;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	MongoTemplate mongo;

	final List<String> hashes = new ArrayList<>();
	final List<Long> members = new ArrayList<>();

	@BeforeEach
	void setUp() {
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
	}

	@AfterEach
	void tearDown() {
		for (String h : hashes) {
			jdbc.update("DELETE FROM risk_score_history WHERE subject_hash = ?", h);
			jdbc.update("DELETE FROM risk_score WHERE subject_hash = ?", h);
			jdbc.update("DELETE FROM admin_action WHERE subject_hash = ?", h);
			jdbc.update("DELETE FROM payment_method WHERE method_hash = ?", h);
			jdbc.update("DELETE FROM member_device WHERE device_hash = ?", h);
			mongo.getCollection("behavior_log").deleteMany(Filters.or(Filters.eq("ci_hash", h), Filters.eq("device_hash", h)));
		}
		for (Long m : members) {
			jdbc.update("DELETE FROM cancellation WHERE reservation_id IN (SELECT reservation_id FROM reservation WHERE member_id = ?)", m);
			jdbc.update("DELETE FROM reservation WHERE member_id = ?", m);
			jdbc.update("DELETE FROM member WHERE member_id = ?", m);
		}
		jdbc.update("DELETE FROM admin_user WHERE admin_id = ?", ADMIN_ID);
	}

	// ------------------------------------------------------------ 행동 로그 → 점수

	@Test
	void 빠른_클릭_봇은_점수가_저장되고_캐시와_이력에_남는다() {
		String ci = hash();
		long m = member(ci, Instant.now().minus(30, ChronoUnit.DAYS));
		Instant t = Instant.now().minusSeconds(600);
		List<Document> docs = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			docs.add(event("VIEW_SEAT", m, ci, null, t.plusMillis(100L * i), 100, 200, null));
		}
		mongo.getCollection("behavior_log").insertMany(docs);
		dirty.mark(new RiskSubject(SubjectType.CI, ci));

		RiskScoreBatch.Result r = batch.run();

		assertThat(r.scored()).isEqualTo(1);
		assertThat(r.written()).isEqualTo(1);
		BigDecimal score = finalScore(SubjectType.CI, ci);
		assertThat(score).isEqualByComparingTo("40.00"); // FAST_CLICK MID 25 + 같은 간격 30회 REGULAR_INTERVAL 15
		assertThat(jdbc.queryForObject("SELECT reasons FROM risk_score WHERE subject_hash = ?", String.class, ci))
				.contains("FAST_CLICK").contains("REGULAR_INTERVAL");
		assertThat(redis.opsForValue().get(RiskScoreCache.key(SubjectType.CI, ci))).isEqualTo("40.00");
		assertThat(historyCount(ci)).isEqualTo(1);
		assertThat(dirty.pending()).isZero();
	}

	@Test
	void 같은_증거로_다시_돌려도_행과_이력이_늘지_않는다() {
		String ci = hash();
		long m = member(ci, Instant.now().minus(30, ChronoUnit.DAYS));
		Instant t = Instant.now().minusSeconds(600);
		List<Document> docs = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			docs.add(event("VIEW_SEAT", m, ci, null, t.plusMillis(100L * i), 50, 200, null));
		}
		mongo.getCollection("behavior_log").insertMany(docs);
		RiskSubject s = new RiskSubject(SubjectType.CI, ci);
		dirty.mark(s);
		batch.run();
		dirty.mark(s);
		RiskScoreBatch.Result again = batch.run();

		assertThat(again.scored()).isEqualTo(1);
		assertThat(again.written()).isZero();
		assertThat(historyCount(ci)).isEqualTo(1);
	}

	@Test
	void 정상_사용자는_0점이라_행을_만들지_않는다() {
		String ci = hash();
		long m = member(ci, Instant.now().minus(30, ChronoUnit.DAYS));
		Instant t = Instant.now().minusSeconds(600);
		mongo.getCollection("behavior_log").insertMany(List.of(
				event("VIEW_TRAIN", m, ci, null, t, null, 200, null),
				event("VIEW_SEAT", m, ci, null, t.plusSeconds(3), 3000, 200, null)));
		dirty.mark(new RiskSubject(SubjectType.CI, ci));

		RiskScoreBatch.Result r = batch.run();

		assertThat(r.scored()).isEqualTo(1);
		assertThat(rowCount(ci)).isZero();
	}

	@Test
	void 한_기기로_여러_계정이_동시에_선점하면_기기_점수가_오른다() {
		String device = hash();
		Instant old = Instant.now().minus(30, ChronoUnit.DAYS);
		long m1 = member(hash(), old);
		long m2 = member(hash(), old);
		long m3 = member(hash(), old);
		for (long m : List.of(m1, m2, m3)) {
			jdbc.update("INSERT INTO member_device (device_hash, member_id, first_seen_at, last_seen_at) "
					+ "VALUES (?, ?, SYSTIMESTAMP, SYSTIMESTAMP)", device, m);
		}
		Instant t = Instant.now().minusSeconds(300);
		mongo.getCollection("behavior_log").insertMany(List.of(
				event("SEAT_HOLD", m1, null, device, t, null, 201, List.of(1L)),
				event("SEAT_HOLD", m2, null, device, t.plusSeconds(5), null, 201, List.of(2L)),
				event("SEAT_HOLD", m3, null, device, t.plusSeconds(9), null, 201, List.of(3L))));
		dirty.mark(new RiskSubject(SubjectType.DEVICE, device));

		batch.run();

		String reasons = jdbc.queryForObject("SELECT reasons FROM risk_score WHERE subject_type = 'DEVICE' AND subject_hash = ?",
				String.class, device);
		assertThat(reasons).contains("MULTI_ACCOUNT_SYNC").contains("LINKED_ACCOUNTS");
		assertThat(finalScore(SubjectType.DEVICE, device)).isEqualByComparingTo("50.00"); // 동시 선점 HIGH 40 + 연결 계정 MID 10(3계정)
	}

	// ------------------------------------------------------------ Oracle 신호

	@Test
	void 구매_직후_취소를_반복하면_CI_점수가_오른다() {
		String ci = hash();
		long m = member(ci, Instant.now().minus(30, ChronoUnit.DAYS));
		for (int i = 0; i < 2; i++) {
			long r = confirmedReservation(m, ci, 2);
			userCancel(r, 5, 600);
		}
		dirty.mark(new RiskSubject(SubjectType.CI, ci));

		batch.run();

		String reasons = jdbc.queryForObject("SELECT reasons FROM risk_score WHERE subject_hash = ?", String.class, ci);
		assertThat(reasons).contains("CANCEL_AFTER_PAY");
		assertThat(jdbc.queryForObject("SELECT total_purchase_seats FROM risk_score WHERE subject_hash = ?",
				Long.class, ci)).isEqualTo(4L);
	}

	@Test
	void 같은_카드를_여러_계정이_쓰면_결제수단_점수가_오른다() {
		String card = hash();
		Instant old = Instant.now().minus(30, ChronoUnit.DAYS);
		for (int i = 0; i < 4; i++) {
			long m = member(hash(), old);
			jdbc.update("INSERT INTO payment_method (method_id, member_id, method_hash, method_type, card_last4) "
					+ "VALUES (?, ?, ?, 'CARD', '1234')", NEXT_ID.getAndIncrement(), m, card);
		}
		dirty.mark(new RiskSubject(SubjectType.PAYMENT, card));

		batch.run();

		assertThat(finalScore(SubjectType.PAYMENT, card)).isEqualByComparingTo("20.00"); // LINKED_ACCOUNTS HIGH
	}

	@Test
	void 예약_이벤트는_Oracle에서_읽은_CI를_대상으로_표시한다() {
		String ci = hash();
		long m = member(ci, Instant.now().minus(30, ChronoUnit.DAYS));
		long r = confirmedReservation(m, ci, 1);
		String other = hash();
		String value = """
				{"eventId":"%s","eventType":"RESERVATION_CONFIRMED","aggregateType":"RESERVATION","aggregateId":"%d",
				 "seq":1,"occurredAt":"2026-11-17T10:00:00","payload":{"ciHash":"%s"}}"""
				.formatted(UUID.randomUUID(), r, other);

		listener.consume(List.of(
				new ConsumerRecord<>("jejari.reservation", 0, 0, String.valueOf(r), value),
				new ConsumerRecord<>("jejari.reservation", 0, 1, "x", "{깨진 메시지"),
				new ConsumerRecord<>("jejari.reservation", 0, 2, "x", value.replace("RESERVATION_CONFIRMED", "SEAT_HELD"))));

		assertThat(redis.opsForSet().members(RiskDirtyQueue.DIRTY_KEY)).containsExactly("CI:" + ci); // payload 해시는 무시
	}

	// ------------------------------------------------------------ 감쇠·관리자 판정

	@Test
	void 조용해진_대상도_하루가_지나면_감쇠가_반영된다() {
		String ci = hash();
		member(ci, Instant.now().minus(60, ChronoUnit.DAYS));
		insertScore(SubjectType.CI, ci, "80.00", Instant.now().minus(30, ChronoUnit.DAYS), "NONE");
		redis.opsForValue().set(RiskScoreCache.key(SubjectType.CI, ci), "80.00");

		RiskScoreBatch.Result r = batch.run(); // 표시가 없어도 감쇠 갱신 대상으로 잡힌다

		assertThat(r.written()).isGreaterThanOrEqualTo(1);
		assertThat(finalScore(SubjectType.CI, ci)).isBetween(new BigDecimal("39.5"), new BigDecimal("40.5"));
		assertThat(redis.opsForValue().get(RiskScoreCache.key(SubjectType.CI, ci))).startsWith("40");
		assertThat(jdbc.queryForObject("SELECT review_status FROM risk_score WHERE subject_hash = ?", String.class, ci))
				.isEqualTo("NONE");
	}

	@Test
	void 관리자가_CLEAR한_대상은_CLEAR_이전_증거를_다시_세지_않는다() {
		String ci = hash();
		long m = member(ci, Instant.now().minus(30, ChronoUnit.DAYS));
		Instant clearedAt = Instant.now().minusSeconds(60);
		insertScore(SubjectType.CI, ci, "0", clearedAt, "CLEARED");
		jdbc.update("INSERT INTO admin_user (admin_id, login_id, password_hash, role, status) "
				+ "VALUES (?, ?, 'x', 'SUPERVISOR', 'ACTIVE')", ADMIN_ID, "risk-batch-test");
		jdbc.update("INSERT INTO admin_action (action_id, admin_id, subject_type, subject_hash, action_type, acted_at) "
				+ "VALUES (?, ?, 'CI', ?, 'CLEAR', ?)", NEXT_ID.getAndIncrement(), ADMIN_ID, ci, Timestamp.from(clearedAt));
		// CLEAR 이전의 빠른 클릭 30회
		List<Document> docs = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			docs.add(event("VIEW_SEAT", m, ci, null, clearedAt.minusSeconds(120).plusMillis(100L * i), 100, 200, null));
		}
		mongo.getCollection("behavior_log").insertMany(docs);
		dirty.mark(new RiskSubject(SubjectType.CI, ci));

		RiskScoreBatch.Result r = batch.run();

		assertThat(r.failed()).isZero();
		assertThat(finalScore(SubjectType.CI, ci)).isEqualByComparingTo("0");
		assertThat(redis.hasKey(RiskScoreCache.key(SubjectType.CI, ci))).isFalse();
	}

	// ------------------------------------------------------------ 락·목록

	@Test
	void 다른_인스턴스가_돌고_있으면_건너뛴다() {
		redis.opsForValue().set(RiskScoreBatch.LOCK_KEY, "other");
		dirty.mark(new RiskSubject(SubjectType.CI, hash()));

		RiskScoreBatch.Result r = batch.run();

		assertThat(r.pending()).isEqualTo(-1);
		assertThat(redis.opsForValue().get(RiskScoreBatch.LOCK_KEY)).isEqualTo("other"); // 남의 락은 풀지 않는다
		assertThat(dirty.pending()).isEqualTo(1);
	}

	@Test
	void 처리_중에_죽어_남은_목록은_다음_배치가_이어서_한다() {
		String ci = hash();
		member(ci, Instant.now().minus(30, ChronoUnit.DAYS));
		redis.opsForSet().add(RiskDirtyQueue.WORK_KEY, "CI:" + ci, "CI:not-a-hash");
		redis.opsForSet().add(RiskDirtyQueue.DIRTY_KEY, "DEVICE:" + hash());

		RiskScoreBatch.Result r = batch.run();

		assertThat(r.scored()).isEqualTo(2); // work의 CI 1건 + 이어서 넘겨받은 DEVICE 1건, 깨진 원소는 버림
		assertThat(redis.hasKey(RiskDirtyQueue.WORK_KEY)).isFalse();
		assertThat(redis.hasKey(RiskDirtyQueue.DIRTY_KEY)).isFalse();
	}

	// ------------------------------------------------------------ 도우미

	String hash() {
		byte[] b = new byte[32];
		new SecureRandom().nextBytes(b);
		String h = HexFormat.of().formatHex(b);
		hashes.add(h);
		return h;
	}

	long member(String ci, Instant createdAt) {
		long id = NEXT_ID.getAndIncrement();
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status, created_at) VALUES (?, ?, ?, '테스트', 'ACTIVE', ?)",
				id, "risk" + id + "@infra-test.jejari.dev", ci, Timestamp.from(createdAt));
		members.add(id);
		return id;
	}

	long confirmedReservation(long memberId, String ci, int seats) {
		long id = jdbc.queryForObject("SELECT reservation_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO reservation (reservation_id, member_id, ci_hash, run_id, from_station_id, to_station_id,
				  seat_count, total_amount, status, channel, source, held_at, pay_deadline_at, confirmed_at,
				  idempotency_key, version)
				VALUES (?, ?, ?, 9001, '3900023', '3900114', ?, 59800, 'CONFIRMED', 'WEB', 'NORMAL',
				  SYSTIMESTAMP - INTERVAL '20' MINUTE, SYSTIMESTAMP, SYSTIMESTAMP - INTERVAL '10' MINUTE, ?, 0)""",
				id, memberId, ci, seats, UUID.randomUUID().toString());
		return id;
	}

	void userCancel(long reservationId, long minutesAfterPurchase, long minutesBeforeDeparture) {
		jdbc.update("""
				INSERT INTO cancellation (cancel_id, reservation_id, cancel_type, canceled_at, minutes_after_purchase,
				  minutes_before_departure, refund_amount, cancel_fee, resold_yn)
				VALUES (?, ?, 'USER', SYSTIMESTAMP, ?, ?, 59000, 800, 'N')""",
				NEXT_ID.getAndIncrement() + 9_000_000_000L, reservationId, minutesAfterPurchase, minutesBeforeDeparture);
	}

	void insertScore(SubjectType type, String h, String score, Instant calculatedAt, String review) {
		jdbc.update("""
				INSERT INTO risk_score (subject_type, subject_hash, final_score, rule_score, reasons, calculated_at, review_status)
				VALUES (?, ?, ?, ?, '[]', ?, ?)""", type.name(), h, new BigDecimal(score), new BigDecimal(score),
				Timestamp.from(calculatedAt), review);
	}

	Document event(String type, long memberId, String ci, String device, Instant ts, Integer interval, int status,
			List<Long> seats) {
		Document d = new Document("event_id", UUID.randomUUID().toString()).append("member_id", memberId)
				.append("ci_hash", ci).append("device_hash", device).append("event_type", type)
				.append("ts", Date.from(ts)).append("interval_ms", interval).append("channel", "WEB")
				.append("response_status", status)
				.append("expire_at", Date.from(ts.plus(1, ChronoUnit.DAYS)));
		if (seats != null) {
			d.append("meta", new Document("seatIds", seats));
		}
		return d;
	}

	BigDecimal finalScore(SubjectType type, String h) {
		return jdbc.queryForObject("SELECT final_score FROM risk_score WHERE subject_type = ? AND subject_hash = ?",
				BigDecimal.class, type.name(), h);
	}

	int rowCount(String h) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM risk_score WHERE subject_hash = ?", Integer.class, h);
	}

	int historyCount(String h) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM risk_score_history WHERE subject_hash = ?", Integer.class, h);
	}

}
