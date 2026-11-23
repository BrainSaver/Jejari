package com.jejari.member.me;

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

import com.jejari.support.FullStackInfraTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AUTH-06 내 정보·탈퇴: 실제 가입·로그인으로 만든 회원으로 확인한다 */
@FullStackInfraTest
class MyAccountInfraTest {

	static final String PW = "Blue-Whale-42";
	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;

	final List<Long> members = new ArrayList<>();

	@BeforeEach
	void flushRedis() {
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
	}

	@AfterEach
	void cleanUp() {
		for (long id : members) {
			for (String t : List.of("waitlist", "reservation", "auth_session", "identity_verification", "member_credential")) {
				jdbc.update("DELETE FROM " + t + " WHERE member_id = ?", id);
			}
			jdbc.update("DELETE FROM member WHERE member_id = ?", id);
		}
	}

	// ------------------------------------------------------------ 내 정보

	@Test
	void 내_정보는_이름과_휴대폰을_가려서_보여준다() throws Exception {
		Account a = signedUp("홍길동", phone());
		MvcResult r = mvc.perform(get("/api/v1/me").header("Authorization", a.bearer())).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		String body = r.getResponse().getContentAsString();
		assertThat(body).doesNotContain("홍길동").doesNotContain(a.phone());
		JsonNode me = JSON.readTree(body);
		assertThat(me.get("name").asString()).isEqualTo("홍*동");
		assertThat(me.get("phone").asString()).isEqualTo(a.phone().substring(0, 3) + "-****-" + a.phone().substring(7));
		assertThat(me.get("email").asString()).isEqualTo(a.email());
		assertThat(me.get("identityVerified").asBoolean()).isTrue();
	}

	// ------------------------------------------------------------ 탈퇴

	@Test
	void 탈퇴하면_개인정보를_지우고_로그인과_세션을_막지만_CI는_남긴다() throws Exception {
		Account a = signedUp("김탈퇴", phone());
		String ci = jdbc.queryForObject("SELECT ci_hash FROM member WHERE member_id = ?", String.class, a.id());
		long wl = waitlist(a.id());

		assertThat(withdraw(a, PW).getResponse().getStatus()).isEqualTo(204);

		Map<String, Object> m = jdbc.queryForMap("SELECT * FROM member WHERE member_id = ?", a.id());
		assertThat(m.get("WITHDRAWN_AT")).isNotNull();
		assertThat(m.get("NAME")).isEqualTo("탈퇴회원");
		assertThat((String) m.get("EMAIL")).isNotEqualTo(a.email()).endsWith("@withdrawn.invalid");
		assertThat(m.get("PHONE_ENC")).isNull();
		assertThat(m.get("PHONE_HASH")).isNull();
		assertThat(m.get("CI_HASH")).isEqualTo(ci); // 재가입으로 위험 점수 세탁 방지
		assertThat(count("SELECT COUNT(*) FROM member_credential WHERE member_id = ?", a.id())).isZero();
		assertThat(count("SELECT COUNT(*) FROM auth_session WHERE member_id = ? AND revoked_at IS NULL", a.id())).isZero();
		assertThat(jdbc.queryForObject("SELECT status FROM waitlist WHERE waitlist_id = ?", String.class, wl))
				.isEqualTo("CANCELED");

		assertThat(login(a.email(), PW).getResponse().getStatus()).isEqualTo(401);
		assertThat(mvc.perform(get("/api/v1/me").header("Authorization", a.bearer())).andReturn().getResponse()
				.getStatus()).isEqualTo(404);
		// 같은 이메일로 다시 가입할 수 있다
		Account again = signedUp("김탈퇴", phone(), a.email());
		assertThat(again.id()).isNotEqualTo(a.id());
	}

