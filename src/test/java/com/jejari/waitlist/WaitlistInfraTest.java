package com.jejari.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jejari.auth.ActorType;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.queue.redis.QueueKeys;
import com.jejari.support.FullStackInfraTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * WL-01·02 취소표 대기를 실제 Oracle·Redis로 확인한다. 매진은 테스트용 Redis(15번)의 좌석 배치도 캐시에
 * GENERAL 좌석을 모두 판매됨으로 써서 만든다 (실제 좌석은 건드리지 않음).
 */
@FullStackInfraTest
class WaitlistInfraTest {

	static final long RUN = FullStackInfraTest.ON_SALE_RUN;
	static final String FROM = "3900023";
	static final String TO = "3900114";
	static final AtomicLong NEXT_ID = new AtomicLong(940_001);

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	AccessTokenIssuer tokens;
	@Autowired
	JsonMapper json;

	final List<Long> members = new ArrayList<>();

	@BeforeEach
	void setUp() {
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		redis.opsForValue().set(QueueKeys.RISK_SYNCED_AT, Instant.now().toString()); // 위험 점수는 캐시에서 읽게
		soldOut("GENERAL");
	}

	@AfterEach
	void tearDown() {
		for (long m : members) {
			jdbc.update("DELETE FROM waitlist WHERE member_id = ?", m);
			jdbc.update("DELETE FROM member WHERE member_id = ?", m);
		}
	}

	// ------------------------------------------------------------ 신청

	// concurrency.md §16 T38·T39 일부 (신청 때 priority_at 계산만. 제안 순서가 시각에 따라 바뀌는 것은 안 봄)
	@Test
	void 매진된_등급에_신청하면_위험_점수만큼_순서가_뒤로_밀린다_점수는_응답에_없다() throws Exception {
		String ci = hash();
		long m = member(ci, "ACTIVE");
		redis.opsForValue().set(QueueKeys.risk("CI", ci), "80.00");

		Instant before = Instant.now();
		String body = mvc.perform(apply(m, RUN, "GENERAL", 1)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("WAITING")).andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("penalty").doesNotContain("priority").doesNotContain("score");

		Map<String, Object> row = jdbc.queryForMap(
				"SELECT requested_at, priority_at, penalty_ms FROM waitlist WHERE member_id = ?", m);
		assertThat(((Number) row.get("PENALTY_MS")).longValue()).isEqualTo(80 * 180_000L); // 4시간
		Instant req = ((Timestamp) row.get("REQUESTED_AT")).toInstant();
		Instant pri = ((Timestamp) row.get("PRIORITY_AT")).toInstant();
		assertThat(Duration.between(req, pri)).isEqualTo(Duration.ofHours(4));
		assertThat(req).isAfterOrEqualTo(before.minusSeconds(1));
	}

	@Test
	void 남은_좌석이_있는_등급은_신청할_수_없다() throws Exception {
		long m = member(hash(), "ACTIVE");
		mvc.perform(apply(m, RUN, "FIRST", 1)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("WAITLIST_NOT_SOLD_OUT"));
	}

	// concurrency.md §16 T40
	@Test
	void 이용_제한_회원은_403() throws Exception {
		long m = member(hash(), "RESTRICTED");
		mvc.perform(apply(m, RUN, "GENERAL", 1)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("WAITLIST_RESTRICTED"));
	}

	@Test
	void 같은_운행은_한_건만_동시에_눌러도_한_건만() throws Exception {
		long m = member(hash(), "ACTIVE");
		ExecutorService pool = Executors.newFixedThreadPool(4);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			results.add(pool.submit((Callable<Integer>) () -> {
				go.await();
				return mvc.perform(apply(m, RUN, "GENERAL", 1)).andReturn().getResponse().getStatus();
			}));
		}
		go.countDown();
		List<Integer> codes = new ArrayList<>();
		for (Future<Integer> f : results) {
			codes.add(f.get());
		}
		pool.shutdown();
		assertThat(codes).containsOnlyOnce(201);
		assertThat(codes.stream().filter(c -> c == 409).count()).isEqualTo(3);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist WHERE member_id = ?", Integer.class, m)).isOne();

