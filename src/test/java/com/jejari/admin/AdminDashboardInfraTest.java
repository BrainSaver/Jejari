package com.jejari.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jejari.auth.ActorType;
import com.jejari.auth.AdminRole;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.seat.SeatHoldService.HoldCommand;
import com.jejari.seat.SeatHoldService.SeatRequest;
import com.jejari.seat.SeatHoldService;
import com.jejari.support.NoRunningApp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADM-03 실시간 예매 현황을 실제 Oracle·Redis로 확인한다.
 * 운행 9001: 열차 1 (1호차 특실 30석 + 2~10호차 일반 55석씩), 서울(3900023) → 부산(3900114).
 */
@Tag("infra")
@ExtendWith(NoRunningApp.class)
@SpringBootTest(properties = {
		"spring.data.redis.database=15",
		"jejari.queue.scheduler-enabled=false",
		"jejari.risk.cache-resync-enabled=false",
		"jejari.risk.batch-enabled=false",
		"jejari.seat.expiry-scanner-enabled=false",
		"jejari.train.sale-open-scheduler-enabled=false"})
@AutoConfigureMockMvc
class AdminDashboardInfraTest {

	static final long RUN = 9001;
	static final String SEOUL = "3900023";
	static final String BUSAN = "3900114";
	static final long MEMBER = 960001;
	static final long ADMIN = 960901;
	static final long METHOD = 960801;
	static final String CI = "dash-ci-1";
	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	AccessTokenIssuer tokens;
	@Autowired
	SeatHoldService hold;

	int totalSeats;

