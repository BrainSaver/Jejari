package com.jejari.risk.model;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jejari.auth.ActorType;
import com.jejari.auth.AdminRole;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.support.FullStackInfraTest;

/**
 * RISK-05 모델 보조 의견: model_score(계정 단위) → 케이스의 연결 계정만, 사용 중인 합성 모델 버전만, 높은 순으로.
 * 정답 라벨은 적재하지 않으므로 응답에도 없다.
 */
@FullStackInfraTest
class ModelOpinionInfraTest {

	static final long MEMBER_BASE = 947_000;
	static final AtomicLong NEXT = new AtomicLong(MEMBER_BASE + 1);
	static final long ADMIN = 965_901;
	static final String VERSION = "ens-test-01";

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
		jdbc.update("UPDATE model_run SET is_active = 'N' WHERE algorithm = 'ENSEMBLE'");
		jdbc.update("""
				INSERT INTO model_run (model_version, algorithm, params, train_source, trained_at, threshold, is_active)
				VALUES (?, 'ENSEMBLE', '{}', 'test', SYSTIMESTAMP, 90, 'N')""", VERSION);
		jdbc.update("""
				INSERT INTO admin_user (admin_id, login_id, password_hash, role, status)
				SELECT ?, 'model-infra-test', 'x', 'VIEWER', 'ACTIVE' FROM dual
				 WHERE NOT EXISTS (SELECT 1 FROM admin_user WHERE admin_id = ?)""", ADMIN, ADMIN);
	}

	@AfterEach
	void tearDown() {
		mongo.getCollection("model_score").deleteMany(new Document("model_version", VERSION));
		for (long c : cases) {
			jdbc.update("DELETE FROM suspicious_case WHERE case_id = ?", c);
		}
		jdbc.update("DELETE FROM model_run WHERE model_version = ?", VERSION);
		for (String v : wasActive) {
			jdbc.update("UPDATE model_run SET is_active = 'Y' WHERE model_version = ?", v);
		}
		jdbc.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
		jdbc.update("DELETE FROM admin_user WHERE admin_id = ?", ADMIN);
	}

	@Test
	void 케이스_계정의_점수만_높은_순으로_사용_중인_모델이_없으면_숨긴다() throws Exception {
		String ci = String.format("%064x", NEXT.get() + 7);
		long a = member(ci);
		long b = member(ci);
		long c = member(ci); // 점수 없음
		long stranger = member(String.format("%064x", 1L));
		score(a, 42.0, "GBM", List.of("0.2초 미만 비율 0.31 (사람 0.00~0.05)"));
		score(b, 97.3, "GRU", List.of("행동 순서가 학습한 봇과 닮음(GRU)", "선점 전 둘러보기 0 (사람 2~9)"));
		score(stranger, 99.9, "GBM", List.of("남의 점수"));
		long caseId = caseFor(ci);
		String url = "/api/v1/admin/cases/" + caseId + "/model";

		// 사용 중인 모델이 없으면 숨김 (이유와 함께)
		mvc.perform(get(url).header("Authorization", admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.shown").value(false)).andExpect(jsonPath("$.accounts.length()").value(0));

		jdbc.update("UPDATE model_run SET is_active = 'Y' WHERE model_version = ?", VERSION);
		mvc.perform(get(url).header("Authorization", admin())).andExpect(status().isOk())
				.andExpect(jsonPath("$.shown").value(true))
				.andExpect(jsonPath("$.modelVersion").value(VERSION))
				.andExpect(jsonPath("$.threshold").value(90))
				.andExpect(jsonPath("$.accounts.length()").value(2)) // 남의 계정 점수는 안 섞임
				.andExpect(jsonPath("$.accounts[0].memberId").value(b)) // 높은 순
				.andExpect(jsonPath("$.accounts[0].overThreshold").value(true))
				.andExpect(jsonPath("$.accounts[0].by").value("GRU"))
				.andExpect(jsonPath("$.accounts[0].label").value("계정 2"))
				.andExpect(jsonPath("$.accounts[0].reasons[1]").value("선점 전 둘러보기 0 (사람 2~9)"))
				.andExpect(jsonPath("$.accounts[0].type").value("EVASIVE")) // 봇 쪽으로 본 계정만 닮은 유형을 보여 준다
				.andExpect(jsonPath("$.accounts[0].typeProb").value(0.82))
				.andExpect(jsonPath("$.accounts[1].memberId").value(a))
				.andExpect(jsonPath("$.accounts[1].overThreshold").value(false))
				.andExpect(jsonPath("$.accounts[1].type").doesNotExist()) // 사람 쪽이면 수법 이름을 붙이지 않음
				.andExpect(jsonPath("$.accounts[1].typeProb").doesNotExist())
				.andExpect(jsonPath("$.accounts[0].label").exists())
				.andExpect(jsonPath("$.accounts[0].strategy").doesNotExist()) // 정답 라벨 없음
				.andExpect(jsonPath("$.unscored").value(1)); // c
		mvc.perform(get("/api/v1/admin/cases/999999999/model").header("Authorization", admin()))
				.andExpect(status().isNotFound());
	}

	void score(long memberId, double score, String by, List<String> reasons) {
		mongo.getCollection("model_score").insertOne(new Document("member_id", memberId).append("model_version", VERSION)
				.append("score", score).append("by", by).append("scores", Map.of("gbm", score))
				.append("reasons", reasons).append("source", "test").append("scored_at", new Date())
				.append("type", "EVASIVE").append("type_prob", 0.82)); // 닮은 유형 (ERD v29) — 모든 계정에 매겨져 있다
	}

	long caseFor(String ci) {
		long id = jdbc.queryForObject("SELECT suspicious_case_seq.NEXTVAL FROM dual", Long.class);
		Instant now = Instant.now();
		jdbc.update("""
				INSERT INTO suspicious_case (case_id, subject_type, subject_hash, trigger_type, trigger_detail,
				  risk_score_at_open, window_from, window_to, status, opened_at)
				VALUES (?, 'CI', ?, 'RULE', '모델 테스트', 70, ?, ?, 'OPEN', ?)""", id, ci,
				Timestamp.from(now.minusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
		cases.add(id);
		return id;
	}

	long member(String ci) {
		long id = NEXT.getAndIncrement();
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, '테스트', 'ACTIVE')",
				id, "ml" + id + "@infra-test.jejari.dev", ci);
		return id;
	}

	String admin() {
		return "Bearer " + tokens.issue(ActorType.ADMIN, ADMIN, UUID.randomUUID().toString(), AdminRole.VIEWER).value();
	}
}
