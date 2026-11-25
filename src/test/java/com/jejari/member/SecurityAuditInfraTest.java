package com.jejari.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jejari.support.FullStackInfraTest;

import tools.jackson.databind.json.JsonMapper;

/**
 * 보안 점검(2026-11-25, 저장소 최상단 SECURITY_AUDIT_20261125.md)에서 나온 결함이 다시 생기지 않게 막는 회귀 테스트.
 *
 * <ul>
 *   <li>§2.1 정지된 명의(CI)로 다른 이메일 재가입</li>
 *   <li>§2.2 정지 직전에 받은 액세스 토큰으로 결제·정보 변경·원문 보기·탈퇴</li>
 *   <li>§2.3 한글 User-Agent 200자(600바이트)로 로그인 → ORA-12899 500</li>
 *   <li>§2.4 좌석 선점 seats:[null] → NPE 500</li>
 * </ul>
 */
@FullStackInfraTest
class SecurityAuditInfraTest {

	static final String PW = "Blue-Whale-42";
	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;

	final List<String> emails = new ArrayList<>();

	@BeforeEach
	void flushRedis() {
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
	}

	@AfterEach
	void cleanUp() {
		for (String e : emails) {
			for (Long id : jdbc.queryForList("SELECT member_id FROM member WHERE email = ?", Long.class, e)) {
				for (String t : List.of("waitlist", "reservation", "auth_session", "identity_verification",
						"member_credential")) {
					jdbc.update("DELETE FROM " + t + " WHERE member_id = ?", id);
				}
				jdbc.update("DELETE FROM member WHERE member_id = ?", id);
			}
		}
	}

	// ------------------------------------------------------------ §2.1

	@Test
	void 정지된_명의는_다른_이메일로_다시_가입할_수_없다() throws Exception {
		String phone = phone();
		Account a = signedUp("정지재가입", phone);
		ban(a);

		String token = verify("정지재가입", phone);
		MvcResult r = call("signup", Map.of("verificationToken", token, "email", newEmail(), "password", PW));

		assertThat(r.getResponse().getStatus()).isEqualTo(403);
		assertThat(code(r)).isEqualTo("AUTH_BANNED");
		String ci = jdbc.queryForObject("SELECT ci_hash FROM member WHERE member_id = ?", String.class, a.id());
		assertThat(count("SELECT COUNT(*) FROM member WHERE ci_hash = ?", ci)).isEqualTo(1);
	}

	@Test
	void 정지와_무관한_명의는_그대로_가입된다() throws Exception {
		// 대조: 정지 검사가 정상 가입(신규)까지 막지 않는지
		assertThat(call("signup", Map.of("verificationToken", verify("정상가입", phone()), "email", newEmail(),
				"password", PW)).getResponse().getStatus()).isEqualTo(201);
	}

	// ------------------------------------------------------------ §2.2

	@Test
	void 정지된_회원은_남은_토큰으로_정보변경_원문보기_탈퇴를_못한다() throws Exception {
		Account a = signedUp("정지토큰", phone());
		ban(a);

		MvcResult upd = send(patch("/api/v1/me"), a, Map.of("currentPassword", PW, "phone", phone()));
		MvcResult rev = send(post("/api/v1/me/reveal"), a, Map.of("password", PW));
		MvcResult del = send(delete("/api/v1/me"), a, Map.of("password", PW));

		assertThat(List.of(upd.getResponse().getStatus(), rev.getResponse().getStatus(), del.getResponse().getStatus()))
				.containsOnly(403);
		assertThat(List.of(code(upd), code(rev), code(del))).containsOnly("AUTH_BANNED");
		// 탈퇴로 이메일을 풀지 못했다
		assertThat(jdbc.queryForObject("SELECT email FROM member WHERE member_id = ?", String.class, a.id()))
				.isEqualTo(a.email());
		assertThat(count("SELECT COUNT(*) FROM member WHERE member_id = ? AND withdrawn_at IS NULL", a.id())).isEqualTo(1);

		// 조회는 연다: 화면이 정지 상태를 안내할 수 있게
		MvcResult me = mvc.perform(get("/api/v1/me").header("Authorization", a.bearer())).andReturn();
		assertThat(me.getResponse().getStatus()).isEqualTo(200);
		assertThat(JSON.readTree(me.getResponse().getContentAsString()).get("status").asString()).isEqualTo("BANNED");
	}

	@Test
	void 정지된_회원은_남은_토큰으로_결제를_시작하지_못한다() throws Exception {
		Account a = signedUp("정지결제", phone());
		ban(a);

		MvcResult r = mvc.perform(post("/api/v1/reservations/1/payments").header("Authorization", a.bearer())
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(Map.of("cardNumber", "4111111111111111")))).andReturn();

