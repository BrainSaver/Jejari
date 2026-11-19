package com.jejari.risk.model;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jejari.auth.ActorType;
import com.jejari.auth.AdminRole;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.support.FullStackInfraTest;

/**
 * A-09 모델 관리: 버전마다 점수 수·봇 쪽으로 본 수·관리자 판정과 맞은 수, 두 버전 비교, 사용 중 버전 바꾸기(SUPERVISOR).
 * 사용 중으로 바꿔도 ENSEMBLE은 하나만 Y. 점수가 없는 버전은 켤 수 없다.
 */
@FullStackInfraTest
class ModelAdminInfraTest {

	static final long MEMBER_BASE = 948_000;
	static final AtomicLong NEXT = new AtomicLong(MEMBER_BASE + 1);
	static final long ADMIN = 965_902;
	static final String A = "ens-test-a";
	static final String B = "ens-test-b";
	static final String EMPTY = "ens-test-e";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MongoTemplate mongo;
	@Autowired
	AccessTokenIssuer tokens;

	final List<Long> cases = new ArrayList<>();
	List<String> wasActive;

	@BeforeEach
	void setUp() {
		wasActive = jdbc.queryForList("SELECT model_version FROM model_run WHERE algorithm = 'ENSEMBLE' AND is_active = 'Y'",
				String.class);
		for (String v : List.of(A, B, EMPTY)) {
			jdbc.update("""
					INSERT INTO model_run (model_version, algorithm, params, train_source, trained_at, threshold, is_active)
					VALUES (?, 'ENSEMBLE', '{}', 'test', SYSTIMESTAMP, 90, 'N')""", v);
		}
		jdbc.update("UPDATE model_run SET is_active = CASE WHEN model_version = ? THEN 'Y' ELSE 'N' END WHERE algorithm = 'ENSEMBLE'", A);
		jdbc.update("""
				INSERT INTO admin_user (admin_id, login_id, password_hash, role, status)
				SELECT ?, 'model-admin-test', 'x', 'SUPERVISOR', 'ACTIVE' FROM dual
				 WHERE NOT EXISTS (SELECT 1 FROM admin_user WHERE admin_id = ?)""", ADMIN, ADMIN);
	}

	@AfterEach
	void tearDown() {
		mongo.getCollection("model_score").deleteMany(new Document("model_version", new Document("$in", List.of(A, B, EMPTY))));
		for (long c : cases) {
			jdbc.update("DELETE FROM case_session WHERE case_id = ?", c);
			jdbc.update("DELETE FROM suspicious_case WHERE case_id = ?", c);
		}
		jdbc.update("DELETE FROM model_run WHERE model_version IN (?, ?, ?)", A, B, EMPTY);
		jdbc.update("UPDATE model_run SET is_active = 'N' WHERE algorithm = 'ENSEMBLE'");
		for (String v : wasActive) {
			jdbc.update("UPDATE model_run SET is_active = 'Y' WHERE model_version = ?", v);
		}
		jdbc.update("DELETE FROM auth_session WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
		jdbc.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
		jdbc.update("DELETE FROM admin_user WHERE admin_id = ?", ADMIN);
	}

	@Test
	void 버전마다_점수와_판정_맞춤을_세고_두_버전을_비교하고_사용_중을_하나만_바꾼다() throws Exception {
		long bot = member();      // 관리자가 봇으로 판정
		long human = member();    // 관리자가 사람으로 판정
		long both = member();     // 판정 없음, 두 버전 모두 봇 쪽
		judged(bot, "MACRO");
		judged(human, "NORMAL");
		// A: bot·human·both 봇 쪽 (human은 오탐)   B: bot·both만 봇 쪽
		score(A, bot, 99, "SPEED");
		score(A, human, 95, "MULTI_ACCOUNT");
		score(A, both, 97, "EVASIVE");
		score(B, bot, 98, "SPEED");
		score(B, human, 40, "MULTI_ACCOUNT");
		score(B, both, 93, "EVASIVE");

		mvc.perform(get("/api/v1/admin/models").header("Authorization", token(AdminRole.VIEWER))).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.version == 'ens-test-a')].active").value(true))
				.andExpect(jsonPath("$[?(@.version == 'ens-test-a')].scored").value(3))
				.andExpect(jsonPath("$[?(@.version == 'ens-test-a')].flagged").value(3))
				.andExpect(jsonPath("$[?(@.version == 'ens-test-a')].verdicts.botsFlagged").value(1))
				.andExpect(jsonPath("$[?(@.version == 'ens-test-a')].verdicts.humansFlagged").value(1)) // 오탐 1
				.andExpect(jsonPath("$[?(@.version == 'ens-test-a')].types.EVASIVE").value(1))
				.andExpect(jsonPath("$[?(@.version == 'ens-test-b')].flagged").value(2))
				.andExpect(jsonPath("$[?(@.version == 'ens-test-b')].verdicts.humansFlagged").value(0));

