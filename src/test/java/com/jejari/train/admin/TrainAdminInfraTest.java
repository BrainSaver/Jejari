package com.jejari.train.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jejari.auth.ActorType;
import com.jejari.auth.AdminRole;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.support.NoRunningApp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADM-01·02 관리자 기준 데이터·운행 API를 실제 Oracle로 확인한다 (화면 A-02·A-03).
 *
 * <p>시드 데이터는 건드리지 않는다. 테스트가 만드는 행은 모두 관리자 대역(노선 100~, 열차 1000~, 행사·정책 100~,
 * 운행 1000000~)과 {@code TST} 접두 코드라 끝나면 그 대역만 지운다.
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
class TrainAdminInfraTest {

	static final long ADMIN = 961901;
	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenIssuer tokens;

	@BeforeEach
	void setUp() {
		clean();
	}

	@AfterEach
	void tearDown() {
		clean();
	}

	void clean() {
		jdbc.update("DELETE FROM train_stop WHERE run_id >= ?", RunAdminService.RUN_ID_BASE);
		jdbc.update("DELETE FROM train_run WHERE run_id >= ?", RunAdminService.RUN_ID_BASE);
		jdbc.update("DELETE FROM purchase_limit_policy WHERE policy_id >= ?", RunAdminService.POLICY_ID_BASE);
		jdbc.update("DELETE FROM sale_event WHERE sale_event_id >= ?", RunAdminService.EVENT_ID_BASE);
		jdbc.update("DELETE FROM seat WHERE train_id >= ?", MasterDataService.TRAIN_ID_BASE);
		jdbc.update("DELETE FROM train_car WHERE train_id >= ?", MasterDataService.TRAIN_ID_BASE);
		jdbc.update("DELETE FROM train WHERE train_id >= ?", MasterDataService.TRAIN_ID_BASE);
		jdbc.update("DELETE FROM route_station WHERE route_id >= ?", MasterDataService.ROUTE_ID_BASE);
		jdbc.update("DELETE FROM route WHERE route_id >= ?", MasterDataService.ROUTE_ID_BASE);
		jdbc.update("DELETE FROM car_type WHERE car_type_code LIKE 'TST%'");
		jdbc.update("DELETE FROM station WHERE station_id LIKE 'TST%'");
	}

	// ------------------------------------------------------------ 권한

