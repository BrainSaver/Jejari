package com.jejari.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jejari.auth.ActorType;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.support.FullStackInfraTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * RSV-04 예매 내역을 실제 Oracle로 확인한다. 예약·좌석·결제 행은 테스트가 직접 넣고 지운다.
 * 상시 수수료 정책(sale_event_id NULL)이 DB에 없으면 테스트 동안만 넣는다 (CNL-01 예시 비율).
 */
@FullStackInfraTest
class ReservationQueryInfraTest {

	static final long RUN = FullStackInfraTest.ON_SALE_RUN;
	static final String FROM = "3900023";
	static final String TO = "3900114";
	static final AtomicLong NEXT_ID = new AtomicLong(950_001);

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenIssuer tokens;
	@Autowired
	JsonMapper json;

	final List<Long> members = new ArrayList<>();
	final List<Long> policies = new ArrayList<>();
	List<Long> seatIds;
	long me;
	long other;

	@BeforeEach
	void setUp() {
		seatIds = jdbc.queryForList("""
				SELECT seat_id FROM seat WHERE train_id = (SELECT train_id FROM train_run WHERE run_id = ?)
				 ORDER BY seat_id FETCH FIRST 4 ROWS ONLY""", Long.class, RUN);
		me = member();
		other = member();
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
		for (long m : members) {
			jdbc.update("DELETE FROM payment WHERE reservation_id IN (SELECT reservation_id FROM reservation WHERE member_id = ?)", m);
			jdbc.update("DELETE FROM payment_method WHERE member_id = ?", m);
			jdbc.update("DELETE FROM reservation_seat WHERE reservation_id IN (SELECT reservation_id FROM reservation WHERE member_id = ?)", m);
			jdbc.update("DELETE FROM reservation WHERE member_id = ?", m);
			jdbc.update("DELETE FROM member WHERE member_id = ?", m);
		}
		for (long p : policies) {
			jdbc.update("DELETE FROM cancel_fee_policy WHERE fee_policy_id = ?", p);
		}
	}

	// ------------------------------------------------------------ 목록

	@Test
	void 목록은_내_예약만_최근_것부터_상태로_거르고_커서로_넘긴다() throws Exception {
		long r1 = reservation(me, "CONFIRMED", 1, 0);
		long r2 = reservation(me, "HOLD", 1, 1);
		long r3 = reservation(me, "EXPIRED", 1, 2);
		reservation(other, "CONFIRMED", 1, 3);

		JsonNode all = getJson("/api/v1/reservations", me);
		assertThat(ids(all)).containsExactly(r3, r2, r1);
		assertThat(all.get("nextBefore").isNull()).isTrue();

		assertThat(ids(getJson("/api/v1/reservations?status=confirmed,hold", me))).containsExactly(r2, r1);

		JsonNode first = getJson("/api/v1/reservations?size=2", me);
		assertThat(ids(first)).containsExactly(r3, r2);
		long next = first.get("nextBefore").asLong();
		assertThat(ids(getJson("/api/v1/reservations?size=2&before=" + next, me))).containsExactly(r1);
	}

	@Test
	void 목록의_결제_기한은_결제_전_예약에만_있다() throws Exception {
		reservation(me, "CONFIRMED", 1, 0);
		reservation(me, "HOLD", 1, 1);
		JsonNode items = getJson("/api/v1/reservations", me).get("items");
		assertThat(items.get(0).get("status").asString()).isEqualTo("HOLD");
		assertThat(items.get(0).get("payDeadlineAt").isNull()).isFalse();
		assertThat(items.get(1).get("payDeadlineAt").isNull()).isTrue();
		assertThat(items.get(1).get("from").get("name").asString()).isNotBlank();
		assertThat(items.get(1).get("trainNo").asString()).isNotBlank();
	}

	@Test
	void 잘못된_필터와_크기는_400() throws Exception {
		for (String q : List.of("?status=PAID", "?status=CONFIRMED,1=1", "?size=0", "?size=51", "?before=-1")) {
			mvc.perform(get("/api/v1/reservations" + q).header("Authorization", bearer(me)))
					.andExpect(status().isBadRequest());
		}
	}

	@Test
	void 로그인_없이는_401() throws Exception {
		mvc.perform(get("/api/v1/reservations")).andExpect(status().isUnauthorized());
	}

	// ------------------------------------------------------------ 상세

	@Test
	void 남의_예약과_없는_예약은_똑같이_404() throws Exception {
		long theirs = reservation(other, "CONFIRMED", 1, 0);
		String a = mvc.perform(get("/api/v1/reservations/" + theirs).header("Authorization", bearer(me)))
				.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		String b = mvc.perform(get("/api/v1/reservations/999999999").header("Authorization", bearer(me)))
				.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		assertThat(json.readTree(a).get("code")).isEqualTo(json.readTree(b).get("code"));
	}

	@Test
	void 확정_예약은_승차권_번호와_예상_수수료를_보여준다() throws Exception {
		long r = reservation(me, "CONFIRMED", 2, 0);
		payment(r, "APPROVED", 2 * 59800);

		mvc.perform(get("/api/v1/reservations/" + r).header("Authorization", bearer(me)))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
		JsonNode d = getJson("/api/v1/reservations/" + r, me);

		assertThat(d.get("seats")).hasSize(2);
		assertThat(d.get("seats").get(0).get("ticketNo").asString()).startsWith("T" + r);
		assertThat(d.get("summary").get("payDeadlineAt").isNull()).isTrue();
		assertThat(d.get("payment").get("status").asString()).isEqualTo("APPROVED");

		JsonNode c = d.get("cancel");
		assertThat(c.get("cancelable").asBoolean()).isTrue();
		// 남은 분에 맞는 구간의 비율로 좌석마다 버림 계산
		long m = c.get("minutesBeforeDeparture").asLong();
		BigDecimal rate = new BigDecimal(c.get("feeRate").asString());
		assertThat(rate).isEqualByComparingTo(expectedRate(m));
		long perSeat = new BigDecimal(59800).multiply(rate).longValue();
		assertThat(c.get("fee").asLong()).isEqualTo(perSeat * 2);
		assertThat(c.get("refund").asLong()).isEqualTo(2 * 59800 - perSeat * 2);
	}

