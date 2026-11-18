package com.jejari.risk.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jejari.auth.ActorType;
import com.jejari.auth.AdminRole;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.common.pii.Encrypted;
import com.jejari.common.pii.PiiCipher;
import com.jejari.common.pii.PiiField;
import com.jejari.support.FullStackInfraTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ADM-04 위험 사용자 목록: 권한, 점수 순·필터, 마스킹(원문이 응답에 없음), 마커·케이스 표시 */
@FullStackInfraTest
class RiskAdminInfraTest {

	static final AtomicLong NEXT = new AtomicLong(920_001);
	static final String URL = "/api/v1/admin/risks";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenIssuer tokens;
	@Autowired
	PiiCipher cipher;
	@Autowired
	JsonMapper json;

	final List<String> hashes = new ArrayList<>();
	final List<Long> members = new ArrayList<>();

	@AfterEach
	void tearDown() {
		for (String h : hashes) {
			jdbc.update("DELETE FROM case_marker WHERE case_id IN (SELECT case_id FROM suspicious_case WHERE subject_hash = ?)", h);
			jdbc.update("DELETE FROM suspicious_case WHERE subject_hash = ?", h);
			jdbc.update("DELETE FROM risk_score WHERE subject_hash = ?", h);
			jdbc.update("DELETE FROM payment_method WHERE method_hash = ?", h);
		}
		for (long m : members) {
			jdbc.update("DELETE FROM member WHERE member_id = ?", m);
		}
	}

	@Test
	void 관리자만_볼_수_있다() throws Exception {
		mvc.perform(get(URL)).andExpect(status().isUnauthorized());
		mvc.perform(get(URL).header("Authorization", "Bearer "
				+ tokens.issue(ActorType.MEMBER, 1L, UUID.randomUUID().toString()).value()))
				.andExpect(status().isForbidden());
		mvc.perform(get(URL).header("Authorization", admin())).andExpect(status().isOk());
	}

	@Test
	void 이름_전화_이메일은_가려서만_나가고_원문은_응답_어디에도_없다() throws Exception {
		String ci = hash();
		long m = member(ci, "홍길동", "01012345678", "gildong@example.com");
		score("CI", ci, "91.5", "PENDING", "[{\"rule\":\"FAST_CLICK\",\"observed\":30,\"threshold\":20,\"points\":25,\"severity\":\"MID\"},"
				+ "{\"rule\":\"NEW_ACCOUNT\",\"observed\":0,\"threshold\":7,\"points\":5,\"severity\":\"LOW\"}]", 3);
		long caseId = openCase("CI", ci);

		String body = body(URL + "?type=CI&minScore=91.5&maxScore=91.5&size=100");
		assertThat(body).doesNotContain("홍길동").doesNotContain("01012345678").doesNotContain("1234-5678")
				.doesNotContain("gildong@");
		JsonNode row = find(body, ci);
		assertThat(row.get("holder").get("name").asString()).isEqualTo("홍*동");
		assertThat(row.get("holder").get("phone").asString()).isEqualTo("010-****-5678");
		assertThat(row.get("holder").get("email").asString()).doesNotContain("gildong").contains("@");
		assertThat(row.get("markers")).hasSize(1);
		assertThat(row.get("markers").get(0).asString()).isEqualTo("FAST_CLICK"); // 보조 신호(NEW_ACCOUNT)는 마커 아님
		assertThat(row.get("linkedAccounts").asLong()).isEqualTo(3);
		assertThat(row.get("latestCase").get("caseId").asLong()).isEqualTo(caseId);
		assertThat(row.get("latestCase").get("status").asString()).isEqualTo("OPEN");
		assertThat(m).isPositive();
	}

	@Test
	void 복호화가_안_되는_번호는_오류_대신_가린다() throws Exception {
		String ci = hash();
		long m = member(ci, "김철수", null, "cs@example.com");
		jdbc.update("UPDATE member SET phone_enc = 'not-a-cipher', enc_key_id = 'k1' WHERE member_id = ?", m);
		score("CI", ci, "77.7", "NONE", "[]", 1);
		JsonNode row = find(body(URL + "?type=CI&minScore=77.7&maxScore=77.7&size=100"), ci);
		assertThat(row.get("holder").get("phone").asString()).isEqualTo("***");
	}

