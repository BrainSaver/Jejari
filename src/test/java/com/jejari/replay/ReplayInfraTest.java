package com.jejari.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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

import com.jejari.auth.ActorType;
import com.jejari.auth.AdminRole;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.common.config.AppPolicy;
import com.jejari.risk.SubjectType;
import com.jejari.risk.cache.RiskScoreCache;
import com.jejari.support.FullStackInfraTest;

import tools.jackson.databind.json.JsonMapper;

/**
 * ADM-11 화면 녹화: 녹화기 API → Kafka(jejari.replay) → MongoDB(replay_session·replay_event) → 관리자 재생.
 * 입력값이 저장되지 않는지, 좌표·궤적이 잘리는지, 재전달이 중복되지 않는지, 케이스 밖 세션은 못 여는지, 삭제 보류까지 본다.
 */
@FullStackInfraTest
class ReplayInfraTest {

	static final long MEMBER_BASE = 945_000;
	static final AtomicLong NEXT = new AtomicLong(MEMBER_BASE + 1);
	static final long ADMIN = 964_901;
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
	AccessTokenIssuer tokens;
	@Autowired
	ReplayAdminService replays;

	final List<String> sessions = new ArrayList<>();
	final List<Long> cases = new ArrayList<>();

	@BeforeEach
	void setUp() {
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		// 표본(10%)으로 FULL이 되는 우연을 없앤다: 비율 0 → 모드는 위험 점수로만 정해짐
		redis.opsForHash().put("policy:cache", AppPolicy.REPLAY_FULL_RECORD_RATIO, "0");
		jdbc.update("""
				INSERT INTO admin_user (admin_id, login_id, password_hash, role, status)
				SELECT ?, 'replay-infra-test', 'x', 'VIEWER', 'ACTIVE' FROM dual
				 WHERE NOT EXISTS (SELECT 1 FROM admin_user WHERE admin_id = ?)""", ADMIN, ADMIN);
	}

	@AfterEach
	void tearDown() {
		mongo.getCollection("replay_event").deleteMany(new Document("session_id", new Document("$in", sessions)));
		mongo.getCollection("replay_session").deleteMany(new Document("_id", new Document("$in", sessions)));
		for (long c : cases) {
			jdbc.update("DELETE FROM suspicious_case WHERE case_id = ?", c);
		}
		jdbc.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
		jdbc.update("DELETE FROM admin_user WHERE admin_id = ?", ADMIN);
	}

