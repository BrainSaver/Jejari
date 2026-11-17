package com.jejari.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.auth.ActorType;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.seat.SeatHoldService;
import com.jejari.seat.SeatHoldService.HoldCommand;
import com.jejari.seat.SeatHoldService.SeatRequest;
import com.jejari.seat.SeatKeys;
import com.jejari.seat.SeatPaymentService;
import com.jejari.support.FullStackInfraTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * CNL-01 수수료 미리보기·CNL-02 확정 취소를 실제 흐름으로 확인한다: 좌석 선점(B) → 결제 시작·승인(좌석 단계 B) → 확정 →
 * {@code POST /reservations/{id}/cancel}. 좌석 반환·카운터·REOFFER·cancellation·이력·outbox·환불까지 본다.
 */
@FullStackInfraTest
class ReservationCancelInfraTest {

	static final long RUN = FullStackInfraTest.ON_SALE_RUN;
	static final String FROM = "3900023";
	static final String TO = "3900114";
	static final long MEMBER_BASE = 930_000;
	static final AtomicLong NEXT = new AtomicLong(MEMBER_BASE + 1);
	static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	TransactionTemplate tx;
	@Autowired
	SeatHoldService hold;
	@Autowired
	SeatPaymentService seatPay;
	@Autowired
	AccessTokenIssuer tokens;
	@Autowired
	JsonMapper json;

	List<Long> seatIds;
	final List<Long> policies = new ArrayList<>();

	@BeforeEach
	void setUp() {
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		seatIds = jdbc.queryForList("SELECT seat_id FROM seat WHERE train_id = 1 ORDER BY seat_id DESC", Long.class)
				.subList(300, 340);
		Integer standing = jdbc.queryForObject("SELECT COUNT(*) FROM cancel_fee_policy WHERE sale_event_id IS NULL",
				Integer.class);
		if (standing == 0) {
			policy(4320L, null, "0", "N");
			policy(1440L, 4320L, "0.05", "N");
			policy(180L, 1440L, "0.1", "N");
			policy(0L, 180L, "0.2", "N");
			policy(null, null, "0.4", "Y");
		}
	}

