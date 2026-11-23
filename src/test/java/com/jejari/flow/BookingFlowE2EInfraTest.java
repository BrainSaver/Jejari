package com.jejari.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jejari.queue.api.QueueSeatTokenInterceptor;
import com.jejari.queue.redis.QueueKeys;
import com.jejari.queue.domain.QueueAdmitter;
import com.jejari.seat.SeatKeys;
import com.jejari.support.FullStackInfraTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * TEST-02 예매 전체 흐름. 사용자가 화면에서 하는 순서 그대로 <b>HTTP API만</b> 불러 다섯 파트가 이어지는지 본다.
 *
 * <pre>
 * 본인인증 → 가입 → 로그인 (D)
 *   → 대기열 진입 → 입장 → 입장 토큰 (A)
 *   → 좌석 배치도 → 좌석 선점 (B)
 *   → 결제 → 확정 (C)
 *   → 예매 내역 → 수수료 미리보기 → 취소 → 환불 (D)
 * 그리고 DB 흔적: 상태 이력, outbox 이벤트 순서, 매수 카운터, 좌석 키, 행동 로그(Kafka → MongoDB)
 * </pre>
 * 각 파트의 세부 규칙은 파트별 테스트가 본다. 여기서는 "이어 붙였을 때 끊기는 곳이 없는가"만 본다.
 */
@FullStackInfraTest
class BookingFlowE2EInfraTest {

	static final long RUN = FullStackInfraTest.ON_SALE_RUN;
	static final String FROM = "3900023";
	static final String TO = "3900114";
	static final String PW = "Green-Otter-73";
	static final String CARD_OK = "4111111111111111"; // Mock PG: 승인되는 카드
	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	MongoTemplate mongo;
	@Autowired
	QueueAdmitter admitter;

	String email;

	@BeforeEach
	void setUp() {
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		redis.opsForValue().set(QueueKeys.RISK_SYNCED_AT, "test"); // 위험 점수 캐시 준비됨
		email = "flow" + UUID.randomUUID().toString().substring(0, 8) + "@infra-test.jejari.dev";
	}

	@AfterEach
	void cleanUp() {
		for (Long m : jdbc.queryForList("SELECT member_id FROM member WHERE email = ?", Long.class, email)) {
			String mine = "SELECT reservation_id FROM reservation WHERE member_id = " + m;
			jdbc.update("DELETE FROM outbox_event WHERE aggregate_type = 'RESERVATION' AND aggregate_id IN (SELECT TO_CHAR(reservation_id) FROM reservation WHERE member_id = ?)", m);
			for (String t : List.of("cancellation", "payment", "reservation_history", "saga_instance", "reservation_seat",
					"seat_inventory")) {
				jdbc.update("DELETE FROM " + t + " WHERE reservation_id IN (" + mine + ")");
			}
			String ci = jdbc.queryForObject("SELECT ci_hash FROM member WHERE member_id = ?", String.class, m);
			jdbc.update("DELETE FROM payment_method WHERE member_id = ?", m);
			jdbc.update("DELETE FROM reservation WHERE member_id = ?", m);
			jdbc.update("DELETE FROM purchase_counter WHERE ci_hash = ?", ci);
			for (String t : List.of("auth_session", "identity_verification", "member_credential", "member_device")) {
				jdbc.update("DELETE FROM " + t + " WHERE member_id = ?", m);
			}
			jdbc.update("DELETE FROM member WHERE member_id = ?", m);
			mongo.getCollection("behavior_log").deleteMany(new Document("member_id", m));
		}
	}

