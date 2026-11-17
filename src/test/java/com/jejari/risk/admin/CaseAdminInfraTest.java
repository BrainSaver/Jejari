package com.jejari.risk.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
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
import com.mongodb.client.model.Filters;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ADM-05 케이스 목록·상세·타임라인: 같은 CI의 두 계정 이벤트를 한 줄로, 의심 구간 표시, 마스킹, 범위 제한 */
@FullStackInfraTest
class CaseAdminInfraTest {

	static final AtomicLong NEXT = new AtomicLong(910_001);

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MongoTemplate mongo;
	@Autowired
	AccessTokenIssuer tokens;
	@Autowired
	JsonMapper json;

	String ci;
	long older;
	long newer;
	long caseId;
	Instant wf;
	Instant wt;

	@BeforeEach
	void setUp() {
		ci = hash();
		older = member("박민수", Instant.now().minus(40, ChronoUnit.DAYS));
		newer = member("최지은", Instant.now().minus(1, ChronoUnit.DAYS));
		wt = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.SECONDS);
		wf = wt.minusSeconds(300);
		jdbc.update("""
				INSERT INTO risk_score (subject_type, subject_hash, final_score, rule_score, reasons, linked_account_cnt,
				  calculated_at, review_status) VALUES ('CI', ?, 70, 70, '[{"rule":"MULTI_ACCOUNT_SYNC"}]', 2, SYSTIMESTAMP, 'PENDING')""", ci);
		for (String s : List.of("20", "45", "70")) {
			jdbc.update("""
					INSERT INTO risk_score_history (history_id, subject_type, subject_hash, final_score, reasons, calculated_at)
					VALUES (risk_score_history_seq.NEXTVAL, 'CI', ?, ?, '[]', ?)""", ci, new BigDecimal(s),
					Timestamp.from(wt.minusSeconds(1000 - Long.parseLong(s))));
		}
		caseId = jdbc.queryForObject("SELECT suspicious_case_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO suspicious_case (case_id, subject_type, subject_hash, trigger_type, trigger_detail,
				  risk_score_at_open, window_from, window_to, status, opened_at)
				VALUES (?, 'CI', ?, 'RULE', '60초 안에 계정 2개가 좌석 선점', 70, ?, ?, 'OPEN', ?)""",
				caseId, ci, Timestamp.from(wf), Timestamp.from(wt), Timestamp.from(wt));
		jdbc.update("""
				INSERT INTO case_marker (marker_id, case_id, session_id, at_ts, marker_type, rule_code, label, severity)
				VALUES (case_marker_seq.NEXTVAL, ?, NULL, ?, 'AUTO', 'MULTI_ACCOUNT_SYNC', '60초 안에 계정 2개가 좌석 선점', 'MID')""",
				caseId, Timestamp.from(wt.minusSeconds(60)));

		mongo.getCollection("behavior_log").insertMany(List.of(
				event(older, "VIEW_SEAT", wf.minusSeconds(3600), 200),     // 구간 밖 (1시간 전)
				event(older, "SEAT_HOLD", wf.plusSeconds(10), 201),
				event(newer, "SEAT_HOLD", wf.plusSeconds(20), 409),
				event(newer, "SEAT_HOLD", wt.plusSeconds(120), 201)));    // 구간 밖 (뒤)
	}

	@AfterEach
	void tearDown() {
		jdbc.update("DELETE FROM case_marker WHERE case_id = ?", caseId);
		jdbc.update("DELETE FROM suspicious_case WHERE case_id = ?", caseId);
		jdbc.update("DELETE FROM risk_score_history WHERE subject_hash = ?", ci);
		jdbc.update("DELETE FROM risk_score WHERE subject_hash = ?", ci);
		mongo.getCollection("behavior_log").deleteMany(Filters.in("member_id", List.of(older, newer)));
		jdbc.update("DELETE FROM member WHERE member_id IN (?, ?)", older, newer);
	}

	@Test
	void 목록은_상태로_거르고_현재_점수를_함께_보여준다() throws Exception {
		JsonNode page = getJson("/api/v1/admin/cases?status=OPEN&size=100");
		JsonNode mine = null;
		for (JsonNode n : page.get("items")) {
			if (n.get("caseId").asLong() == caseId) mine = n;
		}
		assertThat(mine).isNotNull();
		assertThat(mine.get("currentScore").decimalValue()).isEqualByComparingTo("70");
		assertThat(mine.get("reviewStatus").asString()).isEqualTo("PENDING");

		JsonNode closed = getJson("/api/v1/admin/cases?status=DISMISSED&size=100");
		closed.get("items").forEach(n -> assertThat(n.get("caseId").asLong()).isNotEqualTo(caseId));
		mvc.perform(get("/api/v1/admin/cases?status=HACKED").header("Authorization", admin()))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 상세는_연결_계정을_가입_순으로_가려서_보여주고_점수_이력은_시간순() throws Exception {
		String raw = body("/api/v1/admin/cases/" + caseId);
		assertThat(raw).doesNotContain("박민수").doesNotContain("최지은");
		JsonNode d = json.readTree(raw);
		assertThat(d.get("accounts")).hasSize(2);
		assertThat(d.get("accounts").get(0).get("label").asString()).isEqualTo("계정 1");
		assertThat(d.get("accounts").get(0).get("memberId").asLong()).isEqualTo(older);
		assertThat(d.get("accounts").get(0).get("maskedName").asString()).isEqualTo("박*수");
		assertThat(d.get("markers").get(0).get("ruleCode").asString()).isEqualTo("MULTI_ACCOUNT_SYNC");
		List<String> scores = new ArrayList<>();
		d.get("scoreHistory").forEach(p -> scores.add(p.get("score").decimalValue().stripTrailingZeros().toPlainString()));
		assertThat(scores).containsExactly("20", "45", "70");
	}

	@Test
	void 타임라인은_두_계정의_구간_이벤트를_시간순으로_합친다() throws Exception {
		JsonNode t = getJson("/api/v1/admin/cases/" + caseId + "/timeline");
		JsonNode ev = t.get("events");
		assertThat(ev).hasSize(2); // 기본 범위 = 의심 구간
		assertThat(ev.get(0).get("account").asString()).isEqualTo("계정 1");
		assertThat(ev.get(1).get("account").asString()).isEqualTo("계정 2");
		assertThat(ev.get(1).get("responseStatus").asInt()).isEqualTo(409);
		assertThat(ev.get(0).get("inWindow").asBoolean()).isTrue();
		assertThat(t.get("markers")).hasSize(1);
		assertThat(t.get("truncated").asBoolean()).isFalse();

		// 범위를 넓히면 구간 밖 이벤트도 보이고 inWindow=false
		String from = wf.minusSeconds(7200).toString();
		String to = wt.plusSeconds(600).toString();
		JsonNode wide = getJson("/api/v1/admin/cases/" + caseId + "/timeline?from=" + from + "&to=" + to);
		assertThat(wide.get("events")).hasSize(4);
		assertThat(wide.get("events").get(0).get("inWindow").asBoolean()).isFalse();
		assertThat(wide.get("events").get(3).get("inWindow").asBoolean()).isFalse();
	}

	@Test
	void 범위가_24시간을_넘거나_거꾸로면_400_없는_케이스는_404_회원은_403() throws Exception {
		String from = wt.minus(25, ChronoUnit.HOURS).toString();
		mvc.perform(get("/api/v1/admin/cases/" + caseId + "/timeline?from=" + from + "&to=" + wt).header("Authorization", admin()))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/admin/cases/" + caseId + "/timeline?from=" + wt + "&to=" + wf).header("Authorization", admin()))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/admin/cases/" + caseId + "/timeline?from=yesterday").header("Authorization", admin()))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/admin/cases/999999999").header("Authorization", admin()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));
		mvc.perform(get("/api/v1/admin/cases/" + caseId).header("Authorization",
				"Bearer " + tokens.issue(ActorType.MEMBER, older, UUID.randomUUID().toString()).value()))
				.andExpect(status().isForbidden());
	}

	// ------------------------------------------------------------ 도우미

	String admin() {
		return "Bearer " + tokens.issue(ActorType.ADMIN, 1L, UUID.randomUUID().toString(), AdminRole.VIEWER).value();
	}

	String body(String url) throws Exception {
		return mvc.perform(get(url).header("Authorization", admin())).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
	}

	JsonNode getJson(String url) throws Exception {
		return json.readTree(body(url));
	}

	static String hash() {
		byte[] b = new byte[32];
		new SecureRandom().nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	long member(String name, Instant createdAt) {
		long id = NEXT.getAndIncrement();
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status, created_at) VALUES (?, ?, ?, ?, 'ACTIVE', ?)",
				id, "case" + id + "@infra-test.jejari.dev", ci, name, Timestamp.from(createdAt));
		return id;
	}

	Document event(long memberId, String type, Instant ts, int status) {
		return new Document("event_id", UUID.randomUUID().toString()).append("member_id", memberId).append("ci_hash", ci)
				.append("event_type", type).append("ts", Date.from(ts)).append("run_id", 9001L)
				.append("response_status", status).append("channel", "WEB")
				.append("meta", new Document("seatIds", List.of(101L)))
				.append("expire_at", Date.from(ts.plus(1, ChronoUnit.DAYS)));
	}
}
