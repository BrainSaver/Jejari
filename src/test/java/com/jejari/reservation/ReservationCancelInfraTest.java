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
import com.jejari.auth.AdminRole;
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
 * ADM-07 관리자 강제 취소({@code POST /admin/reservations/{id}/cancel})도 같은 트랜잭션이라 여기서 본다.
 */
@FullStackInfraTest
class ReservationCancelInfraTest {

	static final long RUN = FullStackInfraTest.ON_SALE_RUN;
	static final String FROM = "3900023";
	static final String TO = "3900114";
	static final long MEMBER_BASE = 930_000;
	static final AtomicLong NEXT = new AtomicLong(MEMBER_BASE + 1);
	static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
	static final long ADMIN = 963_901;

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
	@Autowired
	RefundRetryBatch retry;
	@Autowired
	ResaleBatch resale;

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
		// admin_action.admin_id가 admin_user를 가리키는 FK라 테스트 관리자 행이 있어야 한다 (해시는 쓰지 않으므로 가짜 값)
		jdbc.update("""
				INSERT INTO admin_user (admin_id, login_id, password_hash, role, status)
				SELECT ?, 'cnl-infra-test', 'x', 'SUPERVISOR', 'ACTIVE' FROM dual
				 WHERE NOT EXISTS (SELECT 1 FROM admin_user WHERE admin_id = ?)""", ADMIN, ADMIN);
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
		for (String t : List.of("admin_action", "cancellation", "payment", "reservation_history", "saga_instance", "reservation_seat",
				"seat_inventory")) {
			jdbc.update("DELETE FROM " + t + " WHERE reservation_id IN (" + mine + ")");
		}
		jdbc.update("DELETE FROM payment_method WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
		jdbc.update("DELETE FROM reservation WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
		jdbc.update("DELETE FROM purchase_counter WHERE ci_hash LIKE 'cnl-ci-%'");
		jdbc.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
		jdbc.update("DELETE FROM admin_action WHERE admin_id = ?", ADMIN);
		jdbc.update("DELETE FROM admin_user WHERE admin_id = ?", ADMIN);
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
		// 환불 완료 이벤트 (알림 REFUNDED용). 취소 이벤트 뒤에 한 번
		assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event WHERE aggregate_id = TO_CHAR(?) ORDER BY seq",
				String.class, r)).endsWith("RESERVATION_CANCELED", "REFUND_COMPLETED");
		String refunded = one("""
				SELECT payload FROM outbox_event WHERE aggregate_id = TO_CHAR(?) AND event_type = 'REFUND_COMPLETED'""", r);
		assertThat(refunded).contains("\"refundAmount\":" + q.get("refund").asLong())
				.contains("\"refundPgTxId\":\"mock-refund-");
		// 커밋 뒤 좌석 키는 대기자 먼저 REOFFER (§8.2). seat-release 소비자가 이벤트를 바로 받아 대기자가 없으면
		// 일반 판매로 풀 수 있으므로(키 삭제) 둘 중 하나. 판매됨(SOLD)으로 남아 있으면 안 된다
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s1, 0))).isIn("REOFFER:" + r, null);
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
		assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = TO_CHAR(?) AND event_type = 'REFUND_COMPLETED'",
				r)).isOne();
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
		// 환불이 안 됐으면 환불 완료 이벤트도 없다
		assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = TO_CHAR(?) AND event_type = 'REFUND_COMPLETED'",
				r)).isZero();
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

	// ------------------------------------------------------------ ADM-07

	@Test
	void 관리자_강제_취소는_수수료_없이_전액_환불하고_누가_왜_했는지_남긴다() throws Exception {
		long m = member();
		long s1 = seatIds.get(15);
		long r = confirmed(m, true, s1, seatIds.get(16));
		long paid = jdbc.queryForObject("SELECT amount FROM payment WHERE reservation_id = ?", Long.class, r);

		mvc.perform(post("/api/v1/admin/reservations/" + r + "/cancel").header("Authorization", admin(AdminRole.SUPERVISOR))
				.contentType("application/json").content("{\"memo\":\"  되팔이 확정 케이스 조치  \"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cancel.status").value("CANCELED"))
				.andExpect(jsonPath("$.cancel.seatCount").value(2))
				.andExpect(jsonPath("$.cancel.cancelFee").value(0))
				.andExpect(jsonPath("$.cancel.refundAmount").value(paid))
				.andExpect(jsonPath("$.cancel.refundStatus").value("DONE"))
				.andExpect(jsonPath("$.actionId").isNumber());

		assertThat(one("SELECT status FROM reservation WHERE reservation_id = ?", r)).isEqualTo("CANCELED");
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ?", r)).isZero();
		assertThat(counter(m)).isZero();
		Map<String, Object> c = jdbc.queryForMap("SELECT * FROM cancellation WHERE reservation_id = ?", r);
		assertThat(c.get("CANCEL_TYPE")).isEqualTo("ADMIN"); // 위험 점수는 USER만 센다
		assertThat(((Number) c.get("CANCEL_FEE")).longValue()).isZero();
		assertThat(((Number) c.get("REFUND_AMOUNT")).longValue()).isEqualTo(paid);
		assertThat(c.get("FEE_POLICY_ID")).isNull();
		assertThat(c.get("REFUND_STATUS")).isEqualTo("DONE");
		assertThat(one("SELECT status FROM payment WHERE reservation_id = ?", r)).isEqualTo("REFUNDED");
		assertThat(one("""
				SELECT from_status || '>' || to_status || ':' || actor_type FROM reservation_history
				 WHERE reservation_id = ? AND to_status = 'CANCELED'""", r)).isEqualTo("CONFIRMED>CANCELED:ADMIN");
		assertThat(one("""
				SELECT payload FROM outbox_event WHERE aggregate_id = TO_CHAR(?) AND event_type = 'RESERVATION_CANCELED'""", r))
				.contains("\"reason\":\"ADMIN\"").contains("\"cancelFee\":0").contains("\"seats\"");
		assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event WHERE aggregate_id = TO_CHAR(?) ORDER BY seq",
				String.class, r)).endsWith("RESERVATION_CANCELED", "REFUND_COMPLETED");
		// 감사 기록: 누가(admin_id)·누구에게(예약 회원의 CI)·왜(사유, 앞뒤 공백 제거)
		Map<String, Object> a = jdbc.queryForMap("SELECT * FROM admin_action WHERE reservation_id = ?", r);
		assertThat(((Number) a.get("ADMIN_ID")).longValue()).isEqualTo(ADMIN);
		assertThat(a.get("ACTION_TYPE")).isEqualTo("CANCEL_RESERVATION");
		assertThat(a.get("SUBJECT_TYPE")).isEqualTo("CI");
		assertThat(a.get("SUBJECT_HASH")).isEqualTo("cnl-ci-" + m);
		assertThat(a.get("MEMO")).isEqualTo("되팔이 확정 케이스 조치");
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s1, 0))).isIn("REOFFER:" + r, null);
	}

	@Test
	void 강제_취소는_SUPERVISOR만_사유_필수_확정_예약만_두_번_눌러도_한_번() throws Exception {
		long m = member();
		long r = confirmed(m, true, seatIds.get(25));
		String url = "/api/v1/admin/reservations/" + r + "/cancel";
		String body = "{\"memo\":\"운영 사유 강제 취소\"}";

		for (AdminRole role : new AdminRole[] { AdminRole.VIEWER, AdminRole.REVIEWER }) {
			mvc.perform(post(url).header("Authorization", admin(role)).contentType("application/json").content(body))
					.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_FORBIDDEN"));
		}
		mvc.perform(post(url).header("Authorization", bearer(m)).contentType("application/json").content(body))
				.andExpect(status().isForbidden()); // 회원 토큰으로는 관리자 경로에 못 들어온다
		mvc.perform(post(url).header("Authorization", admin(AdminRole.SUPERVISOR)).contentType("application/json")
				.content("{\"memo\":\"  짧음 \"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ACTION_REASON_REQUIRED"));
		mvc.perform(post(url).header("Authorization", admin(AdminRole.SUPERVISOR)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ACTION_REASON_REQUIRED"));
		// 거절된 요청은 아무것도 바꾸지 않는다
		assertThat(one("SELECT status FROM reservation WHERE reservation_id = ?", r)).isEqualTo("CONFIRMED");
		assertThat(count("SELECT COUNT(*) FROM admin_action WHERE reservation_id = ?", r)).isZero();

		mvc.perform(post(url).header("Authorization", admin(AdminRole.SUPERVISOR)).contentType("application/json").content(body))
				.andExpect(status().isOk());
		mvc.perform(post(url).header("Authorization", admin(AdminRole.SUPERVISOR)).contentType("application/json").content(body))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_NOT_CONFIRMED"));
		assertThat(count("SELECT COUNT(*) FROM cancellation WHERE reservation_id = ?", r)).isOne();
		assertThat(count("SELECT COUNT(*) FROM admin_action WHERE reservation_id = ?", r)).isOne();

		long held = hold.hold(m, cmd(seatIds.get(26))).reservationId();
		mvc.perform(post("/api/v1/admin/reservations/" + held + "/cancel").header("Authorization", admin(AdminRole.SUPERVISOR))
				.contentType("application/json").content(body))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_NOT_CONFIRMED"));
		mvc.perform(post("/api/v1/admin/reservations/999999999999/cancel").header("Authorization", admin(AdminRole.SUPERVISOR))
				.contentType("application/json").content(body))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
	}

	// ------------------------------------------------------------ CNL-03

	@Test
	void 환불_실패는_재시도_배치가_다시_요청해_DONE으로_바꾸고_알림은_한_번() throws Exception {
		long m = member();
		long r = confirmed(m, false, seatIds.get(32)); // 승인 거래 ID가 없어 처음 환불은 실패
		mvc.perform(post("/api/v1/reservations/" + r + "/cancel").header("Authorization", bearer(m)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.refundStatus").value("FAILED"));
		long cancelId = jdbc.queryForObject("SELECT cancel_id FROM cancellation WHERE reservation_id = ?", Long.class, r);

		// PG가 다시 받아 줄 수 있게 됨 (승인 거래 ID 확인). 막 실패한 건은 유예(1분) 안이라 건드리지 않는다
		jdbc.update("UPDATE payment SET pg_tx_id = ? WHERE reservation_id = ?", "mock-" + UUID.randomUUID(), r);
		retry.run();
		assertThat(one("SELECT refund_status FROM cancellation WHERE cancel_id = ?", cancelId)).isEqualTo("FAILED");

		backdate(cancelId, 2, 2);
		retry.run();
		assertThat(one("SELECT refund_status FROM cancellation WHERE cancel_id = ?", cancelId)).isEqualTo("DONE");
		assertThat(one("SELECT refund_pg_tx_id FROM cancellation WHERE cancel_id = ?", cancelId)).startsWith("mock-refund-");
		assertThat(one("SELECT status FROM payment WHERE reservation_id = ?", r)).isEqualTo("REFUNDED");

		retry.run(); // 끝난 건은 다시 건드리지 않는다
		assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = TO_CHAR(?) AND event_type = 'REFUND_COMPLETED'",
				r)).isOne();
	}

	@Test
	void 늦은_승인_환불도_같은_배치가_재시도하고_오래된_건은_사람에게_넘긴다() throws Exception {
		long m = member();
		long r = confirmed(m, true, seatIds.get(33));
		long late = lateApprovalFailed(r);
		backdate(late, 2, 2);
		long oldR = confirmed(m, false, seatIds.get(37)); // 34는 로컬 시연 데이터가 쓰는 경우가 있어 피함
		mvc.perform(post("/api/v1/reservations/" + oldR + "/cancel").header("Authorization", bearer(m)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.refundStatus").value("FAILED"));
		long old = jdbc.queryForObject("SELECT cancel_id FROM cancellation WHERE reservation_id = ?", Long.class, oldR);
		jdbc.update("UPDATE payment SET pg_tx_id = ? WHERE reservation_id = ?", "mock-" + UUID.randomUUID(), oldR);
		backdate(old, 73 * 60, 73 * 60); // 포기 시간(72시간) 지남

		RefundRetryBatch.Result res = retry.run();

		assertThat(one("SELECT refund_status FROM cancellation WHERE cancel_id = ?", late)).isEqualTo("DONE");
		assertThat(one("SELECT status FROM payment WHERE reservation_id = ?", r)).isEqualTo("REFUNDED");
		assertThat(one("SELECT refund_status FROM cancellation WHERE cancel_id = ?", old)).isEqualTo("FAILED"); // 건드리지 않음
		assertThat(res.givenUp()).isGreaterThanOrEqualTo(1);
	}

	// ------------------------------------------------------------ CNL-04

	@Test
	void 출발한_뒤에만_다른_사람이_산_좌석의_취소를_재판매로_기록한다() throws Exception {
		long a = member();
		long b = member();
		long resoldSeat = seatIds.get(38);
		long r1 = confirmed(a, true, resoldSeat);
		cancel(a, r1);
		redis.delete(SeatKeys.hold(RUN, resoldSeat, 0)); // 대기자 우선(REOFFER) 키를 치워 일반 판매로
		long r2 = confirmed(b, true, resoldSeat); // 다른 사람이 같은 좌석을 삼
		long r3 = confirmed(a, true, seatIds.get(39));
		cancel(a, r3); // 아무도 안 삼

		resale.run(); // 출발 전: 재판매는 계속 바뀌므로 N 그대로
		assertThat(resold(r1)).isEqualTo("N");

		String before = one("SELECT status FROM train_run WHERE run_id = ?", RUN);
		jdbc.update("UPDATE train_run SET status = 'DEPARTED' WHERE run_id = ?", RUN);
		try {
			resale.run();
			assertThat(resold(r1)).isEqualTo("Y");
			assertThat(resold(r3)).isEqualTo("N");
			assertThat(one("SELECT status FROM reservation WHERE reservation_id = ?", r2)).isEqualTo("CONFIRMED");
			resale.run(); // 다시 돌려도 같다
			assertThat(resold(r1)).isEqualTo("Y");
			assertThat(count("SELECT COUNT(*) FROM cancellation WHERE reservation_id IN (?, ?) AND resold_yn = 'Y'", r1, r3))
					.isOne();
		} finally {
			jdbc.update("UPDATE train_run SET status = ? WHERE run_id = ?", before, RUN);
		}
	}

	// ------------------------------------------------------------ 도우미

	void cancel(long m, long r) throws Exception {
		mvc.perform(post("/api/v1/reservations/" + r + "/cancel").header("Authorization", bearer(m)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
	}

	String resold(long r) {
		return one("SELECT resold_yn FROM cancellation WHERE reservation_id = ?", r);
	}

	/** 재시도 배치의 유예·포기 시간을 시험하려고 시각을 과거로 돌린다 */
	void backdate(long cancelId, long updatedMinutesAgo, long canceledMinutesAgo) {
		jdbc.update("""
				UPDATE cancellation SET updated_at = SYSTIMESTAMP - NUMTODSINTERVAL(?, 'MINUTE'),
				       canceled_at = SYSTIMESTAMP - NUMTODSINTERVAL(?, 'MINUTE')
				 WHERE cancel_id = ?""", updatedMinutesAgo, canceledMinutesAgo, cancelId);
	}

	/** §7.3 늦은 승인 환불이 PG 오류로 FAILED가 된 상태 (확정 예약을 만료된 것처럼 두고 PAY_FAILED 취소를 남김) */
	long lateApprovalFailed(long r) {
		jdbc.update("UPDATE reservation SET status = 'EXPIRED', updated_at = SYSTIMESTAMP WHERE reservation_id = ?", r);
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id = ?", r);
		long amount = jdbc.queryForObject("SELECT amount FROM payment WHERE reservation_id = ?", Long.class, r);
		long id = jdbc.queryForObject("SELECT cancellation_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO cancellation (cancel_id, reservation_id, cancel_type, canceled_at, refund_amount, cancel_fee,
				  refund_status, resold_yn)
				VALUES (?, ?, 'PAY_FAILED', SYSTIMESTAMP, ?, 0, 'FAILED', 'N')""", id, r, amount);
		return id;
	}


	String admin(AdminRole role) {
		return "Bearer " + tokens.issue(ActorType.ADMIN, ADMIN, UUID.randomUUID().toString(), role).value();
	}

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
