package com.jejari.member.guest;

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

/** 비회원(게스트) 예매: 본인인증 → 게스트 입장 → 예약 조회·제한·삭제 (ERD v33) */
@FullStackInfraTest
class GuestInfraTest {

	static final String PW = "Blue-Whale-42";
	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	GuestService guestService;

	final List<Long> members = new ArrayList<>();

	@BeforeEach
	void reset() {
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

	void enable(boolean on) {
		redis.opsForHash().put("policy:cache", "guest.enabled", on ? "Y" : "N");
		sleep(1100); // AuthPolicy는 값을 1초 동안 들고 있다
	}

	static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// ------------------------------------------------------------ 켜기·끄기

	@Test
	void 기본은_꺼져_있고_꺼져_있으면_입장할_수_없다() throws Exception {
		sleep(1100); // 앞 테스트가 켜 둔 값이 AuthPolicy 안에 1초 남아 있을 수 있다
		assertThat(JSON.readTree(mvc.perform(get("/api/v1/auth/guest/enabled")).andReturn().getResponse()
				.getContentAsString()).get("enabled").asBoolean()).isFalse();
		String token = verify("김비회", phone());
		MvcResult r = call("guest", Map.of("verificationToken", token));
		assertThat(r.getResponse().getStatus()).isEqualTo(403);
		assertThat(code(r)).isEqualTo("GUEST_DISABLED");
	}

	// ------------------------------------------------------------ 입장

	@Test
	void 본인인증만으로_게스트가_되고_세션과_토큰을_받는다() throws Exception {
		enable(true);
		String phone = phone();
		Guest g = enter("홍길동", phone);
		Map<String, Object> m = jdbc.queryForMap("SELECT * FROM member WHERE member_id = ?", g.id());
		assertThat(m.get("MEMBER_TYPE")).isEqualTo("GUEST");
		assertThat(m.get("STATUS")).isEqualTo("ACTIVE"); // 이용 제한·정지가 회원과 똑같이 걸리도록
		assertThat((String) m.get("EMAIL")).isEqualTo("guest-" + g.id() + "@guest.invalid");
		assertThat(m.get("PHONE_HASH")).isNotNull();
		assertThat(m.get("GUEST_EXPIRES_AT")).isNotNull();
		assertThat(count("SELECT COUNT(*) FROM member_credential WHERE member_id = ?", g.id())).isZero(); // 비밀번호 없음
		// 세션 행이 있어야 행동 로그·녹화가 이어지고 관리자 정지로 쫓아낼 수 있다
		assertThat(count("SELECT COUNT(*) FROM auth_session WHERE member_id = ? AND revoked_at IS NULL", g.id())).isOne();

		JsonNode me = JSON.readTree(mvc.perform(get("/api/v1/me").header("Authorization", g.bearer())).andReturn()
				.getResponse().getContentAsString());
		assertThat(me.get("guest").asBoolean()).isTrue();
		assertThat(me.get("name").asString()).isEqualTo("홍*동");
	}

	@Test
	void 같은_CI로_다시_들어오면_행을_새로_만들지_않고_다시_쓴다() throws Exception {
		enable(true);
		String phone = phone();
		Guest a = enter("이재입", phone);
		Guest b = enter("이재입", phone);
		assertThat(b.id()).isEqualTo(a.id());
		String ci = jdbc.queryForObject("SELECT ci_hash FROM member WHERE member_id = ?", String.class, a.id());
		assertThat(count("SELECT COUNT(*) FROM member WHERE ci_hash = ?", ci)).isOne(); // 다계정으로 보이지 않게
		assertThat(count("SELECT COUNT(*) FROM identity_verification WHERE member_id = ?", a.id())).isEqualTo(2);
	}

	@Test
	void 이미_회원인_사람은_로그인하라고_안내하고_본인인증을_태우지_않는다() throws Exception {
		enable(true);
		String phone = phone();
		signedUp("박회원", phone);
		String token = verify("박회원", phone);
		MvcResult r = call("guest", Map.of("verificationToken", token));
		assertThat(r.getResponse().getStatus()).isEqualTo(409);
		assertThat(code(r)).isEqualTo("GUEST_IS_MEMBER");
		// 토큰은 쓰이지 않았다: 같은 토큰으로 가입 단계의 확인(peek)이 아직 된다 → 다른 이메일로 가입이 이어진다
		assertThat(call("signup", Map.of("verificationToken", token, "email", "gm" + UUID.randomUUID().toString().substring(0, 8)
				+ "@infra-test.jejari.dev", "password", PW)).getResponse().getStatus()).isEqualTo(201);
		members.add(jdbc.queryForObject("SELECT MAX(member_id) FROM member", Long.class));
	}

	@Test
	void 정지된_CI는_게스트로도_들어올_수_없다() throws Exception {
		enable(true);
		String phone = phone();
		Guest g = enter("최정지", phone);
		jdbc.update("UPDATE member SET status = 'BANNED' WHERE member_id = ?", g.id());
		MvcResult r = call("guest", Map.of("verificationToken", verify("최정지", phone)));
		assertThat(r.getResponse().getStatus()).isEqualTo(403);
		assertThat(code(r)).isEqualTo("AUTH_BANNED");
	}

	// ------------------------------------------------------------ 막는 기능

	@Test
	void 게스트는_취소표_내정보변경_탈퇴_원본보기를_쓸_수_없다() throws Exception {
		enable(true);
		Guest g = enter("정막힘", phone());
		String body = "{\"runId\":9001,\"fromStationId\":\"3900023\",\"toStationId\":\"3900114\",\"seatGrade\":\"GENERAL\",\"seatCount\":1}";
		MvcResult w = mvc.perform(post("/api/v1/waitlists").header("Authorization", g.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
		assertThat(w.getResponse().getStatus()).isEqualTo(403);
		assertThat(code(w)).isEqualTo("GUEST_NOT_ALLOWED");
		for (MvcResult r : List.of(
				mvc.perform(patch("/api/v1/me").header("Authorization", g.bearer()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"currentPassword\":\"x\",\"name\":\"다른이름\"}")).andReturn(),
				mvc.perform(delete("/api/v1/me").header("Authorization", g.bearer()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"password\":\"x\"}")).andReturn(),
				mvc.perform(post("/api/v1/me/reveal").header("Authorization", g.bearer()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"password\":\"x\"}")).andReturn())) {
			assertThat(r.getResponse().getStatus()).isEqualTo(403);
			assertThat(code(r)).isEqualTo("GUEST_NOT_ALLOWED");
		}
		// 읽기와 취소 목록은 된다
		assertThat(mvc.perform(get("/api/v1/waitlists").header("Authorization", g.bearer())).andReturn().getResponse()
				.getStatus()).isEqualTo(200);
	}

	// ------------------------------------------------------------ 로그인 없이 예약 조회

	@Test
	void 예약_번호와_휴대폰이_맞으면_조회되고_틀리면_똑같이_404다() throws Exception {
		enable(true);
		String phone = phone();
		Guest g = enter("강조회", phone);
		long rid = reservation(g.id());

		MvcResult ok = lookup(rid, phone);
		assertThat(ok.getResponse().getStatus()).isEqualTo(200);
		String body = ok.getResponse().getContentAsString();
		assertThat(body).doesNotContain("강조회").doesNotContain(phone); // 이름·휴대폰은 돌려주지 않는다
		JsonNode v = JSON.readTree(body);
		assertThat(v.get("reservationId").asLong()).isEqualTo(rid);
		assertThat(v.get("status").asString()).isEqualTo("HOLD");
		assertThat(v.get("seatCount").asInt()).isEqualTo(1);

		// 휴대폰이 다르거나, 없는 예약이거나, 형식이 아니면 모두 같은 응답 (존재 여부를 알려 주지 않는다)
		String wrongPhone = lookup(rid, "01000000001").getResponse().getContentAsString();
		String noSuch = lookup(rid + 100000, phone).getResponse().getContentAsString();
		String badFormat = lookup(rid, "abc").getResponse().getContentAsString();
		for (String s : List.of(wrongPhone, noSuch, badFormat)) {
			assertThat(code(s)).isEqualTo("GUEST_LOOKUP_NOT_FOUND");
		}
	}

	@Test
	void 일반_회원의_예약은_이_경로로_조회되지_않는다() throws Exception {
		enable(true);
		String phone = phone();
		long memberId = signedUp("윤회원", phone);
		long rid = reservation(memberId);
		MvcResult r = lookup(rid, phone);
		assertThat(r.getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void 조회_시도는_IP당_시간당_한도를_넘으면_429다() throws Exception {
		enable(true);
		redis.opsForHash().put("policy:cache", "guest.lookup_rate_per_hour", "3");
		sleep(1100);
		for (int i = 0; i < 3; i++) {
			assertThat(lookup(1L, "01011112222").getResponse().getStatus()).isEqualTo(404);
		}
		assertThat(lookup(1L, "01011112222").getResponse().getStatus()).isEqualTo(429);
	}

	// ------------------------------------------------------------ 가입 시 회원으로 전환

	@Test
	void 게스트가_같은_CI로_가입하면_그_행이_회원이_되고_예매_이력이_이어진다() throws Exception {
		enable(true);
		String phone = phone();
		Guest g = enter("한전환", phone);
		long rid = reservation(g.id());
		String email = "conv" + UUID.randomUUID().toString().substring(0, 8) + "@infra-test.jejari.dev";
		String token = verify("한전환", phone);
		assertThat(call("signup", Map.of("verificationToken", token, "email", email, "password", PW)).getResponse()
				.getStatus()).isEqualTo(201);

		Map<String, Object> m = jdbc.queryForMap("SELECT * FROM member WHERE member_id = ?", g.id());
		assertThat(m.get("MEMBER_TYPE")).isNull(); // 일반 회원
		assertThat(m.get("GUEST_EXPIRES_AT")).isNull();
		assertThat(m.get("EMAIL")).isEqualTo(email);
		assertThat(count("SELECT COUNT(*) FROM member_credential WHERE member_id = ?", g.id())).isOne();
		String ci = (String) m.get("CI_HASH");
		assertThat(count("SELECT COUNT(*) FROM member WHERE ci_hash = ?", ci)).isOne(); // 다계정으로 보이지 않는다
		assertThat(count("SELECT COUNT(*) FROM reservation WHERE reservation_id = ? AND member_id = ?", rid, g.id())).isOne();
		// 이제 회원이라 비회원 예약 조회로는 안 보인다
		assertThat(lookup(rid, phone).getResponse().getStatus()).isEqualTo(404);
	}

	// ------------------------------------------------------------ 기한 지난 게스트 삭제

	@Test
	void 기한이_지나고_진행_중_예약이_없으면_이름과_연락처를_지운다() throws Exception {
		enable(true);
		Guest g = enter("조삭제", phone());
		jdbc.update("UPDATE member SET guest_expires_at = SYSTIMESTAMP - INTERVAL '1' DAY WHERE member_id = ?", g.id());
		assertThat(guestService.purgeExpired()).isGreaterThanOrEqualTo(1);
		Map<String, Object> m = jdbc.queryForMap("SELECT * FROM member WHERE member_id = ?", g.id());
		assertThat(m.get("NAME")).isEqualTo("비회원");
		assertThat(m.get("PHONE_ENC")).isNull();
		assertThat(m.get("PHONE_HASH")).isNull();
		assertThat(m.get("WITHDRAWN_AT")).isNotNull();
		assertThat(m.get("CI_HASH")).isNotNull(); // 위험 점수·매수 제한을 세탁하지 못하게 남긴다
		assertThat(count("SELECT COUNT(*) FROM auth_session WHERE member_id = ? AND revoked_at IS NULL", g.id())).isZero();
	}

	@Test
	void 진행_중인_예약이_있으면_기한이_지나도_지우지_않는다() throws Exception {
		enable(true);
		Guest g = enter("차보존", phone());
		reservation(g.id()); // HOLD = 진행 중
		jdbc.update("UPDATE member SET guest_expires_at = SYSTIMESTAMP - INTERVAL '1' DAY WHERE member_id = ?", g.id());
		guestService.purgeExpired();
		assertThat(jdbc.queryForObject("SELECT name FROM member WHERE member_id = ?", String.class, g.id())).isEqualTo("차보존");
	}

	@Test
	void 지워진_게스트가_같은_CI로_다시_오면_그_행을_되살린다() throws Exception {
		enable(true);
		String phone = phone();
		Guest g = enter("표되살", phone);
		jdbc.update("UPDATE member SET guest_expires_at = SYSTIMESTAMP - INTERVAL '1' DAY WHERE member_id = ?", g.id());
		guestService.purgeExpired();
		Guest again = enter("표되살", phone);
		assertThat(again.id()).isEqualTo(g.id());
		Map<String, Object> m = jdbc.queryForMap("SELECT * FROM member WHERE member_id = ?", g.id());
		assertThat(m.get("NAME")).isEqualTo("표되살");
		assertThat(m.get("WITHDRAWN_AT")).isNull();
		assertThat(m.get("PHONE_HASH")).isNotNull();
	}

	// ------------------------------------------------------------ 도우미

	record Guest(long id, String access) {
		String bearer() {
			return "Bearer " + access;
		}
	}

	static String phone() {
		return String.format("010%08d", (long) (Math.random() * 100_000_000));
	}

	String verify(String name, String phone) throws Exception {
		return JSON.readTree(call("verify", Map.of("name", name, "phone", phone, "provider", "MOCK_PASS"))
				.getResponse().getContentAsString()).get("verificationToken").asString();
	}

	Guest enter(String name, String phone) throws Exception {
		MvcResult r = call("guest", Map.of("verificationToken", verify(name, phone)));
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
		String access = JSON.readTree(r.getResponse().getContentAsString()).get("accessToken").asString();
		String sub = jwtSub(access);
		long id = Long.parseLong(sub);
		if (!members.contains(id)) {
			members.add(id);
		}
		return new Guest(id, access);
	}

	long signedUp(String name, String phone) throws Exception {
		String email = "gm" + UUID.randomUUID().toString().substring(0, 8) + "@infra-test.jejari.dev";
		assertThat(call("signup", Map.of("verificationToken", verify(name, phone), "email", email, "password", PW))
				.getResponse().getStatus()).isEqualTo(201);
		long id = jdbc.queryForObject("SELECT member_id FROM member WHERE email = ?", Long.class, email);
		members.add(id);
		return id;
	}

	/** JWT 가운데 부분(payload)의 sub. 서명은 서버가 이미 확인했다 */
	static String jwtSub(String jwt) {
		String payload = jwt.split("\\.")[1];
		byte[] raw = java.util.Base64.getUrlDecoder().decode(payload);
		return JSON.readTree(new String(raw, java.nio.charset.StandardCharsets.UTF_8)).get("sub").asString();
	}

	MvcResult call(String path, Object body) throws Exception {
		return mvc.perform(post("/api/v1/auth/" + path).contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(body))).andReturn();
	}

	MvcResult lookup(long reservationId, String phone) throws Exception {
		return call("guest/lookup", Map.of("reservationId", reservationId, "phone", phone));
	}

	String code(MvcResult r) throws Exception {
		return code(r.getResponse().getContentAsString());
	}

	static String code(String body) {
		return JSON.readTree(body).get("code").asString();
	}

	long reservation(long memberId) {
		long id = jdbc.queryForObject("SELECT reservation_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO reservation (reservation_id, member_id, ci_hash, run_id, from_station_id, to_station_id,
				  seat_count, total_amount, status, channel, source, held_at, pay_deadline_at, idempotency_key, version)
				SELECT ?, member_id, ci_hash, 9001, '3900023', '3900114', 1, 59800, 'HOLD', 'WEB',
				       'NORMAL', SYSTIMESTAMP, SYSTIMESTAMP + INTERVAL '10' MINUTE, ?, 0 FROM member WHERE member_id = ?""",
				id, UUID.randomUUID().toString(), memberId);
		return id;
	}

	long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}
}