	@Test
	void 위험_회원은_FULL로_녹화되고_입력값은_저장되지_않으며_재전달은_한_번만() throws Exception {
		long m = member();
		redis.opsForValue().set(RiskScoreCache.key(SubjectType.CI, ci(m)), "75.00"); // 70점 이상 → FULL
		String sid = session();
		mvc.perform(get("/api/v1/replay/config").header("Authorization", bearer(m, sid)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.mode").value("FULL")).andExpect(jsonPath("$.batchSec").value(5));

		long now = System.currentTimeMillis();
		List<List<Integer>> longPath = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			longPath.add(List.of(i * 50, 100 + i, 200));
		}
		List<Map<String, Object>> events = List.of(
				Map.of("seq", 1, "ts", now - 3000, "type", "PAGE_VIEW", "screen", "runs/:id/seats", "runId", 9001),
				Map.of("seq", 2, "ts", now - 2000, "type", "CLICK", "screen", "runs/:id/seats", "target", "seat-105007",
						"x", 5000, "y", 300),
				Map.of("seq", 3, "ts", now - 1500, "type", "INPUT", "screen", "runs/:id/seats", "target", "em",
						"inputLen", 7, "value", "secret@x.com"), // value는 받는 칸이 없다
				Map.of("seq", 4, "ts", now - 1000, "type", "MOUSE_PATH", "screen", "runs/:id/seats", "path", longPath),
				Map.of("seq", 5, "ts", now - 900, "type", "CLICK", "screen", "runs/:id/seats", "target", "홍길동"),
				Map.of("seq", 6, "ts", now - 3L * 86_400_000, "type", "CLICK", "screen", "x"), // 3일 전 → 버림
				Map.of("seq", 7, "ts", now, "type", "DROP_TABLE", "screen", "x")); // 모르는 종류 → 버림
		String body = JSON.writeValueAsString(Map.of("events", events));
		mvc.perform(post("/api/v1/replay/events").header("Authorization", bearer(m, sid))
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(5));
		// 같은 묶음 재전송 (네트워크 재시도)
		mvc.perform(post("/api/v1/replay/events").header("Authorization", bearer(m, sid))
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());

		List<Document> saved = waitEvents(sid, 5);
		assertThat(saved).hasSize(5);
		assertThat(saved.toString()).doesNotContain("secret");
		Document click = saved.get(1);
		assertThat(click.getString("target")).isEqualTo("seat-105007");
		assertThat(click.getInteger("x")).isEqualTo(1000); // 0~1000으로 자름
		assertThat(saved.get(2).getString("input_masked")).isEqualTo("len:7");
		assertThat(saved.get(3).getList("path", List.class)).hasSize(50); // replay.max_path_points
		assertThat(saved.get(4).containsKey("target")).isFalse(); // 한글(이름 같은 값)은 요소 ID로 받지 않음
		assertThat(saved).allSatisfy(d -> assertThat(d.getDate("expire_at")).isNotNull()); // 90일 TTL

		Document s = mongo.getCollection("replay_session").find(new Document("_id", sid)).first();
		assertThat(s.getString("recording_mode")).isEqualTo("FULL");
		assertThat(s.getString("ci_hash")).isEqualTo(ci(m));
		assertThat(((Number) s.get("event_count")).longValue()).isEqualTo(5);
		assertThat(s.getList("run_ids", Object.class)).extracting(Object::toString).containsExactly("9001");
	}

	@Test
	void 평소_회원은_SAMPLED라_마우스_궤적을_성기게_남긴다() throws Exception {
		long m = member();
		String sid = session();
		mvc.perform(get("/api/v1/replay/config").header("Authorization", bearer(m, sid)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("SAMPLED"))
				.andExpect(jsonPath("$.maxPathPoints").value(16)).andExpect(jsonPath("$.pathSampleMs").value(150));
		long now = System.currentTimeMillis();
		List<List<Integer>> path = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			path.add(List.of(i * 150, 10 + i, 10));
		}
		String body = JSON.writeValueAsString(Map.of("events", List.of(
				Map.of("seq", 1, "ts", now - 500, "type", "CLICK", "screen", "home", "x", 10, "y", 10),
				Map.of("seq", 2, "ts", now, "type", "MOUSE_PATH", "screen", "home", "path", path))));
		mvc.perform(post("/api/v1/replay/events").header("Authorization", bearer(m, sid))
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(2));
		List<Document> saved = waitEvents(sid, 2);
		assertThat(saved).extracting(d -> d.getString("type")).containsExactly("CLICK", "MOUSE_PATH");
		assertThat(saved.get(1).getList("path", List.class)).hasSize(16); // FULL(50)의 1/3
	}

	// concurrency.md §16 T22 일부 (legal_hold 뒤 도착한 이벤트는 따로 안 봄)
	@Test
	void 관리자는_케이스와_겹치는_세션만_재생하고_케이스가_열리면_삭제가_보류된다() throws Exception {
		long m = member();
		long other = member();
		String sid = session();
		String otherSid = session();
		long now = System.currentTimeMillis();
		send(m, sid, now);
		send(other, otherSid, now);
		waitEvents(sid, 1);
		waitEvents(otherSid, 1);
		long caseId = caseFor(ci(m));

		mvc.perform(get("/api/v1/admin/cases/" + caseId + "/replays").header("Authorization", admin()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].sessionId").value(sid));
		mvc.perform(get("/api/v1/admin/cases/" + caseId + "/replays/" + sid).header("Authorization", admin()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.events[0].type").value("CLICK"))
				.andExpect(jsonPath("$.events[0].expire_at").doesNotExist());
		// 다른 사람 세션은 이 케이스로 열 수 없다 (없는 세션과 같은 응답)
		mvc.perform(get("/api/v1/admin/cases/" + caseId + "/replays/" + otherSid).header("Authorization", admin()))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/admin/cases/" + caseId + "/replays").header("Authorization", "Bearer "
				+ tokens.issue(ActorType.MEMBER, m, sid).value())).andExpect(status().isForbidden());

		assertThat(replays.hold(caseId)).isEqualTo(1);
		Document s = mongo.getCollection("replay_session").find(new Document("_id", sid)).first();
		assertThat(s.getBoolean("legal_hold")).isTrue();
		assertThat(s.containsKey("expire_at")).isFalse();
		assertThat(mongo.getCollection("replay_event").countDocuments(new Document("session_id", sid)
				.append("expire_at", new Document("$exists", true)))).isZero();
		// 보류 뒤 들어온 이벤트도 TTL 없이 저장된다
		send(m, sid, now + 1000, 2);
		waitEvents(sid, 2);
		assertThat(mongo.getCollection("replay_event").countDocuments(new Document("session_id", sid)
				.append("expire_at", new Document("$exists", true)))).isZero();
		// 다른 세션은 그대로 90일 뒤 지워진다
		assertThat(mongo.getCollection("replay_session").find(new Document("_id", otherSid)).first()
				.getBoolean("legal_hold")).isFalse();
	}

	// ------------------------------------------------------------ 도우미

	void send(long m, String sid, long ts) throws Exception {
		send(m, sid, ts, 1);
	}

	void send(long m, String sid, long ts, long seq) throws Exception {
		mvc.perform(post("/api/v1/replay/events").header("Authorization", bearer(m, sid))
				.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("events", List.of(
						Map.of("seq", seq, "ts", ts, "type", "CLICK", "screen", "home", "x", 500, "y", 500))))))
				.andExpect(status().isAccepted());
	}