		// 예약 확인(404)보다 먼저 정지로 막힌다
		assertThat(r.getResponse().getStatus()).isEqualTo(403);
		assertThat(code(r)).isEqualTo("AUTH_BANNED");
	}

	// ------------------------------------------------------------ §2.3

	@Test
	void 한글_User_Agent_200자로_로그인해도_500이_아니고_300바이트로_저장된다() throws Exception {
		Account a = signedUp("긴에이전트", phone());
		String ua = "가".repeat(200); // 200자지만 UTF-8 600바이트

		MvcResult r = call(post("/api/v1/auth/login").header("User-Agent", ua), Map.of("email", a.email(), "password", PW));

		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		Integer b = jdbc.queryForObject("""
				SELECT LENGTHB(user_agent) FROM auth_session
				 WHERE member_id = ? ORDER BY created_at DESC FETCH FIRST 1 ROWS ONLY""", Integer.class, a.id());
		assertThat(b).isEqualTo(300); // 3바이트 글자 100개
	}

	@Test
	void 제어_문자_섞인_User_Agent는_500이_아니라_400() throws Exception {
		Account a = signedUp("제어문자", phone());

		// 방화벽이 getHeader() 순간 거절한다. 전에는 컨트롤러 안에서 터져 500 + 헤더 원문이 에러 로그에 남았다
		MvcResult r = call(post("/api/v1/auth/login").header("User-Agent", "Mozilla\r\nX-Fake: 1"),
				Map.of("email", a.email(), "password", PW));

		assertThat(r.getResponse().getStatus()).isEqualTo(400);
		assertThat(code(r)).isEqualTo("INVALID_REQUEST");
	}

	// ------------------------------------------------------------ §2.4

	@Test
	void 좌석_선점_목록에_null이_있으면_500이_아니라_400() throws Exception {
		Account a = signedUp("널좌석", phone());

		MvcResult r = mvc.perform(post("/api/v1/reservations").header("Authorization", a.bearer())
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"runId\":9001,\"seats\":[null]}")).andReturn();

		assertThat(r.getResponse().getStatus()).isEqualTo(400);
		assertThat(code(r)).isEqualTo("INVALID_REQUEST");
	}

	// ------------------------------------------------------------ 도우미

	record Account(long id, String email, String access) {
		String bearer() {
			return "Bearer " + access;
		}
	}

	static String phone() {
		return String.format("010%08d", (long) (Math.random() * 100_000_000));
	}

	String newEmail() {
		String e = "sa" + UUID.randomUUID().toString().substring(0, 8) + "@infra-test.jejari.dev";
		emails.add(e);
		return e;
	}

	/** 관리자 정지(CaseActionService.ban)와 같은 효과: 상태만 바꾸고, 이미 받은 액세스 토큰은 그대로 둔다 */
	void ban(Account a) {
		jdbc.update("UPDATE member SET status = 'BANNED', updated_at = SYSTIMESTAMP WHERE member_id = ?", a.id());
	}

	String verify(String name, String phone) throws Exception {
		return JSON.readTree(call("verify", Map.of("name", name, "phone", phone, "provider", "MOCK_PASS"))
				.getResponse().getContentAsString()).get("verificationToken").asString();
	}

	Account signedUp(String name, String phone) throws Exception {
		String email = newEmail();
		assertThat(call("signup", Map.of("verificationToken", verify(name, phone), "email", email, "password", PW))
				.getResponse().getStatus()).isEqualTo(201);
		long id = jdbc.queryForObject("SELECT member_id FROM member WHERE email = ?", Long.class, email);
		String access = JSON.readTree(call("login", Map.of("email", email, "password", PW)).getResponse()
				.getContentAsString()).get("accessToken").asString();
		return new Account(id, email, access);
	}

	MvcResult call(String path, Object body) throws Exception {
		return call(post("/api/v1/auth/" + path), body);
	}

	MvcResult call(MockHttpServletRequestBuilder req, Object body) throws Exception {
		MvcResult r = mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)))
				.andReturn();
		// 로그인은 로그인 대기실(LoginGate)이 비동기로 처리한다 → 결과를 한 번 더 받아 온다
		return r.getRequest().isAsyncStarted() ? mvc.perform(MockMvcRequestBuilders.asyncDispatch(r)).andReturn() : r;
	}

	MvcResult send(MockHttpServletRequestBuilder req, Account a, Object body) throws Exception {
		return mvc.perform(req.header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(body))).andReturn();
	}

	String code(MvcResult r) throws Exception {
		return JSON.readTree(r.getResponse().getContentAsString()).get("code").asString();
	}

	long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}
}