		mvc.perform(apply(m, RUN, "GENERAL", 1)).andExpect(jsonPath("$.code").value("WAITLIST_DUPLICATED"));
	}

	@Test
	void 활성_대기가_3건이면_더_못하고_끝난_대기는_세지_않는다() throws Exception {
		long m = member(hash(), "ACTIVE");
		for (int i = 0; i < 3; i++) {
			insertWaitlist(m, 9002, "WAITING", Instant.now());
		}
		mvc.perform(apply(m, RUN, "GENERAL", 1)).andExpect(status().is(422))
				.andExpect(jsonPath("$.code").value("WAITLIST_LIMIT"));

		jdbc.update("UPDATE waitlist SET status = 'EXPIRED' WHERE member_id = ? AND ROWNUM = 1", m);
		mvc.perform(apply(m, RUN, "GENERAL", 1)).andExpect(status().isCreated());
	}

	@Test
	void 입력이_틀리면_거절() throws Exception {
		long m = member(hash(), "ACTIVE");
		mvc.perform(apply(m, RUN, "VIP", 1)).andExpect(status().isBadRequest());
		mvc.perform(apply(m, RUN, "GENERAL", 0)).andExpect(status().isBadRequest());
		mvc.perform(apply(m, RUN, "GENERAL", 10)).andExpect(status().is(422)) // 상시 1회 한도 9매
				.andExpect(jsonPath("$.code").value("LIMIT_PER_RESERVATION"));
		mvc.perform(post("/api/v1/waitlists").header("Authorization", bearer(m)).contentType(MediaType.APPLICATION_JSON)
				.content(body(RUN, TO, FROM, "GENERAL", 1))).andExpect(status().isBadRequest()); // 역 순서 반대
		mvc.perform(apply(m, 999_999, "GENERAL", 1)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("RUN_NOT_ON_SALE"));
		mvc.perform(post("/api/v1/waitlists").contentType(MediaType.APPLICATION_JSON)
				.content(body(RUN, FROM, TO, "GENERAL", 1))).andExpect(status().isUnauthorized());
	}

	// ------------------------------------------------------------ 목록

	@Test
	void 목록은_내_대기만_보이고_앞_대기_수와_제안_기한을_보여준다() throws Exception {
		long me = member(hash(), "ACTIVE");
		long early = member(hash(), "ACTIVE");
		long late = member(hash(), "ACTIVE");
		Instant now = Instant.now();
		insertWaitlist(early, RUN, "WAITING", now.minusSeconds(600));
		long mine = insertWaitlist(me, RUN, "WAITING", now);
		insertWaitlist(late, RUN, "WAITING", now.plusSeconds(600));
		long offered = insertWaitlist(me, 9002, "OFFERED", now);
		jdbc.update("UPDATE waitlist SET offer_deadline_at = SYSTIMESTAMP + INTERVAL '10' MINUTE WHERE waitlist_id = ?",
				offered);

		JsonNode list = json.readTree(mvc.perform(get("/api/v1/waitlists").header("Authorization", bearer(me)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(list).hasSize(2);
		Map<Long, JsonNode> byId = new HashMap<>();
		list.forEach(n -> byId.put(n.get("waitlistId").asLong(), n));
		assertThat(byId.get(mine).get("ahead").asLong()).isEqualTo(1); // early만 앞
		assertThat(byId.get(mine).get("offerDeadlineAt").isNull()).isTrue();
		assertThat(byId.get(offered).get("ahead").isNull()).isTrue();
		assertThat(byId.get(offered).get("offerDeadlineAt").isNull()).isFalse();
		assertThat(byId.get(mine).get("fromName").asString()).isNotBlank();
	}

	// ------------------------------------------------------------ 대기 취소

	@Test
	void 기다리는_중인_대기만_취소되고_두_번_눌러도_같다() throws Exception {
		long me = member(hash(), "ACTIVE");
		long other = member(hash(), "ACTIVE");
		long w = insertWaitlist(me, RUN, "WAITING", Instant.now());
		long offered = insertWaitlist(me, 9002, "OFFERED", Instant.now());
		long theirs = insertWaitlist(other, RUN, "WAITING", Instant.now());

		mvc.perform(delete("/api/v1/waitlists/" + w).header("Authorization", bearer(me))).andExpect(status().isNoContent());
		mvc.perform(delete("/api/v1/waitlists/" + w).header("Authorization", bearer(me))).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT status FROM waitlist WHERE waitlist_id = ?", String.class, w))
				.isEqualTo("CANCELED");

		mvc.perform(delete("/api/v1/waitlists/" + offered).header("Authorization", bearer(me)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WAITLIST_NOT_WAITING"));
		mvc.perform(delete("/api/v1/waitlists/" + theirs).header("Authorization", bearer(me)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WAITLIST_NOT_FOUND"));
		assertThat(jdbc.queryForObject("SELECT status FROM waitlist WHERE waitlist_id = ?", String.class, theirs))
				.isEqualTo("WAITING");
	}

	// ------------------------------------------------------------ 도우미

	/** 테스트 Redis의 좌석 배치도 캐시에 그 등급 좌석을 모두 판매됨으로 (SeatMapService가 캐시를 그대로 읽음) */
	void soldOut(String grade) {
		List<Long> seats = jdbc.queryForList("""
				SELECT seat_id FROM seat WHERE seat_grade = ?
				   AND train_id = (SELECT train_id FROM train_run WHERE run_id = ?)""", Long.class, grade, RUN);
		Map<String, String> h = new HashMap<>();
		h.put("_", "1");
		seats.forEach(s -> h.put(Long.toString(s), "S"));
		redis.opsForHash().putAll("seatmap:" + RUN, h);
	}

	MockHttpServletRequestBuilder apply(long memberId, long runId, String grade, int count) {
		return post("/api/v1/waitlists").header("Authorization", bearer(memberId)).contentType(MediaType.APPLICATION_JSON)
				.content(body(runId, FROM, TO, grade, count));
	}

	static String body(long runId, String from, String to, String grade, int count) {
		return """
				{"runId":%d,"fromStationId":"%s","toStationId":"%s","seatGrade":"%s","seatCount":%d}"""
				.formatted(runId, from, to, grade, count);
	}

	String bearer(long memberId) {
		return "Bearer " + tokens.issue(ActorType.MEMBER, memberId, UUID.randomUUID().toString()).value();
	}

	static String hash() {
		byte[] b = new byte[32];
		new SecureRandom().nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	long member(String ci, String status) {
		long id = NEXT_ID.getAndIncrement();
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, '테스트', ?)",
				id, "wl" + id + "@infra-test.jejari.dev", ci, status);
		members.add(id);
		return id;
	}

	long insertWaitlist(long memberId, long runId, String status, Instant priorityAt) {
		long id = jdbc.queryForObject("SELECT waitlist_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO waitlist (waitlist_id, member_id, run_id, from_station_id, to_station_id, seat_grade, seat_count,
				  requested_at, priority_at, penalty_ms, status)
				VALUES (?, ?, ?, ?, ?, 'GENERAL', 1, ?, ?, 0, ?)""",
				id, memberId, runId, FROM, TO, Timestamp.from(priorityAt), Timestamp.from(priorityAt), status);
		return id;
	}
}