	@Test
	void 비밀번호가_틀리면_탈퇴되지_않고_로그인과_같은_잠금이_걸린다() throws Exception {
		Account a = signedUp("이잠금", phone());
		for (int i = 0; i < 5; i++) {
			assertThat(withdraw(a, "Wrong-Password-" + i).getResponse().getStatus()).isEqualTo(401);
		}
		assertThat(withdraw(a, PW).getResponse().getStatus()).isEqualTo(423); // 맞아도 잠김
		assertThat(login(a.email(), PW).getResponse().getStatus()).isEqualTo(423); // 로그인도 잠김 (같은 카운터)
		assertThat(withdraw(a, null).getResponse().getStatus()).isIn(401, 423);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE member_id = ? AND withdrawn_at IS NULL",
				Integer.class, a.id())).isOne();
	}

	@Test
	void 진행_중인_예약이_있으면_탈퇴할_수_없다() throws Exception {
		Account a = signedUp("박예약", phone());
		jdbc.update("""
				INSERT INTO reservation (reservation_id, member_id, ci_hash, run_id, from_station_id, to_station_id,
				  seat_count, total_amount, status, channel, source, held_at, pay_deadline_at, idempotency_key, version)
				SELECT reservation_seq.NEXTVAL, member_id, ci_hash, 9001, '3900023', '3900114', 1, 59800, 'HOLD', 'WEB',
				       'NORMAL', SYSTIMESTAMP, SYSTIMESTAMP + INTERVAL '10' MINUTE, ?, 0 FROM member WHERE member_id = ?""",
				UUID.randomUUID().toString(), a.id());
		MvcResult r = withdraw(a, PW);
		assertThat(r.getResponse().getStatus()).isEqualTo(409);
		assertThat(JSON.readTree(r.getResponse().getContentAsString()).get("code").asString())
				.isEqualTo("WITHDRAW_ACTIVE_RESERVATION");
		assertThat(count("SELECT COUNT(*) FROM member_credential WHERE member_id = ?", a.id())).isOne();
	}

	// ------------------------------------------------------------ 원본 보기

	@Test
	void 비밀번호를_다시_넣으면_가리지_않은_이름과_휴대폰을_준다() throws Exception {
		Account a = signedUp("홍길동", phone());
		MvcResult r = reveal(a, PW);
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		JsonNode v = JSON.readTree(r.getResponse().getContentAsString());
		assertThat(v.get("name").asString()).isEqualTo("홍길동");
		assertThat(v.get("phone").asString()).isEqualTo(a.phone());
	}

	@Test
	void 원본_보기도_비밀번호가_틀리면_거절하고_로그인과_같은_잠금이_걸린다() throws Exception {
		Account a = signedUp("이원본", phone());
		MvcResult r = reveal(a, "Wrong-Password-1");
		assertThat(r.getResponse().getStatus()).isEqualTo(401);
		assertThat(r.getResponse().getContentAsString()).doesNotContain("이원본").doesNotContain(a.phone());
		assertThat(reveal(a, null).getResponse().getStatus()).isEqualTo(401);
		for (int i = 0; i < 3; i++) {
			reveal(a, "Wrong-Password-x" + i);
		}
		assertThat(reveal(a, PW).getResponse().getStatus()).isEqualTo(423); // 5번 틀리면 맞아도 잠김
		assertThat(login(a.email(), PW).getResponse().getStatus()).isEqualTo(423);
	}

	// ------------------------------------------------------------ 정보 변경

	@Test
	void 이름과_휴대폰을_바꾸면_가린_값과_원본_보기에_새_값이_나온다_CI는_그대로() throws Exception {
		Account a = signedUp("홍길동", phone());
		String ci = jdbc.queryForObject("SELECT ci_hash FROM member WHERE member_id = ?", String.class, a.id());
		String oldHash = jdbc.queryForObject("SELECT phone_hash FROM member WHERE member_id = ?", String.class, a.id());
		MvcResult r = update(a, Map.of("currentPassword", PW, "name", "  김   새이름 ", "phone", "010-9876-5432"));
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		JsonNode body = JSON.readTree(r.getResponse().getContentAsString());
		assertThat(body.get("reloginRequired").asBoolean()).isFalse();
		assertThat(body.get("me").get("name").asString()).isEqualTo("김***름"); // 가운데는 모두 가림 (공백 포함)
		assertThat(body.get("me").get("phone").asString()).isEqualTo("010-****-5432");
		assertThat(r.getResponse().getContentAsString()).doesNotContain("김 새이름").doesNotContain("01098765432");

		JsonNode v = JSON.readTree(reveal(a, PW).getResponse().getContentAsString());
		assertThat(v.get("name").asString()).isEqualTo("김 새이름"); // 공백 정리
		assertThat(v.get("phone").asString()).isEqualTo("01098765432");
		assertThat(jdbc.queryForObject("SELECT ci_hash FROM member WHERE member_id = ?", String.class, a.id())).isEqualTo(ci);
		assertThat(jdbc.queryForObject("SELECT phone_hash FROM member WHERE member_id = ?", String.class, a.id()))
				.isNotEqualTo(oldHash);
		// 세션은 그대로 (이름·휴대폰만 바꿨으니 다시 로그인하지 않아도 된다)
		assertThat(mvc.perform(get("/api/v1/me").header("Authorization", a.bearer())).andReturn().getResponse().getStatus())
				.isEqualTo(200);
	}

	@Test
	void 비밀번호를_바꾸면_예전_비밀번호는_막히고_모든_세션이_끊긴다() throws Exception {
		Account a = signedUp("박비번", phone());
		String next = "Green-Turtle-77";
		MvcResult r = update(a, Map.of("currentPassword", PW, "newPassword", next));
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		assertThat(JSON.readTree(r.getResponse().getContentAsString()).get("reloginRequired").asBoolean()).isTrue();
		assertThat(count("SELECT COUNT(*) FROM auth_session WHERE member_id = ? AND revoked_at IS NULL", a.id())).isZero();
		assertThat(login(a.email(), PW).getResponse().getStatus()).isEqualTo(401);
		assertThat(login(a.email(), next).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void 정보_변경은_현재_비밀번호가_틀리면_아무것도_바꾸지_않는다() throws Exception {
		Account a = signedUp("최변경", phone());
		assertThat(update(a, Map.of("currentPassword", "Wrong-Password-9", "name", "다른이름")).getResponse().getStatus())
				.isEqualTo(401);
		assertThat(jdbc.queryForObject("SELECT name FROM member WHERE member_id = ?", String.class, a.id())).isEqualTo("최변경");
	}

	@Test
	void 잘못된_입력은_거절한다_바꿀_것_없음_이름_휴대폰_약한_비밀번호_같은_비밀번호() throws Exception {
		Account a = signedUp("정입력", phone());
		assertThat(code(update(a, Map.of("currentPassword", PW)))).isEqualTo("PROFILE_NOTHING_TO_CHANGE");
		assertThat(code(update(a, Map.of("currentPassword", PW, "name", "김")))).isEqualTo("PROFILE_INVALID");
		assertThat(code(update(a, Map.of("currentPassword", PW, "name", "<script>")))).isEqualTo("PROFILE_INVALID");
		assertThat(code(update(a, Map.of("currentPassword", PW, "phone", "02-123-4567")))).isEqualTo("PROFILE_INVALID");
		assertThat(code(update(a, Map.of("currentPassword", PW, "phone", "010-12ab-5678")))).isEqualTo("PROFILE_INVALID");
		assertThat(code(update(a, Map.of("currentPassword", PW, "newPassword", "short")))).isEqualTo("AUTH_PASSWORD_POLICY");
		assertThat(code(update(a, Map.of("currentPassword", PW, "newPassword", PW)))).isEqualTo("PASSWORD_UNCHANGED");
		// 전부 형식 오류라 비밀번호 확인 전에 걸러져 실패 카운터도 올리지 않는다
		assertThat(withdraw(a, "Wrong-Password-0").getResponse().getStatus()).isEqualTo(401);
	}

	// ------------------------------------------------------------ 도우미

	record Account(long id, String email, String phone, String access) {
		String bearer() {
			return "Bearer " + access;
		}
	}

	static String phone() {
		return String.format("010%08d", (long) (Math.random() * 100_000_000));
	}

	Account signedUp(String name, String phone) throws Exception {
		return signedUp(name, phone, "me" + UUID.randomUUID().toString().substring(0, 8) + "@infra-test.jejari.dev");
	}

	Account signedUp(String name, String phone, String email) throws Exception {
		String token = JSON.readTree(call("verify", Map.of("name", name, "phone", phone, "provider", "MOCK_PASS"))
				.getResponse().getContentAsString()).get("verificationToken").asString();
		assertThat(call("signup", Map.of("verificationToken", token, "email", email, "password", PW)).getResponse()
				.getStatus()).isEqualTo(201);
		long id = jdbc.queryForObject("SELECT member_id FROM member WHERE email = ?", Long.class, email);
		members.add(id);
		String access = JSON.readTree(login(email, PW).getResponse().getContentAsString()).get("accessToken").asString();
		return new Account(id, email, phone, access);
	}

	MvcResult call(String path, Object body) throws Exception {
		MvcResult r = mvc.perform(post("/api/v1/auth/" + path).contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(body))).andReturn();
		// 로그인은 로그인 대기실(LoginGate)이 비동기로 처리한다 → 결과를 한 번 더 받아 온다
		return r.getRequest().isAsyncStarted()
				? mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(r)).andReturn()
				: r;
	}

	MvcResult login(String email, String pw) throws Exception {
		return call("login", Map.of("email", email, "password", pw));
	}

	MvcResult withdraw(Account a, String password) throws Exception {
		return mvc.perform(delete("/api/v1/me").header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content(password == null ? "{}" : JSON.writeValueAsString(Map.of("password", password)))).andReturn();
	}

	MvcResult reveal(Account a, String password) throws Exception {
		return mvc.perform(post("/api/v1/me/reveal").header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content(password == null ? "{}" : JSON.writeValueAsString(Map.of("password", password)))).andReturn();
	}

	MvcResult update(Account a, Map<String, String> body) throws Exception {
		return mvc.perform(patch("/api/v1/me").header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(body))).andReturn();
	}

	String code(MvcResult r) throws Exception {
		return JSON.readTree(r.getResponse().getContentAsString()).get("code").asString();
	}

	long waitlist(long memberId) {
		long id = jdbc.queryForObject("SELECT waitlist_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO waitlist (waitlist_id, member_id, run_id, from_station_id, to_station_id, seat_grade, seat_count,
				  requested_at, priority_at, penalty_ms, status)
				VALUES (?, ?, 9001, '3900023', '3900114', 'GENERAL', 1, SYSTIMESTAMP, SYSTIMESTAMP, 0, 'WAITING')""", id, memberId);
		return id;
	}

	long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}
}