	@AfterEach
	void tearDown() {
		String mine = "SELECT reservation_id FROM reservation WHERE member_id BETWEEN " + (MEMBER_BASE + 1) + " AND "
				+ (MEMBER_BASE + 9999);
		jdbc.update("DELETE FROM outbox_event WHERE aggregate_type = 'RESERVATION' AND aggregate_id IN (SELECT TO_CHAR(reservation_id) FROM reservation WHERE member_id BETWEEN ? AND ?)",
				MEMBER_BASE + 1, MEMBER_BASE + 9999);
		for (String t : List.of("cancellation", "payment", "reservation_history", "saga_instance", "reservation_seat",
				"seat_inventory")) {
			jdbc.update("DELETE FROM " + t + " WHERE reservation_id IN (" + mine + ")");
		}
		jdbc.update("DELETE FROM payment_method WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
		jdbc.update("DELETE FROM reservation WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
		jdbc.update("DELETE FROM purchase_counter WHERE ci_hash LIKE 'cnl-ci-%'");
		jdbc.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
		for (long p : policies) {
			jdbc.update("DELETE FROM cancel_fee_policy WHERE fee_policy_id = ?", p);
		}
	}

	// ------------------------------------------------------------ CNL-01

	@Test
	void 미리보기는_확정_예약만_남의_예약은_404() throws Exception {
		long m = member();
		long r = confirmed(m, true, seatIds.get(0), seatIds.get(1));
		JsonNode q = json.readTree(mvc.perform(get("/api/v1/reservations/" + r + "/cancel-quote")
				.header("Authorization", bearer(m))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		long paid = q.get("paidAmount").asLong();
		BigDecimal rate = new BigDecimal(q.get("feeRate").asString());
		assertThat(q.get("refund").asLong()).isEqualTo(paid - q.get("fee").asLong());
		assertThat(q.get("fee").asLong()).isLessThanOrEqualTo(new BigDecimal(paid).multiply(rate).longValue());

		long held = hold.hold(m, cmd(seatIds.get(5))).reservationId();
		mvc.perform(get("/api/v1/reservations/" + held + "/cancel-quote").header("Authorization", bearer(m)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_NOT_CONFIRMED"));
		mvc.perform(get("/api/v1/reservations/" + r + "/cancel-quote").header("Authorization", bearer(member())))
				.andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------ CNL-02

	@Test
	void 확정_취소는_좌석을_돌려주고_수수료를_기록하고_환불한다() throws Exception {
		long m = member();
		long s1 = seatIds.get(10);
		long s2 = seatIds.get(11);
		long r = confirmed(m, true, s1, s2);
		JsonNode q = json.readTree(mvc.perform(get("/api/v1/reservations/" + r + "/cancel-quote")
				.header("Authorization", bearer(m))).andReturn().getResponse().getContentAsString());
		assertThat(counter(m)).isEqualTo(2);

		mvc.perform(post("/api/v1/reservations/" + r + "/cancel").header("Authorization", bearer(m)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELED"))
				.andExpect(jsonPath("$.kind").value("CONFIRMED"))
				.andExpect(jsonPath("$.seatCount").value(2))
				.andExpect(jsonPath("$.cancelFee").value(q.get("fee").asLong()))
				.andExpect(jsonPath("$.refundAmount").value(q.get("refund").asLong()))
				.andExpect(jsonPath("$.refundStatus").value("DONE"));

		assertThat(one("SELECT status FROM reservation WHERE reservation_id = ?", r)).isEqualTo("CANCELED");
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ?", r)).isZero();
		assertThat(counter(m)).isZero(); // §8.1-3 확정 예약도 카운터 감소
		Map<String, Object> c = jdbc.queryForMap("SELECT * FROM cancellation WHERE reservation_id = ?", r);
		assertThat(c.get("CANCEL_TYPE")).isEqualTo("USER");
		assertThat(c.get("REFUND_STATUS")).isEqualTo("DONE");
		assertThat(c.get("REFUND_PG_TX_ID")).asString().startsWith("mock-refund-");
		assertThat(((Number) c.get("CANCEL_FEE")).longValue()).isEqualTo(q.get("fee").asLong());
		assertThat(c.get("FEE_POLICY_ID")).isNotNull();
		assertThat(c.get("MINUTES_BEFORE_DEPARTURE")).isNotNull();
		assertThat(((Number) c.get("MINUTES_AFTER_PURCHASE")).longValue()).isBetween(0L, 1L);
		assertThat(c.get("RESOLD_YN")).isEqualTo("N");
		assertThat(one("SELECT status FROM payment WHERE reservation_id = ?", r)).isEqualTo("REFUNDED");
		assertThat(one("""
				SELECT from_status || '>' || to_status || ':' || actor_type FROM reservation_history
				 WHERE reservation_id = ? AND to_status = 'CANCELED'""", r)).isEqualTo("CONFIRMED>CANCELED:USER");
		String payload = one("""
				SELECT payload FROM outbox_event WHERE aggregate_id = TO_CHAR(?) AND event_type = 'RESERVATION_CANCELED'""", r);
		assertThat(payload).contains("\"reason\":\"USER\"").contains("\"cancelFee\"").contains("\"seats\"");
		// 커밋 뒤 좌석 키는 대기자 먼저 (§8.2)
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s1, 0))).isEqualTo("REOFFER:" + r);
	}

	@Test
	void 동시에_두_번_취소해도_취소_기록과_환불은_한_번() throws Exception {
		long m = member();
		long r = confirmed(m, true, seatIds.get(20));
		ExecutorService pool = Executors.newFixedThreadPool(3);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> fs = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			fs.add(pool.submit((Callable<Integer>) () -> {
				go.await();
				return mvc.perform(post("/api/v1/reservations/" + r + "/cancel").header("Authorization", bearer(m)))
						.andReturn().getResponse().getStatus();
			}));
		}
		go.countDown();
		List<Integer> codes = new ArrayList<>();
		for (Future<Integer> f : fs) {
			codes.add(f.get());
		}
		pool.shutdown();
		assertThat(codes).containsOnlyOnce(200);
		assertThat(count("SELECT COUNT(*) FROM cancellation WHERE reservation_id = ?", r)).isOne();
		assertThat(counter(m)).isZero(); // 두 번 줄지 않음
	}

	@Test
	void 승인_거래가_없으면_취소는_되고_환불은_FAILED로_남는다() throws Exception {
		long m = member();
		long r = confirmed(m, false, seatIds.get(30));
		mvc.perform(post("/api/v1/reservations/" + r + "/cancel").header("Authorization", bearer(m)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.refundStatus").value("FAILED"));
		assertThat(one("SELECT refund_status FROM cancellation WHERE reservation_id = ?", r)).isEqualTo("FAILED");
		assertThat(one("SELECT status FROM payment WHERE reservation_id = ?", r)).isEqualTo("APPROVED");
		assertThat(one("SELECT status FROM reservation WHERE reservation_id = ?", r)).isEqualTo("CANCELED");
	}

	@Test
	void 결제_전_예약은_같은_경로로_수수료_없이_취소된다() throws Exception {
		long m = member();
		long r = hold.hold(m, cmd(seatIds.get(35))).reservationId();
		mvc.perform(post("/api/v1/reservations/" + r + "/cancel").header("Authorization", bearer(m)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("BEFORE_PAY"))
				.andExpect(jsonPath("$.cancelFee").value(0));
		assertThat(count("SELECT COUNT(*) FROM cancellation WHERE reservation_id = ?", r)).isZero();
	}

	// ------------------------------------------------------------ 도우미

	/** 선점 → 결제 시작 → 승인(좌석 확정) → CONFIRMED. withPgTx면 승인 결제 행에 PG 거래 ID를 넣는다 */
	long confirmed(long m, boolean withPgTx, long... seats) {
		long r = hold.hold(m, cmd(seats)).reservationId();
		LocalDateTime d = LocalDateTime.now(ZONE).plusMinutes(20);
		assertThat(Boolean.TRUE.equals(tx.execute(st -> {
			jdbc.update("""
					UPDATE reservation SET status = 'PAYMENT_PENDING', pay_deadline_at = ?, updated_at = SYSTIMESTAMP,
					       version = version + 1 WHERE reservation_id = ? AND status = 'HOLD'""", d, r);
			return seatPay.extendHoldInCurrentTx(r, d);
		}))).isTrue();
		assertThat(Boolean.TRUE.equals(tx.execute(st -> {
			jdbc.queryForObject("SELECT status FROM reservation WHERE reservation_id = ? FOR UPDATE", String.class, r);
			if (!seatPay.confirmSeatsInCurrentTx(r)) {
				st.setRollbackOnly();
				return false;
			}
			jdbc.update("""
					UPDATE reservation SET status = 'CONFIRMED', confirmed_at = SYSTIMESTAMP, updated_at = SYSTIMESTAMP,
					       version = version + 1 WHERE reservation_id = ? AND status = 'PAYMENT_PENDING'""", r);
			return true;
		}))).isTrue();
		long amount = jdbc.queryForObject("SELECT total_amount FROM reservation WHERE reservation_id = ?", Long.class, r);
		long method = jdbc.queryForObject("SELECT payment_method_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("INSERT INTO payment_method (method_id, member_id, method_hash, method_type, card_last4) VALUES (?, ?, ?, 'CARD', '4242')",
				method, m, "cnl-m-" + method);
		jdbc.update("""
				INSERT INTO payment (payment_id, reservation_id, method_id, amount, status, pg_tx_id, idempotency_key,
				  requested_at, approved_at)
				VALUES (payment_seq.NEXTVAL, ?, ?, ?, 'APPROVED', ?, ?, SYSTIMESTAMP, SYSTIMESTAMP)""",
				r, method, amount, withPgTx ? "mock-" + UUID.randomUUID() : null, UUID.randomUUID().toString());
		return r;
	}

	static HoldCommand cmd(long... seats) {
		List<SeatRequest> list = new ArrayList<>();
		for (long s : seats) {
			list.add(new SeatRequest(s, "ADULT"));
		}
		return new HoldCommand(RUN, FROM, TO, list, "WEB", UUID.randomUUID().toString());
	}

	long member() {
		long id = NEXT.getAndIncrement();
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, '테스트', 'ACTIVE')",
				id, "cnl" + id + "@infra-test.jejari.dev", "cnl-ci-" + id);
		return id;
	}

	String bearer(long memberId) {
		return "Bearer " + tokens.issue(ActorType.MEMBER, memberId, UUID.randomUUID().toString()).value();
	}

	long counter(long m) {
		return jdbc.queryForObject("SELECT NVL(SUM(seat_count), 0) FROM purchase_counter WHERE scope = 'PER_RUN' AND ci_hash = ?",
				Long.class, "cnl-ci-" + m);
	}

	String one(String sql, Object... args) {
		return jdbc.queryForObject(sql, String.class, args);
	}

	long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	void policy(Long min, Long max, String rate, String after) {
		long id = NEXT.getAndIncrement() + 8_000_000;
		jdbc.update("""
				INSERT INTO cancel_fee_policy (fee_policy_id, sale_event_id, min_minutes_before, max_minutes_before, fee_rate, after_departure)
				VALUES (?, NULL, ?, ?, ?, ?)""", id, min, max, new BigDecimal(rate), after);
		policies.add(id);
	}
}
