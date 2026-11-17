package com.jejari.risk.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
import com.jejari.auth.AdminRole;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.common.pii.Encrypted;
import com.jejari.common.pii.PiiCipher;
import com.jejari.common.pii.PiiField;
import com.jejari.risk.SubjectType;
import com.jejari.risk.cache.RiskScoreCache;
import com.jejari.support.FullStackInfraTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ADM-06 판정·조치와 마스킹 해제: 회원 상태·세션·점수·캐시·케이스·감사 기록, 권한, 입력 검증, 동시 판정 */
@FullStackInfraTest
class CaseActionInfraTest {

	static final AtomicLong NEXT = new AtomicLong(900_001);
	static final long ADMIN = 900_999;

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	AccessTokenIssuer tokens;
	@Autowired
	PiiCipher cipher;
	@Autowired
	JsonMapper json;

	String ci;
	long active;
	long restricted;
	long banned;
	long caseId;

	@BeforeEach
	void setUp() {
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		jdbc.update("DELETE FROM admin_user WHERE admin_id = ?", ADMIN);
		jdbc.update("INSERT INTO admin_user (admin_id, login_id, password_hash, role, status) VALUES (?, ?, 'x', 'SUPERVISOR', 'ACTIVE')",
				ADMIN, "case-action-test");
		ci = hash();
		active = member("정하늘", "01011112222", "ACTIVE", 30);
		restricted = member("윤바다", "01033334444", "RESTRICTED", 20);
		banned = member("강산", "01055556666", "BANNED", 10);
		jdbc.update("""
				INSERT INTO risk_score (subject_type, subject_hash, final_score, rule_score, reasons, calculated_at, review_status)
				VALUES ('CI', ?, 85, 85, '[{"rule":"FAST_CLICK"}]', SYSTIMESTAMP, 'PENDING')""", ci);
		redis.opsForValue().set(RiskScoreCache.key(SubjectType.CI, ci), "85.00");
		caseId = jdbc.queryForObject("SELECT suspicious_case_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO suspicious_case (case_id, subject_type, subject_hash, trigger_type, status, opened_at)
				VALUES (?, 'CI', ?, 'RISK_THRESHOLD', 'OPEN', SYSTIMESTAMP)""", caseId, ci);
		session(active);
	}

	@AfterEach
	void tearDown() {
		jdbc.update("DELETE FROM suspicious_case WHERE case_id = ?", caseId);
		jdbc.update("DELETE FROM admin_action WHERE subject_hash = ?", ci);
		jdbc.update("DELETE FROM risk_score_history WHERE subject_hash = ?", ci);
		jdbc.update("DELETE FROM risk_score WHERE subject_hash = ?", ci);
		jdbc.update("DELETE FROM auth_session WHERE member_id IN (?, ?, ?)", active, restricted, banned);
		jdbc.update("DELETE FROM member WHERE member_id IN (?, ?, ?)", active, restricted, banned);
		jdbc.update("DELETE FROM admin_user WHERE admin_id = ?", ADMIN);
	}

	// ------------------------------------------------------------ 판정·조치

	@Test
	void 매크로_판정_이용제한은_활성_계정을_제한하고_기록을_남긴다() throws Exception {
		mvc.perform(act("MACRO", "RESTRICT", "0.1초 간격 좌석 클릭 28회", AdminRole.REVIEWER)).andExpect(status().isOk())
				.andExpect(jsonPath("$.caseStatus").value("CONFIRMED")).andExpect(jsonPath("$.membersChanged").value(1));

		assertThat(memberStatus(active)).isEqualTo("RESTRICTED");
		assertThat(memberStatus(banned)).isEqualTo("BANNED");
		assertThat(one("SELECT status || '/' || verdict FROM suspicious_case WHERE case_id = ?", caseId))
				.isEqualTo("CONFIRMED/MACRO");
		assertThat(one("SELECT review_status FROM risk_score WHERE subject_hash = ?", ci)).isEqualTo("CONFIRMED");
		assertThat(one("SELECT action_type || ':' || memo FROM admin_action WHERE subject_hash = ?", ci))
				.isEqualTo("RESTRICT:0.1초 간격 좌석 클릭 28회");
		assertThat(redis.opsForValue().get(RiskScoreCache.key(SubjectType.CI, ci))).isEqualTo("85.00");
	}

	@Test
	void 오탐_해제는_점수를_0으로_캐시를_지우고_제한만_풀고_정지는_그대로() throws Exception {
		mvc.perform(act("NORMAL", "CLEAR", "정상 사용자 확인 (가족 공용 기기)", AdminRole.REVIEWER))
				.andExpect(status().isOk()).andExpect(jsonPath("$.caseStatus").value("DISMISSED"));

		assertThat(one("SELECT TO_CHAR(final_score) || '/' || review_status FROM risk_score WHERE subject_hash = ?", ci))
				.isEqualTo("0/CLEARED");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM risk_score_history WHERE subject_hash = ? AND final_score = 0",
				Integer.class, ci)).isOne();
		assertThat(redis.hasKey(RiskScoreCache.key(SubjectType.CI, ci))).isFalse(); // 대기열 패널티 즉시 해제
		assertThat(memberStatus(restricted)).isEqualTo("ACTIVE");
		assertThat(memberStatus(banned)).isEqualTo("BANNED");
		// 배치가 이 시각 이후 증거만 세도록 calculated_at = CLEAR 시각
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM risk_score s JOIN admin_action a ON a.subject_hash = s.subject_hash
				 WHERE s.subject_hash = ? AND a.action_type = 'CLEAR' AND s.calculated_at = a.acted_at""", Integer.class, ci)).isOne();
	}

	@Test
	void 정지는_계정을_정지하고_로그인_세션을_끊는다() throws Exception {
		mvc.perform(act("RESELLER", "BAN", "같은 좌석 반복 선점 후 재판매 정황", AdminRole.REVIEWER)).andExpect(status().isOk());
		assertThat(memberStatus(active)).isEqualTo("BANNED");
		assertThat(memberStatus(restricted)).isEqualTo("BANNED");
		assertThat(one("SELECT revoke_reason FROM auth_session WHERE member_id = ?", active)).isEqualTo("ADMIN");
	}

	@Test
	void 판정과_맞지_않는_조치_사유_없음_닫힌_케이스는_거절() throws Exception {
		mvc.perform(act("NORMAL", "BAN", "사유 충분히 길게", AdminRole.REVIEWER)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ACTION_NOT_ALLOWED"));
		mvc.perform(act("MACRO", "CLEAR", "사유 충분히 길게", AdminRole.REVIEWER)).andExpect(status().isConflict());
		mvc.perform(act("MACRO", "CONFIRM", "  짧음 ", AdminRole.REVIEWER)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ACTION_REASON_REQUIRED"));
		mvc.perform(act("HACKER", "CONFIRM", "사유 충분히 길게", AdminRole.REVIEWER)).andExpect(status().isBadRequest());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action WHERE subject_hash = ?", Integer.class, ci)).isZero();

		mvc.perform(act("MACRO", "CONFIRM", "사유 충분히 길게", AdminRole.REVIEWER)).andExpect(status().isOk());
		mvc.perform(act("MACRO", "BAN", "사유 충분히 길게", AdminRole.REVIEWER)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CASE_CLOSED"));
		mvc.perform(post("/api/v1/admin/cases/999999999/actions").header("Authorization", admin(AdminRole.REVIEWER))
				.contentType(MediaType.APPLICATION_JSON).content(body("MACRO", "CONFIRM", "사유 충분히 길게")))
				.andExpect(status().isNotFound());
	}

	@Test
	void 두_관리자가_동시에_판정해도_한_번만() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> fs = new ArrayList<>();
		for (String action : List.of("RESTRICT", "BAN")) {
			fs.add(pool.submit((Callable<Integer>) () -> {
				go.await();
				return mvc.perform(act("MACRO", action, "동시 판정 테스트입니다", AdminRole.REVIEWER)).andReturn()
						.getResponse().getStatus();
			}));
		}
		go.countDown();
		List<Integer> codes = new ArrayList<>();
		for (Future<Integer> f : fs) {
			codes.add(f.get());
		}
		pool.shutdown();
		assertThat(codes).containsExactlyInAnyOrder(200, 409);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action WHERE subject_hash = ?", Integer.class, ci)).isOne();
	}

	@Test
	void 조회_권한만으로는_조치할_수_없다() throws Exception {
		mvc.perform(act("MACRO", "CONFIRM", "사유 충분히 길게", AdminRole.VIEWER)).andExpect(status().isForbidden());
		assertThat(one("SELECT status FROM suspicious_case WHERE case_id = ?", caseId)).isEqualTo("OPEN");
	}

	// ------------------------------------------------------------ 마스킹 해제

	@Test
	void 마스킹_해제는_SUPERVISOR만_사유와_함께_기록을_남기고_원문을_준다() throws Exception {
		mvc.perform(unmask("수사 협조 요청 확인", AdminRole.REVIEWER)).andExpect(status().isForbidden());
		mvc.perform(unmask(null, AdminRole.SUPERVISOR)).andExpect(status().isBadRequest());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action WHERE subject_hash = ? AND action_type = 'UNMASK'",
				Integer.class, ci)).isZero();

		String raw = mvc.perform(unmask("수사 협조 요청 확인", AdminRole.SUPERVISOR)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		JsonNode r = json.readTree(raw);
		assertThat(r.get("accounts")).hasSize(3);
		assertThat(raw).contains("정하늘").contains("01011112222");
		assertThat(one("SELECT memo FROM admin_action WHERE subject_hash = ? AND action_type = 'UNMASK'", ci))
				.isEqualTo("수사 협조 요청 확인");
		assertThat(r.get("actionId").asLong()).isPositive();
	}

	// ------------------------------------------------------------ 도우미

	MockHttpServletRequestBuilder act(String verdict, String action, String memo, AdminRole role) {
		return post("/api/v1/admin/cases/" + caseId + "/actions").header("Authorization", admin(role))
				.contentType(MediaType.APPLICATION_JSON).content(body(verdict, action, memo));
	}

	MockHttpServletRequestBuilder unmask(String memo, AdminRole role) {
		return post("/api/v1/admin/cases/" + caseId + "/unmask").header("Authorization", admin(role))
				.contentType(MediaType.APPLICATION_JSON).content(memo == null ? "{}" : "{\"memo\":\"" + memo + "\"}");
	}

	static String body(String verdict, String action, String memo) {
		return "{\"verdict\":\"%s\",\"action\":\"%s\",\"memo\":\"%s\"}".formatted(verdict, action, memo);
	}

	String admin(AdminRole role) {
		return "Bearer " + tokens.issue(ActorType.ADMIN, ADMIN, UUID.randomUUID().toString(), role).value();
	}

	String one(String sql, Object... args) {
		return jdbc.queryForObject(sql, String.class, args);
	}

	String memberStatus(long id) {
		return one("SELECT status FROM member WHERE member_id = ?", id);
	}

	static String hash() {
		byte[] b = new byte[32];
		new SecureRandom().nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	long member(String name, String phone, String status, int ageDays) {
		long id = NEXT.getAndIncrement();
		Encrypted e = cipher.encrypt(phone, PiiField.MEMBER_PHONE, id);
		jdbc.update("""
				INSERT INTO member (member_id, email, ci_hash, name, phone_enc, enc_key_id, status, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, "act" + id + "@infra-test.jejari.dev", ci, name, e.cipherText(),
				e.keyId(), status, Timestamp.from(Instant.now().minus(ageDays, ChronoUnit.DAYS)));
		return id;
	}

	void session(long memberId) {
		jdbc.update("""
				INSERT INTO auth_session (session_id, member_id, actor_type, refresh_token_hash, token_family_id, expires_at)
				VALUES (?, ?, 'MEMBER', ?, ?, SYSTIMESTAMP + INTERVAL '1' DAY)""",
				UUID.randomUUID().toString(), memberId, hash(), UUID.randomUUID().toString());
	}
}
