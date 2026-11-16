package com.jejari.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.common.pii.PiiCipher;
import com.jejari.common.pii.PiiField;

/**
 * 본인인증 → 가입 → 로그인 전체 흐름을 실제 Oracle·Redis·MongoDB로 확인한다.
 * {@code docker compose up -d} 뒤 {@code ./gradlew infraTest}. Redis는 15번 DB(매번 비움),
 * Oracle·MongoDB는 이 테스트가 만든 이메일({@code @infra-test.jejari.dev})의 행만 지운다.
 */
@Tag("infra")
@SpringBootTest(properties = {
		"spring.data.redis.database=15",
		"jejari.queue.scheduler-enabled=false",
		"jejari.risk.cache-resync-enabled=false"})
@AutoConfigureMockMvc
class MemberAuthInfraTest {

	static final String DOMAIN = "@infra-test.jejari.dev";
	static final String PW = "Blue-Whale-42";
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
	PiiCipher cipher;
	@Autowired
	Pseudonymizer pseudonymizer;

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
			List<Long> ids = jdbc.queryForList("SELECT member_id FROM member WHERE email = ?", Long.class, e);
			for (Long id : ids) {
				jdbc.update("DELETE FROM auth_session WHERE member_id = ?", id);
				jdbc.update("DELETE FROM identity_verification WHERE member_id = ?", id);
				jdbc.update("DELETE FROM member_credential WHERE member_id = ?", id);
				jdbc.update("DELETE FROM member WHERE member_id = ?", id);
			}
			mongo.remove(Query.query(Criteria.where("email_hash").is(pseudonymizer.email(e))), "login_attempt");
		}
	}

	String email() {
		String e = "u" + UUID.randomUUID().toString().substring(0, 8) + DOMAIN;
		emails.add(e);
		return e;
	}

	// ---- 호출 도우미 ----

	MvcResult call(String path, Object body) throws Exception {
		return mvc.perform(post("/api/v1/auth/" + path).contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(body))).andReturn();
	}

	static JsonNode json(MvcResult r) throws Exception {
		return JSON.readTree(r.getResponse().getContentAsString());
	}

	String verify(String name, String phone) throws Exception {
		MvcResult r = call("verify", java.util.Map.of("name", name, "phone", phone, "provider", "MOCK_PASS"));
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		return json(r).get("verificationToken").asText();
	}

	MvcResult signup(String token, String email, String pw) throws Exception {
		return call("signup", java.util.Map.of("verificationToken", token, "email", email, "password", pw));
	}

	MvcResult login(String email, String pw) throws Exception {
		return call("login", java.util.Map.of("email", email, "password", pw));
	}

	static String code(MvcResult r) throws Exception {
		return json(r).get("code").asText();
	}

	String signedUp(String name, String phone) throws Exception {
		String e = email();
		assertThat(signup(verify(name, phone), e, PW).getResponse().getStatus()).isEqualTo(201);
		return e;
	}

	// ---- 가입 ----

	@Test
	void 가입하면_개인정보는_암호문과_해시로만_저장된다() throws Exception {
		String e = email();
		MvcResult r = signup(verify("홍길동", "010-1234-5678"), e.toUpperCase(), PW);
		assertThat(r.getResponse().getStatus()).isEqualTo(201);
		assertThat(json(r).get("email").asText()).doesNotContain(e.substring(0, e.indexOf('@')));

		var row = jdbc.queryForMap("SELECT * FROM member WHERE email = ?", e); // 소문자로 저장
		long id = ((Number) row.get("MEMBER_ID")).longValue();
		assertThat(row.get("STATUS")).isEqualTo("ACTIVE");
		assertThat((String) row.get("PHONE_ENC")).startsWith("v1.").doesNotContain("01012345678");
		assertThat(cipher.decrypt((String) row.get("PHONE_ENC"), PiiField.MEMBER_PHONE, id)).isEqualTo("01012345678");
		assertThat(row.get("PHONE_HASH")).isEqualTo(pseudonymizer.phone("01012345678"));
		assertThat(row.get("CI_HASH")).isEqualTo(pseudonymizer.mockCiHash("홍길동", "01012345678"));

		var cred = jdbc.queryForMap("SELECT * FROM member_credential WHERE member_id = ?", id);
		assertThat((String) cred.get("PASSWORD_HASH")).startsWith("$argon2id$").doesNotContain(PW);
		assertThat(cred.get("PEPPER_KEY_ID")).isEqualTo("p1");
		assertThat(jdbc.queryForObject("SELECT result FROM identity_verification WHERE member_id = ?", String.class, id))
				.isEqualTo("SUCCESS");
	}

	@Test
	void 인증_토큰은_한_번만_쓸_수_있다() throws Exception {
		String token = verify("홍길동", "01012345678");
		assertThat(signup(token, email(), PW).getResponse().getStatus()).isEqualTo(201);
		MvcResult again = signup(token, email(), PW);
		assertThat(again.getResponse().getStatus()).isEqualTo(400);
		assertThat(code(again)).isEqualTo("AUTH_VERIFY_FAILED");
	}

	@Test
	void 비밀번호_규칙_위반이면_토큰을_잃지_않는다() throws Exception {
		String token = verify("홍길동", "01012345678");
		String e = email();
		assertThat(code(signup(token, e, "1234567890"))).isEqualTo("AUTH_PASSWORD_POLICY");
		assertThat(signup(token, e, PW).getResponse().getStatus()).isEqualTo(201);
	}

	@Test
	void 위조_토큰은_거절() throws Exception {
		assertThat(code(signup("A".repeat(43), email(), PW))).isEqualTo("AUTH_VERIFY_FAILED");
		assertThat(code(signup("not-a-token", email(), PW))).isEqualTo("AUTH_VERIFY_FAILED");
	}

	@Test
	void 같은_이메일로_동시에_가입하면_하나만_성공() throws Exception {
		String e = email();
		String t1 = verify("홍길동", "01012345678"), t2 = verify("김철수", "01087654321");
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<MvcResult>> fs = new ArrayList<>();
			for (String t : List.of(t1, t2)) {
				Callable<MvcResult> c = () -> {
					start.await();
					return signup(t, e, PW);
				};
				fs.add(pool.submit(c));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<MvcResult> f : fs) {
				statuses.add(f.get().getResponse().getStatus());
			}
			assertThat(statuses).containsExactlyInAnyOrder(201, 409);
		} finally {
			pool.shutdown();
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE email = ?", Integer.class, e)).isEqualTo(1);
	}

	@Test
	void 같은_사람의_두_계정은_같은_CI로_묶인다() throws Exception {
		String a = signedUp("홍길동", "010-1234-5678");
		String b = signedUp(" 홍길동", "+82 10-1234-5678");
		assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT ci_hash) FROM member WHERE email IN (?, ?)",
				Integer.class, a, b)).isEqualTo(1);
	}

	// ---- 로그인 ----

	@Test
	void 로그인하면_액세스_토큰과_HttpOnly_리프레시_쿠키() throws Exception {
		String e = signedUp("홍길동", "01012345678");
		MvcResult r = login(e, PW);
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		assertThat(json(r).get("accessToken").asText()).isNotBlank();
		String cookie = r.getResponse().getHeader("Set-Cookie");
		assertThat(cookie).contains("HttpOnly", "SameSite=Strict");
		assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");

		Document d = mongo.findOne(Query.query(Criteria.where("email_hash").is(pseudonymizer.email(e))
				.and("result").is("SUCCESS")), Document.class, "login_attempt");
		assertThat(d).isNotNull();
		assertThat(d.toJson()).doesNotContain(e, PW);
		assertThat(d.get("expire_at")).isNotNull();
	}

	@Test
	void 틀린_비밀번호와_없는_계정은_같은_응답() throws Exception {
		String e = signedUp("홍길동", "01012345678");
		MvcResult wrong = login(e, "Wrong-Whale-42");
		MvcResult none = login("nobody" + DOMAIN, PW);
		assertThat(wrong.getResponse().getStatus()).isEqualTo(none.getResponse().getStatus()).isEqualTo(401);
		assertThat(json(wrong).get("message").asText()).isEqualTo(json(none).get("message").asText());
		assertThat(code(wrong)).isEqualTo(code(none)).isEqualTo("AUTH_INVALID_CREDENTIALS");
	}

	@Test
	void 다섯_번_틀리면_맞는_비밀번호도_잠김() throws Exception {
		String e = signedUp("홍길동", "01012345678");
		for (int i = 0; i < 5; i++) {
			assertThat(code(login(e, "Wrong-Whale-42"))).isEqualTo("AUTH_INVALID_CREDENTIALS");
		}
		assertThat(code(login(e, PW))).isEqualTo("AUTH_LOCKED");
		assertThat(jdbc.queryForObject("SELECT failed_count FROM member_credential c JOIN member m"
				+ " ON m.member_id = c.member_id WHERE m.email = ?", Integer.class, e)).isEqualTo(5);
	}

	@Test
	void 없는_계정도_다섯_번이면_똑같이_잠김_계정_존재가_드러나지_않는다() throws Exception {
		String ghost = "ghost" + UUID.randomUUID().toString().substring(0, 6) + DOMAIN;
		emails.add(ghost);
		for (int i = 0; i < 5; i++) {
			assertThat(code(login(ghost, PW))).isEqualTo("AUTH_INVALID_CREDENTIALS");
		}
		assertThat(code(login(ghost, PW))).isEqualTo("AUTH_LOCKED");
	}

	@Test
	void 성공하면_실패_수가_초기화() throws Exception {
		String e = signedUp("홍길동", "01012345678");
		for (int i = 0; i < 4; i++) {
			login(e, "Wrong-Whale-42");
		}
		assertThat(login(e, PW).getResponse().getStatus()).isEqualTo(200);
		for (int i = 0; i < 4; i++) {
			login(e, "Wrong-Whale-42");
		}
		assertThat(login(e, PW).getResponse().getStatus()).as("초기화됐으면 다시 4번은 괜찮다").isEqualTo(200);
	}

	@Test
	void 정지_계정은_비밀번호가_맞을_때만_정지를_알린다() throws Exception {
		String e = signedUp("홍길동", "01012345678");
		jdbc.update("UPDATE member SET status = 'BANNED' WHERE email = ?", e);
		assertThat(code(login(e, "Wrong-Whale-42"))).isEqualTo("AUTH_INVALID_CREDENTIALS");
		assertThat(code(login(e, PW))).isEqualTo("AUTH_BANNED");
	}

	@Test
	void 탈퇴_계정은_없는_계정과_같다() throws Exception {
		String e = signedUp("홍길동", "01012345678");
		jdbc.update("UPDATE member SET withdrawn_at = SYSTIMESTAMP WHERE email = ?", e);
		assertThat(code(login(e, PW))).isEqualTo("AUTH_INVALID_CREDENTIALS");
	}

	@Test
	void IP당_분당_시도_제한() throws Exception {
		String ghost = "rate" + UUID.randomUUID().toString().substring(0, 6) + DOMAIN;
		emails.add(ghost);
		String last = null;
		for (int i = 0; i < 21; i++) {
			// 잠금(이메일별)에 먼저 걸리지 않게 매번 다른 이메일
			last = code(login(i + ghost, PW));
		}
		assertThat(last).isEqualTo("AUTH_RATE_LIMITED");
	}

	@Test
	void 아주_긴_비밀번호도_해시하지_않고_같은_실패() throws Exception {
		String e = signedUp("홍길동", "01012345678");
		assertThat(code(login(e, "x".repeat(100_000)))).isEqualTo("AUTH_INVALID_CREDENTIALS");
	}
}