	@Test
	void 결제_수단은_카드_끝_4자리만() throws Exception {
		String card = hash();
		long m = member(hash(), "이영희", "01098765432", "yh@example.com");
		jdbc.update("INSERT INTO payment_method (method_id, member_id, method_hash, method_type, card_last4) VALUES (?, ?, ?, 'CARD', '4242')",
				NEXT.getAndIncrement(), m, card);
		score("PAYMENT", card, "66.6", "NONE", "[]", 1);
		JsonNode row = find(body(URL + "?type=PAYMENT&minScore=66.6&maxScore=66.6"), card);
		assertThat(row.get("holder").get("cardLast4").asString()).isEqualTo("****4242");
		assertThat(row.get("holder").get("name").asString()).isEqualTo("이*희");
	}

	@Test
	void 점수_순으로_거르고_페이지를_넘긴다() throws Exception {
		List<String> mine = new ArrayList<>();
		for (String s : List.of("55.10", "55.30", "55.20")) {
			String h = hash();
			score("DEVICE", h, s, "NONE", "[]", 1);
			mine.add(h);
		}
		score("DEVICE", hash(), "55.40", "CLEARED", "[]", 1); // 상태 필터로 빠짐

		JsonNode page = json.readTree(body(URL + "?type=DEVICE&status=NONE&minScore=55.1&maxScore=55.3&size=2"));
		assertThat(page.get("items")).hasSize(2);
		assertThat(page.get("hasNext").asBoolean()).isTrue();
		assertThat(page.get("items").get(0).get("finalScore").decimalValue()).isEqualByComparingTo("55.30");
		assertThat(page.get("items").get(1).get("finalScore").decimalValue()).isEqualByComparingTo("55.20");
		JsonNode next = json.readTree(body(URL + "?type=DEVICE&status=NONE&minScore=55.1&maxScore=55.3&size=2&page=1"));
		assertThat(next.get("items")).hasSize(1);
		assertThat(next.get("hasNext").asBoolean()).isFalse();
	}

	@Test
	void 잘못된_필터는_400() throws Exception {
		for (String q : List.of("?status=HACKED", "?type=EMAIL", "?minScore=-1", "?maxScore=101", "?minScore=90&maxScore=10",
				"?size=0", "?size=101", "?page=-1")) {
			mvc.perform(get(URL + q).header("Authorization", admin())).andExpect(status().isBadRequest());
		}
	}

	// ------------------------------------------------------------ 도우미

	String admin() {
		return "Bearer " + tokens.issue(ActorType.ADMIN, 1L, UUID.randomUUID().toString(), AdminRole.VIEWER).value();
	}

	String body(String url) throws Exception {
		return mvc.perform(get(url).header("Authorization", admin())).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
	}

	JsonNode find(String body, String hash) {
		Map<String, JsonNode> byHash = new HashMap<>();
		json.readTree(body).get("items").forEach(n -> byHash.put(n.get("subjectHash").asString(), n));
		assertThat(byHash).containsKey(hash);
		return byHash.get(hash);
	}

	String hash() {
		byte[] b = new byte[32];
		new SecureRandom().nextBytes(b);
		String h = HexFormat.of().formatHex(b);
		hashes.add(h);
		return h;
	}

	long member(String ci, String name, String phone, String email) {
		long id = NEXT.getAndIncrement();
		String enc = null;
		String key = null;
		if (phone != null) {
			Encrypted e = cipher.encrypt(phone, PiiField.MEMBER_PHONE, id);
			enc = e.cipherText();
			key = e.keyId();
		}
		jdbc.update("""
				INSERT INTO member (member_id, email, ci_hash, name, phone_enc, enc_key_id, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')""", id, email.replace("@", "+" + id + "@"), ci, name, enc, key);
		members.add(id);
		return id;
	}

	void score(String type, String hash, String score, String review, String reasons, long linked) {
		jdbc.update("""
				INSERT INTO risk_score (subject_type, subject_hash, final_score, rule_score, reasons, linked_account_cnt,
				  calculated_at, review_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
				type, hash, new BigDecimal(score), new BigDecimal(score), reasons, linked, Timestamp.from(Instant.now()),
				review);
	}

	long openCase(String type, String hash) {
		long id = jdbc.queryForObject("SELECT suspicious_case_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO suspicious_case (case_id, subject_type, subject_hash, trigger_type, status, opened_at)
				VALUES (?, ?, ?, 'RULE', 'OPEN', SYSTIMESTAMP)""", id, type, hash);
		return id;
	}
}
