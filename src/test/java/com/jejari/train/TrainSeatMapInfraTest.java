package com.jejari.train;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jejari.auth.ActorType;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.seat.SeatHoldService;
import com.jejari.seat.SeatHoldService.HoldCommand;
import com.jejari.seat.SeatHoldService.SeatRequest;
import com.jejari.seat.SeatKeys;
import com.jejari.seat.SeatReleaseService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 열차 조회(TRN-01~03)와 좌석 배치도(RSV-01)를 실제 Oracle·Redis로 확인한다.
 * 운행 9001: 열차 1 (1호차 특실 30석 + 2~10호차 일반 55석씩), 서울(3900023) → 부산(3900114).
 */
@Tag("infra")
@SpringBootTest(properties = {
		"spring.data.redis.database=15",
		"jejari.queue.scheduler-enabled=false",
		"jejari.risk.cache-resync-enabled=false",
		"jejari.seat.expiry-scanner-enabled=false"})
@AutoConfigureMockMvc
class TrainSeatMapInfraTest {

	static final long RUN = 9001;
	static final String SEOUL = "3900023";
	static final String BUSAN = "3900114";
	static final long MEMBER = 970001;
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
	@Autowired
	SeatReleaseService release;

	LocalDate runDate;
	int generalSeats;

	@BeforeEach
	void setUp() {
		cleanDb();
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
				MEMBER, "map@infra-test.jejari.dev", "map-ci-1", "테스트");
		runDate = jdbc.queryForObject("SELECT run_date FROM train_run WHERE run_id = ?", java.sql.Date.class, RUN).toLocalDate();
		generalSeats = jdbc.queryForObject("SELECT COUNT(*) FROM seat WHERE train_id = 1 AND seat_grade = 'GENERAL'", Integer.class);
	}

	@AfterEach
	void tearDown() {
		cleanDb();
	}

	// ------------------------------------------------------------ TRN

	@Test
	void 역_목록은_로그인_없이_노선_순서로() throws Exception {
		mvc.perform(get("/api/v1/stations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(5))
				.andExpect(jsonPath("$[0].stationId").value(SEOUL))
				.andExpect(jsonPath("$[0].stationName").value("서울"));
	}

	@Test
	void 운행_검색은_로그인_없이_운임과_남은_좌석을_준다() throws Exception {
		JsonNode runs = json(mvc.perform(get("/api/v1/runs").param("from", SEOUL).param("to", BUSAN)
				.param("date", runDate.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		JsonNode r = find(runs, RUN);

		assertThat(r.get("bookable").asBoolean()).isTrue();
		assertThat(r.get("trainNo").asString()).isNotBlank();
		assertThat(r.get("durationMin").asLong()).isPositive();
		assertThat(r.get("fares").get("GENERAL").asLong()).isPositive();
		assertThat(r.get("fares").get("FIRST").asLong()).isGreaterThan(r.get("fares").get("GENERAL").asLong());
		assertThat(r.get("remaining").get("GENERAL").asInt()).isEqualTo(generalSeats);
	}

	@Test
	void 좌석을_잡으면_남은_좌석이_줄어든다() throws Exception {
		long seat = generalSeat(0);
		hold.hold(MEMBER, cmd(seat));

		JsonNode runs = json(mvc.perform(get("/api/v1/runs").param("from", SEOUL).param("to", BUSAN)
				.param("date", runDate.toString())).andReturn().getResponse().getContentAsString());
		assertThat(find(runs, RUN).get("remaining").get("GENERAL").asInt()).isEqualTo(generalSeats - 1);
	}

	@Test
	void 반대_방향이나_잘못된_날짜() throws Exception {
		JsonNode reverse = json(mvc.perform(get("/api/v1/runs").param("from", BUSAN).param("to", SEOUL)
				.param("date", runDate.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		for (JsonNode r : reverse) {
			assertThat(r.get("runId").asLong()).isNotEqualTo(RUN); // 9001은 서울 → 부산 방향
		}
		mvc.perform(get("/api/v1/runs").param("from", SEOUL).param("to", BUSAN).param("date", "11/19"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void 운행_상세는_정차역과_구간_운임() throws Exception {
		mvc.perform(get("/api/v1/runs/" + RUN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stops.length()").value(5))
				.andExpect(jsonPath("$.stops[0].stationName").value("서울"))
				.andExpect(jsonPath("$.fromStationId").value(SEOUL))
				.andExpect(jsonPath("$.toStationId").value(BUSAN))
				.andExpect(jsonPath("$.fares.GENERAL").isNumber());
		mvc.perform(get("/api/v1/runs/" + RUN).param("from", BUSAN).param("to", SEOUL))
				.andExpect(status().isBadRequest());
	}

	// ------------------------------------------------------------ RSV-01

	@Test
	void 배치도는_로그인과_입장_토큰이_필요하다() throws Exception {
		mvc.perform(get("/api/v1/runs/" + RUN + "/seats")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/runs/" + RUN + "/seats").header("Authorization", bearer()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("QUEUE_TOKEN_REQUIRED"));
	}

	@Test
	void 배치도는_선점_판매_대기자_보류를_구분한다() throws Exception {
		long held = generalSeat(1);
		long canceled = generalSeat(2);
		long sold = generalSeat(3);
		hold.hold(MEMBER, cmd(held));
		long r = hold.hold(MEMBER, cmd(canceled)).reservationId();
		release.cancelBeforePay(MEMBER, r); // 좌석 키가 REOFFER:R → 대기자 몫이라 일반 사용자에겐 선점된 것과 같음
		redis.opsForValue().set(SeatKeys.hold(RUN, sold, 0), "SOLD:123");

		JsonNode map = seatMap("");
		assertThat(state(map, held)).isEqualTo("HELD");
		assertThat(state(map, canceled)).isEqualTo("HELD");
		assertThat(state(map, sold)).isEqualTo("SOLD");
		assertThat(state(map, generalSeat(4))).isEqualTo("EMPTY");
		assertThat(map.get("available").asInt()).isEqualTo(generalSeats + 30 - 3);
	}

	@Test
	void 등급으로_거르면_그_등급_호차만() throws Exception {
		JsonNode map = seatMap("?grade=FIRST");
		assertThat(map.get("cars").size()).isEqualTo(1);
		assertThat(map.get("cars").get(0).get("grade").asString()).isEqualTo("FIRST");
		assertThat(map.get("available").asInt()).isEqualTo(30);
	}

	@Test
	void 배치도_캐시는_유지_시간_동안_재사용된다() throws Exception {
		seatMap("");
		assertThat(redis.hasKey("seatmap:" + RUN)).isTrue();
		assertThat(redis.getExpire("seatmap:" + RUN, java.util.concurrent.TimeUnit.MILLISECONDS)).isBetween(1L, 1000L);
	}

	// ------------------------------------------------------------ 도우미

	private JsonNode seatMap(String query) throws Exception {
		redis.opsForValue().set("queue:pass:" + RUN + ":" + MEMBER, "pass-1");
		return json(mvc.perform(get("/api/v1/runs/" + RUN + "/seats" + query)
						.header("Authorization", bearer()).header("X-Queue-Token", "pass-1"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private static String state(JsonNode map, long seatId) {
		for (JsonNode car : map.get("cars")) {
			for (JsonNode s : car.get("seats")) {
				if (s.get("seatId").asLong() == seatId) {
					return s.get("state").asString();
				}
			}
		}
		throw new AssertionError("좌석 없음 " + seatId);
	}

	private static JsonNode find(JsonNode runs, long runId) {
		for (JsonNode r : runs) {
			if (r.get("runId").asLong() == runId) {
				return r;
			}
		}
		throw new AssertionError("운행 없음 " + runId);
	}

	private long generalSeat(int i) {
		return jdbc.queryForList("SELECT seat_id FROM seat WHERE train_id = 1 AND seat_grade = 'GENERAL' ORDER BY seat_id DESC",
				Long.class).get(100 + i);
	}

	private String bearer() {
		return "Bearer " + tokens.issue(ActorType.MEMBER, MEMBER, UUID.randomUUID().toString()).value();
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
		jdbc.update("DELETE FROM outbox_event WHERE aggregate_type = 'RESERVATION' AND aggregate_id IN (SELECT TO_CHAR(reservation_id) FROM reservation WHERE member_id = ?)", MEMBER);
		jdbc.update("DELETE FROM reservation_history WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM saga_instance WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation_seat WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation WHERE member_id = ?", MEMBER);
		jdbc.update("DELETE FROM purchase_counter WHERE ci_hash = 'map-ci-1'");
		jdbc.update("DELETE FROM member WHERE member_id = ?", MEMBER);
	}
}