	/** Kafka → Mongo는 비동기라 n건이 될 때까지 최대 20초 기다린다 */
	List<Document> waitEvents(String sid, int n) throws InterruptedException {
		long until = System.currentTimeMillis() + 20_000;
		List<Document> docs = List.of();
		while (System.currentTimeMillis() < until) {
			docs = mongo.getCollection("replay_event").find(new Document("session_id", sid))
					.sort(new Document("seq", 1)).into(new ArrayList<>());
			if (docs.size() >= n) {
				Thread.sleep(500); // 재전송이 뒤늦게 더 들어오는지 잠깐 더 본다
				return mongo.getCollection("replay_event").find(new Document("session_id", sid))
						.sort(new Document("seq", 1)).into(new ArrayList<>());
			}
			Thread.sleep(200);
		}
		return docs;
	}

	long caseFor(String ci) {
		long id = jdbc.queryForObject("SELECT suspicious_case_seq.NEXTVAL FROM dual", Long.class);
		Instant now = Instant.now();
		jdbc.update("""
				INSERT INTO suspicious_case (case_id, subject_type, subject_hash, trigger_type, trigger_detail,
				  risk_score_at_open, window_from, window_to, status, opened_at)
				VALUES (?, 'CI', ?, 'RULE', '녹화 테스트', 70, ?, ?, 'OPEN', ?)""", id, ci,
				Timestamp.from(now.minusSeconds(3600)), Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now));
		cases.add(id);
		return id;
	}

	long member() {
		long id = NEXT.getAndIncrement();
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, '테스트', 'ACTIVE')",
				id, "rp" + id + "@infra-test.jejari.dev", ci(id));
		return id;
	}

	/** 실제 CI 해시와 같은 형식 (소문자 16진수 64자). 위험 점수 캐시 키가 이 형식만 받는다 */
	static String ci(long m) {
		return String.format("%064x", m);
	}

	String session() {
		String s = UUID.randomUUID().toString();
		sessions.add(s);
		return s;
	}

	String bearer(long m, String sid) {
		return "Bearer " + tokens.issue(ActorType.MEMBER, m, sid).value();
	}

	String admin() {
		return "Bearer " + tokens.issue(ActorType.ADMIN, ADMIN, UUID.randomUUID().toString(), AdminRole.VIEWER).value();
	}
}