		mvc.perform(get("/api/v1/admin/models/compare").param("a", A).param("b", B).header("Authorization", token(AdminRole.VIEWER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.both").value(2))
				.andExpect(jsonPath("$.onlyA").value(1))
				.andExpect(jsonPath("$.onlyB").value(0))
				.andExpect(jsonPath("$.onlyAList[0].memberId").value(human))
				.andExpect(jsonPath("$.onlyAList[0].verdict").value("HUMAN"));

		// 보기 권한으로는 못 바꾸고, 점수가 없는 버전은 못 켜고, 켜면 ENSEMBLE은 하나만 Y
		mvc.perform(post("/api/v1/admin/models/" + B + "/activate").header("Authorization", token(AdminRole.VIEWER)))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/admin/models/" + EMPTY + "/activate").header("Authorization", token(AdminRole.SUPERVISOR)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MODEL_NOT_SCORED"));
		mvc.perform(post("/api/v1/admin/models/nope-1/activate").header("Authorization", token(AdminRole.SUPERVISOR)))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/admin/models/" + B + "/activate").header("Authorization", token(AdminRole.SUPERVISOR)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.version == 'ens-test-b')].active").value(true))
				.andExpect(jsonPath("$[?(@.version == 'ens-test-a')].active").value(false));
		Integer on = jdbc.queryForObject("SELECT COUNT(*) FROM model_run WHERE algorithm = 'ENSEMBLE' AND is_active = 'Y'", Integer.class);
		org.junit.jupiter.api.Assertions.assertEquals(1, on);
	}

	@Test
	void 무작위_점검_표본은_최근_활동_계정을_ADMIN_케이스로_열고_보기_권한은_못_연다() throws Exception {
		List<Long> members = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			long m = member();
			members.add(m);
			mongo.getCollection("behavior_log").insertOne(new Document("member_id", m).append("event_type", "VIEW_TRAIN")
					.append("ts", new Date()).append("session_id", UUID.randomUUID().toString()).append("ci_hash", String.format("%064x", m)));
		}
		try {
			mvc.perform(post("/api/v1/admin/models/audit-sample").header("Authorization", token(AdminRole.VIEWER))
					.contentType("application/json").content("{\"count\":2}")).andExpect(status().isForbidden());
			mvc.perform(post("/api/v1/admin/models/audit-sample").header("Authorization", token(AdminRole.REVIEWER))
					.contentType("application/json").content("{\"count\":99}")).andExpect(status().isBadRequest());
			String body = mvc.perform(post("/api/v1/admin/models/audit-sample").header("Authorization", token(AdminRole.REVIEWER))
					.contentType("application/json").content("{\"count\":2}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.opened.length()").value(2))
					.andReturn().getResponse().getContentAsString();
			for (String id : body.replaceAll(".*\"opened\":\\[([0-9,]*)\\].*", "$1").split(",")) {
				cases.add(Long.parseLong(id));
			}
			for (long c : cases) {
				org.junit.jupiter.api.Assertions.assertEquals("ADMIN",
						jdbc.queryForObject("SELECT trigger_type FROM suspicious_case WHERE case_id = ?", String.class, c));
				org.junit.jupiter.api.Assertions.assertTrue(jdbc.queryForObject(
						"SELECT trigger_detail FROM suspicious_case WHERE case_id = ?", String.class, c).startsWith("무작위 점검"));
			}
			mvc.perform(get("/api/v1/admin/models/audit").header("Authorization", token(AdminRole.VIEWER)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.open").isNumber());
		} finally {
			mongo.getCollection("behavior_log").deleteMany(new Document("member_id", new Document("$in", members)));
			for (long c : cases) {
				jdbc.update("DELETE FROM case_marker WHERE case_id = ?", c);
			}
		}
	}

	void score(String version, long memberId, double score, String type) {
		mongo.getCollection("model_score").insertOne(new Document("member_id", memberId).append("model_version", version)
				.append("score", score).append("by", "GBM").append("reasons", List.of()).append("scored_at", new Date())
				.append("type", type).append("type_prob", 0.9));
	}

	void judged(long memberId, String verdict) {
		long id = jdbc.queryForObject("SELECT suspicious_case_seq.NEXTVAL FROM dual", Long.class);
		Instant now = Instant.now();
		String ci = String.format("%064x", memberId);
		jdbc.update("""
				INSERT INTO suspicious_case (case_id, subject_type, subject_hash, trigger_type, trigger_detail,
				  risk_score_at_open, window_from, window_to, status, opened_at, verdict)
				VALUES (?, 'CI', ?, 'RULE', '모델 관리 테스트', 70, ?, ?, ?, ?, ?)""", id, ci,
				Timestamp.from(now.minusSeconds(3600)), Timestamp.from(now), "NORMAL".equals(verdict) ? "DISMISSED" : "CONFIRMED",
				Timestamp.from(now), verdict);
		String session = UUID.randomUUID().toString();   // case_session.session_id는 auth_session FK
		jdbc.update("""
				INSERT INTO auth_session (session_id, member_id, actor_type, refresh_token_hash, token_family_id, expires_at)
				VALUES (?, ?, 'MEMBER', ?, ?, SYSTIMESTAMP + INTERVAL '1' DAY)""",
				session, memberId, String.format("%064x", memberId + 7), UUID.randomUUID().toString());
		jdbc.update("INSERT INTO case_session (case_id, session_id, member_id, link_reason) VALUES (?, ?, ?, 'TRIGGER')",
				id, session, memberId);
		cases.add(id);
	}

	long member() {
		long id = NEXT.getAndIncrement();
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, '테스트', 'ACTIVE')",
				id, "ma" + id + "@infra-test.jejari.dev", String.format("%064x", id));
		return id;
	}

	String token(AdminRole role) {
		return "Bearer " + tokens.issue(ActorType.ADMIN, ADMIN, UUID.randomUUID().toString(), role).value();
	}
}