	@Test
	void VIEWER는_보기만_하고_저장은_SUPERVISOR만() throws Exception {
		mvc.perform(get("/api/v1/admin/stations").header("Authorization", admin(AdminRole.VIEWER)))
				.andExpect(status().isOk());
		mvc.perform(get("/api/v1/admin/runs").header("Authorization", admin(AdminRole.VIEWER)))
				.andExpect(status().isOk());
		for (AdminRole role : new AdminRole[] { AdminRole.VIEWER, AdminRole.REVIEWER }) {
			send(post("/api/v1/admin/stations"), role, Map.of("stationId", "TST01", "stationName", "시험역"))
					.andExpect(status().isForbidden());
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM station WHERE station_id = 'TST01'", Integer.class))
				.isZero();
	}

	@Test
	void 회원_토큰으로는_못_본다() throws Exception {
		mvc.perform(get("/api/v1/admin/trains").header("Authorization",
				"Bearer " + tokens.issue(ActorType.MEMBER, 961001, UUID.randomUUID().toString()).value()))
				.andExpect(status().isForbidden());
	}

	// ------------------------------------------------------------ ADM-01

	@Test
	void 역_노선_차량유형_열차를_만들면_좌석이_시드와_같은_규칙으로_생긴다() throws Exception {
		long route = createRoute();
		createCarType("TSTG", 10, "GENERAL", "ABCD");
		createCarType("TSTF", 6, "FIRST", "ABC");

		JsonNode t = json(send(post("/api/v1/admin/trains"), Map.of("trainNo", "T901", "trainKind", "KTX",
				"routeId", route, "direction", "DOWN", "cars", List.of("TSTF", "TSTG", "TSTG")))
				.andExpect(status().isCreated()));
		long trainId = t.get("trainId").asLong();
		assertThat(trainId).isGreaterThanOrEqualTo(MasterDataService.TRAIN_ID_BASE);
		assertThat(t.get("seatCount").asInt()).isEqualTo(6 + 10 + 10);
		assertThat(t.get("cars")).hasSize(3);

		// seat_id = train_id × 100000 + car_no × 1000 + 순번, 좌석 번호 = 행 + 열, 양 끝 열이 창가
		Map<String, Object> s5 = jdbc.queryForMap(
				"SELECT seat_no, is_window, seat_grade FROM seat WHERE seat_id = ?", trainId * 100_000 + 2 * 1000 + 5);
		assertThat(s5).containsEntry("SEAT_NO", "2A").containsEntry("IS_WINDOW", "Y").containsEntry("SEAT_GRADE", "GENERAL");
		Map<String, Object> s2 = jdbc.queryForMap("SELECT seat_no, is_window FROM seat WHERE seat_id = ?",
				trainId * 100_000 + 2 * 1000 + 2);
		assertThat(s2).containsEntry("SEAT_NO", "1B").containsEntry("IS_WINDOW", "N");
		Map<String, Object> f3 = jdbc.queryForMap("SELECT seat_no, is_window, seat_grade FROM seat WHERE seat_id = ?",
				trainId * 100_000 + 1000 + 3);
		assertThat(f3).containsEntry("SEAT_NO", "1C").containsEntry("IS_WINDOW", "Y").containsEntry("SEAT_GRADE", "FIRST");

		// 같은 열차 번호는 막는다 (DB 유니크가 없어 앱에서)
		send(post("/api/v1/admin/trains"), Map.of("trainNo", "T901", "trainKind", "KTX", "routeId", route,
				"direction", "UP", "cars", List.of("TSTG")))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_EXISTS"));
	}

	@Test
	void 열차가_붙은_노선의_역_구성과_쓰이는_차량유형의_좌석은_못_바꾸고_이름은_바꾼다() throws Exception {
		long route = createRoute();
		createCarType("TSTG", 10, "GENERAL", "ABCD");
		createTrain("T902", route, "DOWN", List.of("TSTG"));

		send(put("/api/v1/admin/routes/" + route), Map.of("routeName", "시험선2", "stations", List.of(
				Map.of("stationId", "TSTA1", "distanceKm", 0), Map.of("stationId", "TSTA3", "distanceKm", 54))))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IN_USE"));
		send(put("/api/v1/admin/routes/" + route), Map.of("routeName", "시험선 개명"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.routeName").value("시험선 개명"))
				.andExpect(jsonPath("$.stations.length()").value(3));

		send(put("/api/v1/admin/car-types/TSTG"), Map.of("carTypeName", "일반", "seatCount", 12,
				"seatGrade", "GENERAL", "seatColumns", "ABCD"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IN_USE"));
		send(put("/api/v1/admin/car-types/TSTG"), Map.of("carTypeName", "일반 개명", "seatCount", 10,
				"seatGrade", "GENERAL", "seatColumns", "ABCD"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.carTypeName").value("일반 개명"));
	}

	@Test
	void 노선은_첫_역이_0km이고_거리가_늘어나야_한다() throws Exception {
		createStations();
		send(post("/api/v1/admin/routes"), Map.of("routeName", "엉터리선", "stations", List.of(
				Map.of("stationId", "TSTA1", "distanceKm", 0), Map.of("stationId", "TSTA2", "distanceKm", 0))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VALUE"));
		send(post("/api/v1/admin/routes"), Map.of("routeName", "없는역선", "stations", List.of(
				Map.of("stationId", "TSTA1", "distanceKm", 0), Map.of("stationId", "TSTZZ", "distanceKm", 5))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFERENCE_NOT_FOUND"));
	}

	// ------------------------------------------------------------ ADM-02

	@Test
	void 운행을_요일로_일괄_생성하면_정차_시각이_시드_규칙대로_생기고_다시_만들면_건너뛴다() throws Exception {
		long down = createTrain("T903", createRoute(), "DOWN", List.of(carType()));
		LocalDate from = LocalDate.now().plusDays(100);
		// from부터 14일 중 from과 같은 요일만 → 2건
		int dow = from.getDayOfWeek().getValue();
		JsonNode r = json(send(post("/api/v1/admin/runs"), Map.of("trainId", down, "from", from.toString(),
				"to", from.plusDays(13).toString(), "weekdays", List.of(dow))).andExpect(status().isCreated()));
		assertThat(r.get("created")).hasSize(2);
		long runId = r.get("created").get(0).asLong();
		assertThat(runId).isGreaterThanOrEqualTo(RunAdminService.RUN_ID_BASE);

		Map<String, Object> run = jdbc.queryForMap(
				"SELECT status, sale_open_at, sale_close_at, sale_event_id FROM train_run WHERE run_id = ?", runId);
		assertThat(run.get("STATUS")).isEqualTo("SCHEDULED"); // 오픈은 두 달 전 07:00 → 아직 미래
		assertThat(((java.sql.Timestamp) run.get("SALE_OPEN_AT")).toLocalDateTime())
				.isEqualTo(from.minusMonths(2).atTime(7, 0));
		assertThat(((java.sql.Timestamp) run.get("SALE_CLOSE_AT")).toLocalDateTime()).isEqualTo(from.plusDays(1).atStartOfDay());
		assertThat(run.get("SALE_EVENT_ID")).isNull();

		// 0km·27km·54km, 2.7km/분 + 중간 정차 2분: 09:00 출발 → 2번역 09:12 도착·09:14 출발 → 3번역 09:24 도착
		List<Map<String, Object>> stops = jdbc.queryForList(
				"SELECT station_id, stop_order, arrival_time, departure_time FROM train_stop WHERE run_id = ? ORDER BY stop_order",
				runId);
		assertThat(stops).hasSize(3);
		assertThat(stops.get(0).get("ARRIVAL_TIME")).isNull();
		assertThat(time(stops.get(0).get("DEPARTURE_TIME"))).isEqualTo(from.atTime(9, 0));
		assertThat(time(stops.get(1).get("ARRIVAL_TIME"))).isEqualTo(from.atTime(9, 12));
		assertThat(time(stops.get(1).get("DEPARTURE_TIME"))).isEqualTo(from.atTime(9, 14));
		assertThat(time(stops.get(2).get("ARRIVAL_TIME"))).isEqualTo(from.atTime(9, 24));
		assertThat(stops.get(2).get("DEPARTURE_TIME")).isNull();

		JsonNode again = json(send(post("/api/v1/admin/runs"), Map.of("trainId", down, "from", from.toString(),
				"to", from.plusDays(13).toString(), "weekdays", List.of(dow))).andExpect(status().isCreated()));
		assertThat(again.get("created")).isEmpty();
		assertThat(again.get("skipped")).hasSize(2);
	}

	@Test
	void 상행은_순번과_거리를_뒤집고_기준_출발은_15시() throws Exception {
		long up = createTrain("T904", createRoute(), "UP", List.of(carType()));
		LocalDate d = LocalDate.now().plusDays(100);
		long runId = json(send(post("/api/v1/admin/runs"), Map.of("trainId", up, "from", d.toString(), "to", d.toString()))
				.andExpect(status().isCreated())).get("created").get(0).asLong();
		List<Map<String, Object>> stops = jdbc.queryForList(
				"SELECT station_id, departure_time, arrival_time FROM train_stop WHERE run_id = ? ORDER BY stop_order", runId);
		assertThat(stops).extracting(m -> m.get("STATION_ID")).containsExactly("TSTA3", "TSTA2", "TSTA1");
		assertThat(time(stops.get(0).get("DEPARTURE_TIME"))).isEqualTo(d.atTime(15, 0));
		assertThat(time(stops.get(2).get("ARRIVAL_TIME"))).isEqualTo(d.atTime(15, 24));
	}

	@Test
	void 오픈이_지난_날짜로_만들면_바로_ON_SALE() throws Exception {
		long train = createTrain("T905", createRoute(), "DOWN", List.of(carType()));
		LocalDate d = LocalDate.now().plusDays(5);
		long runId = json(send(post("/api/v1/admin/runs"), Map.of("trainId", train, "from", d.toString(), "to", d.toString()))
				.andExpect(status().isCreated())).get("created").get(0).asLong();
		assertThat(jdbc.queryForObject("SELECT status FROM train_run WHERE run_id = ?", String.class, runId))
				.isEqualTo("ON_SALE");
	}

	@Test
	void 명절_행사를_만들면_전용_매수_제한이_생기고_운행은_행사_오픈_시각을_복사한다() throws Exception {
		long train = createTrain("T906", createRoute(), "DOWN", List.of(carType()));
		LocalDate from = LocalDate.now().plusDays(120);
		LocalDateTime open = LocalDate.now().plusDays(60).atTime(7, 0);
		JsonNode ev = json(send(post("/api/v1/admin/sale-events"), Map.of("name", "시험 명절",
				"holidayFrom", from.toString(), "holidayTo", from.plusDays(3).toString(), "saleOpenAt", open.toString()))
				.andExpect(status().isCreated()));
		long eventId = ev.get("saleEventId").asLong();
		assertThat(ev.get("status").asString()).isEqualTo("PLANNED");
		assertThat(ev.get("limits").get("PER_HOLIDAY").asInt()).isEqualTo(8);
		assertThat(ev.get("limits").get("PER_RUN").asInt()).isEqualTo(4);

		// 행사 기간 밖은 거절
		send(post("/api/v1/admin/runs"), Map.of("trainId", train, "from", from.minusDays(1).toString(),
				"to", from.toString(), "saleEventId", eventId))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VALUE"));

		JsonNode r = json(send(post("/api/v1/admin/runs"), Map.of("trainId", train, "from", from.toString(),
				"to", from.plusDays(3).toString(), "saleEventId", eventId)).andExpect(status().isCreated()));
		assertThat(r.get("created")).hasSize(4);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM train_run WHERE sale_event_id = ? AND sale_open_at = ?", Integer.class, eventId,
				java.sql.Timestamp.valueOf(open))).isEqualTo(4);

		// 행사 오픈 시각을 바꾸면 판매 전 운행도 따라온다
		LocalDateTime moved = open.plusDays(1);
		send(put("/api/v1/admin/sale-events/" + eventId), Map.of("name", "시험 명절", "holidayFrom", from.toString(),
				"holidayTo", from.plusDays(3).toString(), "saleOpenAt", moved.toString(), "status", "PLANNED"))
				.andExpect(status().isOk());
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM train_run WHERE sale_event_id = ? AND sale_open_at = ?", Integer.class, eventId,
				java.sql.Timestamp.valueOf(moved))).isEqualTo(4);

		mvc.perform(get("/api/v1/admin/runs").param("saleEventId", String.valueOf(eventId))
				.param("from", from.toString()).param("to", from.plusDays(3).toString())
				.header("Authorization", admin(AdminRole.VIEWER)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4))
				.andExpect(jsonPath("$[0].saleEventName").value("시험 명절"));
	}

	@Test
	void 판매_오픈_버튼은_SCHEDULED만_열고_열린_운행은_고치거나_지울_수_없다() throws Exception {
		long train = createTrain("T907", createRoute(), "DOWN", List.of(carType()));
		LocalDate d = LocalDate.now().plusDays(100);
		JsonNode r = json(send(post("/api/v1/admin/runs"), Map.of("trainId", train, "from", d.toString(),
				"to", d.plusDays(1).toString())).andExpect(status().isCreated()));
		long a = r.get("created").get(0).asLong();
		long b = r.get("created").get(1).asLong();

		send(post("/api/v1/admin/runs/" + a + "/open"), Map.of())
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ON_SALE"));
		LocalDateTime openAt = jdbc.queryForObject("SELECT sale_open_at FROM train_run WHERE run_id = ?",
				LocalDateTime.class, a);
		assertThat(openAt).isBefore(LocalDateTime.now().plusMinutes(1)); // 오픈 시각도 지금으로 당겨짐

		send(post("/api/v1/admin/runs/" + a + "/open"), Map.of())
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RUN_NOT_SCHEDULED"));
		send(put("/api/v1/admin/runs/" + a), Map.of("saleOpenAt", d.minusDays(30).atTime(7, 0).toString()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RUN_NOT_SCHEDULED"));
		mvc.perform(delete("/api/v1/admin/runs/" + a).header("Authorization", admin(AdminRole.SUPERVISOR)))
				.andExpect(status().isConflict());

		// 판매 전 운행은 시각을 고치고 지울 수 있다
		LocalDateTime newOpen = d.minusDays(30).atTime(7, 0);
		send(put("/api/v1/admin/runs/" + b), Map.of("saleOpenAt", newOpen.toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCHEDULED"));
		mvc.perform(delete("/api/v1/admin/runs/" + b).header("Authorization", admin(AdminRole.SUPERVISOR)))
				.andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM train_stop WHERE run_id = ?", Integer.class, b)).isZero();

		// 운행이 붙은 열차는 편성을 못 바꾸고, 번호는 바꾼다
		send(put("/api/v1/admin/trains/" + train), Map.of("trainNo", "T907", "trainKind", "KTX",
				"routeId", jdbc.queryForObject("SELECT route_id FROM train WHERE train_id = ?", Long.class, train),
				"direction", "DOWN", "cars", List.of(carType(), carType())))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IN_USE"));
	}

	@Test
	void 지난_날짜와_석_달_넘는_기간은_거절() throws Exception {
		long train = createTrain("T908", createRoute(), "DOWN", List.of(carType()));
		LocalDate today = LocalDate.now();
		send(post("/api/v1/admin/runs"), Map.of("trainId", train, "from", today.minusDays(1).toString(),
				"to", today.toString())).andExpect(status().isBadRequest());
		send(post("/api/v1/admin/runs"), Map.of("trainId", train, "from", today.toString(),
				"to", today.plusDays(RunAdminService.BULK_DAYS_MAX).toString())).andExpect(status().isBadRequest());
	}

	// ------------------------------------------------------------ 도우미

	private void createStations() throws Exception {
		for (String[] s : new String[][] { { "TSTA1", "시험가" }, { "TSTA2", "시험나" }, { "TSTA3", "시험다" } }) {
			send(post("/api/v1/admin/stations"), Map.of("stationId", s[0], "stationName", s[1]))
					.andExpect(status().isCreated());
		}
	}

	/** 시험가(0km) – 시험나(27km) – 시험다(54km) */
	private long createRoute() throws Exception {
		createStations();
		return json(send(post("/api/v1/admin/routes"), Map.of("routeName", "시험선", "stations", List.of(
				Map.of("stationId", "TSTA1", "distanceKm", 0), Map.of("stationId", "TSTA2", "distanceKm", 27),
				Map.of("stationId", "TSTA3", "distanceKm", 54))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.originStationId").value("TSTA1"))
				.andExpect(jsonPath("$.destStationId").value("TSTA3"))).get("routeId").asLong();
	}

	private void createCarType(String code, int seats, String grade, String cols) throws Exception {
		send(post("/api/v1/admin/car-types"), Map.of("carTypeCode", code, "carTypeName", code, "seatCount", seats,
				"seatGrade", grade, "seatColumns", cols)).andExpect(status().isCreated());
	}

	private String carType() throws Exception {
		if (jdbc.queryForObject("SELECT COUNT(*) FROM car_type WHERE car_type_code = 'TSTG'", Integer.class) == 0) {
			createCarType("TSTG", 8, "GENERAL", "ABCD");
		}
		return "TSTG";
	}

	private long createTrain(String no, long route, String direction, List<String> cars) throws Exception {
		return json(send(post("/api/v1/admin/trains"), Map.of("trainNo", no, "trainKind", "KTX", "routeId", route,
				"direction", direction, "cars", cars)).andExpect(status().isCreated())).get("trainId").asLong();
	}

	private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
			Object body) throws Exception {
		return send(req, AdminRole.SUPERVISOR, body);
	}

	private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
			AdminRole role, Object body) throws Exception {
		return mvc.perform(req.header("Authorization", admin(role)).contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(body)));
	}

	private String admin(AdminRole role) {
		return "Bearer " + tokens.issue(ActorType.ADMIN, ADMIN, UUID.randomUUID().toString(), role).value();
	}

	private static JsonNode json(ResultActions r) throws Exception {
		return JSON.readTree(r.andReturn().getResponse().getContentAsString());
	}

	private static LocalDateTime time(Object o) {
		return ((java.sql.Timestamp) o).toLocalDateTime();
	}
}