	@BeforeEach
	void setUp() {
		cleanDb();
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
				MEMBER, "dash@infra-test.jejari.dev", CI, "테스트");
		totalSeats = jdbc.queryForObject("SELECT COUNT(*) FROM seat WHERE train_id = 1", Integer.class);
	}

	@AfterEach
	void tearDown() {
		cleanDb();
	}

	// ------------------------------------------------------------ 권한

	@Test
	void 로그인_없이는_못_본다() throws Exception {
		mvc.perform(get(url())).andExpect(status().isUnauthorized());
	}

	@Test
	void 회원_토큰으로는_못_본다_관리자_전용() throws Exception {
		mvc.perform(get(url()).header("Authorization", bearer(ActorType.MEMBER, MEMBER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ADMIN_FORBIDDEN"));
	}

	@Test
	void 등급이_없는_관리자_토큰은_401_권한_부족이_아니라_위조로_본다() throws Exception {
		// JwtPrincipalConverter가 adm 클레임 없는 ADMIN 토큰을 위조·손상으로 보고 토큰 자체를 거절한다.
		// 그래서 403(권한 부족)이 아니라 401이다
		mvc.perform(get(url()).header("Authorization", bearer(ActorType.ADMIN, ADMIN)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void REVIEWER_SUPERVISOR도_볼_수_있다_VIEWER가_가장_낮은_등급() throws Exception {
		for (AdminRole role : new AdminRole[] { AdminRole.REVIEWER, AdminRole.SUPERVISOR }) {
			mvc.perform(get(url()).header("Authorization", adminBearer(role)))
					.andExpect(status().isOk());
		}
	}

	@Test
	void 없는_운행은_404() throws Exception {
		mvc.perform(get("/api/v1/admin/dashboard/runs/999999").header("Authorization", adminBearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	// ------------------------------------------------------------ 좌석

	@Test
	void 아무것도_안_팔린_운행은_전부_남은_좌석() throws Exception {
		JsonNode d = dashboard();

		assertThat(d.get("runId").asLong()).isEqualTo(RUN);
		assertThat(d.get("status").asString()).isEqualTo("ON_SALE");
		assertThat(d.get("windowSec").asInt()).isEqualTo(60);
		assertThat(d.get("seats").get("total").asInt()).isEqualTo(totalSeats);
		assertThat(d.get("seats").get("sold").asInt()).isZero();
		assertThat(d.get("seats").get("held").asInt()).isZero();
		assertThat(d.get("seats").get("remaining").asInt()).isEqualTo(totalSeats);
	}

	@Test
	void 좌석을_잡으면_선점이_늘고_남은_좌석이_준다() throws Exception {
		hold.hold(MEMBER, cmd(seat(0), seat(1)));

		JsonNode seats = dashboard().get("seats");
		assertThat(seats.get("held").asInt()).isEqualTo(2);
		assertThat(seats.get("sold").asInt()).isZero();
		assertThat(seats.get("remaining").asInt()).isEqualTo(totalSeats - 2);
		// 합이 전체와 맞아야 한다 (화면이 비율을 그린다)
		assertThat(seats.get("sold").asInt() + seats.get("held").asInt() + seats.get("remaining").asInt())
				.isEqualTo(totalSeats);
	}

	@Test
	void 판매된_좌석은_sold로_센다() throws Exception {
		redis.opsForValue().set(com.jejari.seat.SeatKeys.hold(RUN, seat(2), 0), "SOLD:123");

		JsonNode seats = dashboard().get("seats");
		assertThat(seats.get("sold").asInt()).isEqualTo(1);
		assertThat(seats.get("remaining").asInt()).isEqualTo(totalSeats - 1);
	}

	// ------------------------------------------------------------ 분당 건수

	@Test
	void 방금_생긴_예약은_분당_예약_수에_들어온다() throws Exception {
		assertThat(dashboard().get("rates").get("reservations").asInt()).isZero();

		hold.hold(MEMBER, cmd(seat(3)));

		assertThat(dashboard().get("rates").get("reservations").asInt()).isEqualTo(1);
	}

	@Test
	void 창_밖의_예약은_세지_않는다() throws Exception {
		long r = hold.hold(MEMBER, cmd(seat(4))).reservationId();
		// 2분 전으로 밀면 60초 창에서 빠진다
		jdbc.update("UPDATE reservation SET created_at = SYSTIMESTAMP - INTERVAL '2' MINUTE WHERE reservation_id = ?", r);

		assertThat(dashboard().get("rates").get("reservations").asInt()).isZero();
	}

	@Test
	void 승인된_결제와_취소만_분당_수치에_들어온다() throws Exception {
		long r = hold.hold(MEMBER, cmd(seat(5))).reservationId();
		jdbc.update("INSERT INTO payment_method (method_id, member_id, method_type) VALUES (?, ?, 'CARD')", METHOD, MEMBER);
		// REQUESTED는 아직 팔린 게 아니라 세지 않는다
		jdbc.update("""
				INSERT INTO payment (payment_id, reservation_id, method_id, amount, status, idempotency_key, requested_at)
				VALUES (?, ?, ?, 1000, 'REQUESTED', ?, SYSTIMESTAMP)""", 960701L, r, METHOD, "idem-req");
		assertThat(dashboard().get("rates").get("payments").asInt()).isZero();

		jdbc.update("""
				INSERT INTO payment (payment_id, reservation_id, method_id, amount, status, idempotency_key, requested_at, approved_at)
				VALUES (?, ?, ?, 1000, 'APPROVED', ?, SYSTIMESTAMP, SYSTIMESTAMP)""", 960702L, r, METHOD, "idem-ok");
		jdbc.update("""
				INSERT INTO cancellation (cancel_id, reservation_id, cancel_type, canceled_at, refund_amount, cancel_fee, resold_yn)
				VALUES (?, ?, 'USER', SYSTIMESTAMP, 900, 100, 'N')""", 960601L, r);

		JsonNode rates = dashboard().get("rates");
		assertThat(rates.get("payments").asInt()).isEqualTo(1);
		assertThat(rates.get("cancels").asInt()).isEqualTo(1);
	}

	@Test
	void 결과를_모르는_결제_건수는_확인_시각이_지난_REQUESTED() throws Exception {
		long r = hold.hold(MEMBER, cmd(seat(6))).reservationId();
		jdbc.update("INSERT INTO payment_method (method_id, member_id, method_type) VALUES (?, ?, 'CARD')", METHOD, MEMBER);
		int before = dashboard().get("payments").get("unknown").asInt();

		// 확인 시각이 아직 안 지난 건은 "모름"이 아니다
		jdbc.update("""
				INSERT INTO payment (payment_id, reservation_id, method_id, amount, status, idempotency_key, requested_at, next_check_at)
				VALUES (?, ?, ?, 1000, 'REQUESTED', ?, SYSTIMESTAMP, SYSTIMESTAMP + INTERVAL '10' MINUTE)""",
				960703L, r, METHOD, "idem-future");
		assertThat(dashboard().get("payments").get("unknown").asInt()).isEqualTo(before);

		jdbc.update("""
				INSERT INTO payment (payment_id, reservation_id, method_id, amount, status, idempotency_key, requested_at, next_check_at)
				VALUES (?, ?, ?, 1000, 'REQUESTED', ?, SYSTIMESTAMP, SYSTIMESTAMP - INTERVAL '1' MINUTE)""",
				960704L, r, METHOD, "idem-past");
		assertThat(dashboard().get("payments").get("unknown").asInt()).isEqualTo(before + 1);
	}

	// ------------------------------------------------------------ 대기열

	@Test
	void 대기열이_비어_있으면_0이고_Redis가_없어도_죽지_않는다() throws Exception {
		JsonNode q = dashboard().get("queue");
		assertThat(q.get("waiting").asLong()).isZero();
		assertThat(q.get("active").asLong()).isZero();
		assertThat(q.get("admitPerSec").asLong()).isZero();
	}

	@Test
	void 줄_서_있는_사람과_입장_속도가_보인다() throws Exception {
		redis.opsForZSet().add("queue:" + RUN, "m1", 1);
		redis.opsForZSet().add("queue:" + RUN, "m2", 2);
		redis.opsForZSet().add("queue:active:" + RUN, "m0", 9_999_999_999L);
		redis.opsForValue().set("queue:throughput:" + RUN, "5");

		JsonNode q = dashboard().get("queue");
		assertThat(q.get("waiting").asLong()).isEqualTo(2);
		assertThat(q.get("active").asLong()).isEqualTo(1);
		assertThat(q.get("admitPerSec").asLong()).isEqualTo(5);
	}

	// ------------------------------------------------------------ 운행 목록

	@Test
	void 판매_중_운행_목록에_9001이_있다() throws Exception {
		JsonNode runs = json(mvc.perform(get("/api/v1/admin/dashboard/runs").header("Authorization", adminBearer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

		List<Long> ids = new ArrayList<>();
		for (JsonNode n : runs) {
			ids.add(n.asLong());
		}
		assertThat(ids).contains(RUN);
	}

	// ------------------------------------------------------------ 도우미

	private static String url() {
		return "/api/v1/admin/dashboard/runs/" + RUN;
	}

	private JsonNode dashboard() throws Exception {
		return json(mvc.perform(get(url()).header("Authorization", adminBearer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	/** 설계서가 정한 최소 등급 VIEWER로 본다 */
	private String adminBearer() {
		return adminBearer(AdminRole.VIEWER);
	}

	private String adminBearer(AdminRole role) {
		return "Bearer " + tokens.issue(ActorType.ADMIN, ADMIN, UUID.randomUUID().toString(), role).value();
	}

	private String bearer(ActorType type, long id) {
		return "Bearer " + tokens.issue(type, id, UUID.randomUUID().toString()).value();
	}

	/** 일반 좌석 하나. 다른 통합 테스트와 겹치지 않게 뒤쪽에서 고른다 */
	private long seat(int i) {
		return jdbc.queryForList(
				"SELECT seat_id FROM seat WHERE train_id = 1 AND seat_grade = 'GENERAL' ORDER BY seat_id DESC",
				Long.class).get(200 + i);
	}

	private static HoldCommand cmd(long... seats) {
		List<SeatRequest> list = new ArrayList<>();
		for (long s : seats) {
			list.add(new SeatRequest(s, "ADULT"));
		}
		return new HoldCommand(RUN, SEOUL, BUSAN, list, "WEB", UUID.randomUUID().toString());
	}

	private static JsonNode json(String body) {
		return JSON.readTree(body);
	}

	private void cleanDb() {
		String mine = "SELECT reservation_id FROM reservation WHERE member_id = " + MEMBER;
		jdbc.update("DELETE FROM cancellation WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM payment WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM payment_method WHERE member_id = ?", MEMBER);
		jdbc.update("DELETE FROM outbox_event WHERE aggregate_type = 'RESERVATION' AND aggregate_id IN (SELECT TO_CHAR(reservation_id) FROM reservation WHERE member_id = ?)", MEMBER);
		jdbc.update("DELETE FROM reservation_history WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM saga_instance WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation_seat WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation WHERE member_id = ?", MEMBER);
		jdbc.update("DELETE FROM purchase_counter WHERE ci_hash = ?", CI);
		jdbc.update("DELETE FROM member WHERE member_id = ?", MEMBER);
	}
}