	@Test
	void 가입부터_줄서기_좌석_결제_취소_환불까지_API만으로_이어진다() throws Exception {
		// 1. 본인인증 → 가입 → 로그인 (D)
		String verify = json(call(post("/api/v1/auth/verify"), Map.of("name", "김흐름", "phone", "01077778888",
				"provider", "MOCK_PASS"), null, 200)).get("verificationToken").asText();
		call(post("/api/v1/auth/signup"), Map.of("verificationToken", verify, "email", email, "password", PW), null, 201);
		String access = json(call(post("/api/v1/auth/login"), Map.of("email", email, "password", PW), null, 200))
				.get("accessToken").asText();
		long m = jdbc.queryForObject("SELECT member_id FROM member WHERE email = ?", Long.class, email);

		// 2. 대기열 진입 → 입장 → 입장 토큰 (A). 입장 스케줄러는 테스트에서 꺼져 있어 직접 한 번 돌린다
		call(post("/api/v1/runs/" + RUN + "/queue"), null, access, 200);
		admitter.admit(RUN);
		JsonNode me = json(call(get("/api/v1/runs/" + RUN + "/queue/me"), null, access, 200));
		assertThat(me.get("state").asText()).isEqualTo("ADMITTED");
		String pass = me.get("passToken").asText();

		// 3. 좌석 배치도 → 좌석 선점 (B). 입장 토큰이 있어야 열린다
		assertThat(mvc.perform(get("/api/v1/runs/" + RUN + "/seats").queryParam("from", FROM).queryParam("to", TO)
				.header("Authorization", "Bearer " + access)).andReturn().getResponse().getStatus())
				.as("입장 토큰 없이 좌석 배치도").isEqualTo(403);
		assertThat(mvc.perform(get("/api/v1/runs/" + RUN + "/seats").queryParam("from", FROM).queryParam("to", TO)
				.header("Authorization", "Bearer " + access).header(QueueSeatTokenInterceptor.HEADER, pass))
				.andReturn().getResponse().getStatus()).isEqualTo(200);
		long seat = freeSeat();
		MockHttpServletRequestBuilder hold = post("/api/v1/reservations")
				.header(QueueSeatTokenInterceptor.HEADER, pass).header("Idempotency-Key", UUID.randomUUID().toString());
		JsonNode held = json(call(hold, Map.of("runId", RUN, "fromStationId", FROM, "toStationId", TO,
				"seats", List.of(Map.of("seatId", seat, "passengerType", "ADULT")), "channel", "WEB"), access, 201));
		long r = held.get("reservationId").asLong();
		assertThat(held.get("status").asText()).isEqualTo("HOLD");

		// 4. 결제 → 확정 (C)
		JsonNode paid = json(call(post("/api/v1/reservations/" + r + "/payments")
				.header("Idempotency-Key", UUID.randomUUID().toString()), Map.of("cardNumber", CARD_OK), access, 201));
		assertThat(paid.get("paymentStatus").asText()).isEqualTo("APPROVED");
		assertThat(paid.get("reservationStatus").asText()).isEqualTo("CONFIRMED");

		// 5. 예매 내역 → 미리보기 → 취소 → 환불 (D)
		JsonNode list = json(call(get("/api/v1/reservations"), null, access, 200));
		assertThat(list.get("items").get(0).get("reservationId").asLong()).isEqualTo(r);
		assertThat(list.get("items").get(0).get("status").asText()).isEqualTo("CONFIRMED");
		JsonNode quote = json(call(get("/api/v1/reservations/" + r + "/cancel-quote"), null, access, 200));
		JsonNode canceled = json(call(post("/api/v1/reservations/" + r + "/cancel"), null, access, 200));
		assertThat(canceled.get("status").asText()).isEqualTo("CANCELED");
		assertThat(canceled.get("kind").asText()).isEqualTo("CONFIRMED");
		assertThat(canceled.get("refundAmount").asLong()).isEqualTo(quote.get("refund").asLong());
		assertThat(canceled.get("refundStatus").asText()).isEqualTo("DONE");

		// 6. DB 흔적: 파트를 넘어가며 남긴 기록이 한 줄로 이어지는가
		assertThat(jdbc.queryForList("SELECT to_status FROM reservation_history WHERE reservation_id = ? ORDER BY history_id",
				String.class, r)).containsExactly("HOLD", "PAYMENT_PENDING", "CONFIRMED", "CANCELED");
		assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event WHERE aggregate_id = TO_CHAR(?) ORDER BY seq",
				String.class, r)).containsExactly("SEAT_HELD", "PAYMENT_STARTED", "RESERVATION_CONFIRMED",
						"RESERVATION_CANCELED", "REFUND_COMPLETED");
		assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE reservation_id = ?", String.class, r))
				.isEqualTo("REFUNDED");
		assertThat(jdbc.queryForObject("""
				SELECT NVL(SUM(seat_count), 0) FROM purchase_counter
				 WHERE scope = 'PER_RUN' AND ci_hash = (SELECT ci_hash FROM member WHERE member_id = ?)""", Long.class, m))
				.isZero(); // 샀다가 취소했으니 한도는 원래대로
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, seat, 0))).isIn("REOFFER:" + r, null); // 대기자 먼저
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ?", Long.class, r))
				.isZero();

		// 7. 행동 로그 (Kafka → MongoDB, 비동기): 선점·취소가 이 회원으로 쌓인다 → 위험 점수의 입력
		assertThat(behaviorTypes(m, 15_000)).contains("SEAT_HOLD", "CANCEL");
	}

	// ------------------------------------------------------------ 도우미

	MvcResult call(MockHttpServletRequestBuilder req, Object body, String access, int expected) throws Exception {
		if (body != null) {
			req.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
		}
		if (access != null) {
			req.header("Authorization", "Bearer " + access);
		}
		MvcResult r = mvc.perform(req).andReturn();
		if (r.getRequest().isAsyncStarted()) { // 로그인은 로그인 대기실(LoginGate)이 비동기로 처리한다
			r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(r)).andReturn();
		}
		assertThat(r.getResponse().getStatus())
				.as("%s %s → %s", r.getRequest().getMethod(), r.getRequest().getRequestURI(),
						r.getResponse().getContentAsString())
				.isEqualTo(expected);
		return r;
	}

	static JsonNode json(MvcResult r) throws Exception {
		return JSON.readTree(r.getResponse().getContentAsString());
	}

	/** 이 운행에서 아무도 안 쓰는 좌석 (다른 테스트·시연 데이터와 겹치지 않게 DB에서 고른다) */
	long freeSeat() {
		return jdbc.queryForObject("""
				SELECT seat_id FROM seat WHERE train_id = 1
				   AND seat_id NOT IN (SELECT seat_id FROM seat_inventory WHERE run_id = ?)
				 ORDER BY seat_id FETCH FIRST 1 ROWS ONLY""", Long.class, RUN);
	}

	/** 행동 로그가 MongoDB에 들어올 때까지 기다린다 (최대 waitMs) */
	List<String> behaviorTypes(long m, long waitMs) throws InterruptedException {
		long until = System.currentTimeMillis() + waitMs;
		List<String> types = List.of();
		while (System.currentTimeMillis() < until) {
			types = mongo.getCollection("behavior_log").distinct("event_type", new Document("member_id", m), String.class)
					.into(new java.util.ArrayList<>());
			if (types.contains("SEAT_HOLD") && types.contains("CANCEL")) {
				break;
			}
			Thread.sleep(300);
		}
		return types;
	}
}