	@Test
	void 결제_전_예약은_승차권_번호를_숨기고_결제_기한과_무료_취소를_보여준다() throws Exception {
		long hold = reservation(me, "HOLD", 1, 0);
		JsonNode d = getJson("/api/v1/reservations/" + hold, me);
		assertThat(d.get("seats").get(0).get("ticketNo").isNull()).isTrue();
		assertThat(d.get("summary").get("payDeadlineAt").isNull()).isFalse();
		assertThat(d.get("cancel").get("cancelable").asBoolean()).isTrue();
		assertThat(d.get("cancel").get("fee").asLong()).isZero();

		long pending = reservation(me, "PAYMENT_PENDING", 1, 1);
		JsonNode p = getJson("/api/v1/reservations/" + pending, me);
		assertThat(p.get("cancel").get("cancelable").asBoolean()).isFalse();
		assertThat(p.get("cancel").get("reason").asString()).isEqualTo("PAYMENT_IN_PROGRESS");

		long expired = reservation(me, "EXPIRED", 1, 2);
		assertThat(getJson("/api/v1/reservations/" + expired, me).get("cancel").get("reason").asString())
				.isEqualTo("ALREADY_CLOSED");
	}

	// ------------------------------------------------------------ 도우미

	static BigDecimal expectedRate(long minutesBefore) {
		if (minutesBefore < 0) return new BigDecimal("0.4");
		if (minutesBefore >= 4320) return BigDecimal.ZERO;
		if (minutesBefore >= 1440) return new BigDecimal("0.05");
		if (minutesBefore >= 180) return new BigDecimal("0.1");
		return new BigDecimal("0.2");
	}

	List<Long> ids(JsonNode page) {
		List<Long> out = new ArrayList<>();
		page.get("items").forEach(n -> out.add(n.get("reservationId").asLong()));
		return out;
	}

	JsonNode getJson(String url, long memberId) throws Exception {
		String body = mvc.perform(get(url).header("Authorization", bearer(memberId))).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return json.readTree(body);
	}

	String bearer(long memberId) {
		return "Bearer " + tokens.issue(ActorType.MEMBER, memberId, UUID.randomUUID().toString()).value();
	}

	long member() {
		long id = NEXT_ID.getAndIncrement();
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, '테스트', 'ACTIVE')",
				id, "resv" + id + "@infra-test.jejari.dev", "resv-ci-" + id);
		members.add(id);
		return id;
	}

	/** seatOffset: 좌석 목록에서 몇 번째부터 쓸지 (예약끼리 좌석이 겹치지 않게) */
	long reservation(long memberId, String status, int seats, int seatOffset) {
		long id = jdbc.queryForObject("SELECT reservation_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO reservation (reservation_id, member_id, ci_hash, run_id, from_station_id, to_station_id,
				  seat_count, total_amount, status, channel, source, held_at, pay_deadline_at, confirmed_at,
				  idempotency_key, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'WEB', 'NORMAL', SYSTIMESTAMP, SYSTIMESTAMP + INTERVAL '10' MINUTE,
				  CASE WHEN ? = 'CONFIRMED' THEN SYSTIMESTAMP END, ?, 0)""",
				id, memberId, "resv-ci-" + memberId, RUN, FROM, TO, seats, 59800L * seats, status, status,
				UUID.randomUUID().toString());
		for (int i = 0; i < seats; i++) {
			jdbc.update("""
					INSERT INTO reservation_seat (reservation_id, seat_id, ticket_no, passenger_type, amount)
					VALUES (?, ?, ?, 'ADULT', 59800)""", id, seatIds.get((seatOffset + i) % seatIds.size()),
					"T" + id + "-" + (i + 1));
		}
		return id;
	}

	void payment(long reservationId, String status, long amount) {
		long memberId = jdbc.queryForObject("SELECT member_id FROM reservation WHERE reservation_id = ?", Long.class,
				reservationId);
		long method = NEXT_ID.getAndIncrement();
		jdbc.update("INSERT INTO payment_method (method_id, member_id, method_hash, method_type, card_last4) VALUES (?, ?, ?, 'CARD', '4242')",
				method, memberId, "m" + method);
		jdbc.update("""
				INSERT INTO payment (payment_id, reservation_id, method_id, amount, status, idempotency_key, requested_at, approved_at)
				VALUES (?, ?, ?, ?, ?, ?, SYSTIMESTAMP, SYSTIMESTAMP)""",
				NEXT_ID.getAndIncrement(), reservationId, method, amount, status, UUID.randomUUID().toString());
	}

	void policy(Long min, Long max, String rate, String after) {
		long id = NEXT_ID.getAndIncrement();
		jdbc.update("""
				INSERT INTO cancel_fee_policy (fee_policy_id, sale_event_id, min_minutes_before, max_minutes_before, fee_rate, after_departure)
				VALUES (?, NULL, ?, ?, ?, ?)""", id, min, max, new BigDecimal(rate), after);
		policies.add(id);
	}
}
