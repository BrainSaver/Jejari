package com.jejari.member.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
		return mvc.perform(post("/api/v1/auth/" + path).contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(body))).andReturn();
	}

	MvcResult login(String email, String pw) throws Exception {
		return call("login", Map.of("email", email, "password", pw));
	}

	MvcResult withdraw(Account a, String password) throws Exception {
		return mvc.perform(delete("/api/v1/me").header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content(password == null ? "{}" : JSON.writeValueAsString(Map.of("password", password)))).andReturn();
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
