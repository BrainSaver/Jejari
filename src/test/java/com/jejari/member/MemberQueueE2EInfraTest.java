package com.jejari.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

import com.jejari.queue.redis.QueueKeys;
import com.jejari.support.FullStackInfraTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가입 → 로그인 → 받은 액세스 토큰으로 대기열 진입까지, 실제 회원(Oracle)으로 이어지는지 확인한다.
 * 운행 정보(B 파트)는 아직 없어 FullStackInfraTest의 테스트용 판매 운행을 쓴다. 회원 정보는 실제 OracleMemberIdentityPort.
 */
@FullStackInfraTest
class MemberQueueE2EInfraTest {

	static final long RUN = FullStackInfraTest.RUN;
	static final String PW = "Blue-Whale-42";
	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;

	String email;

	@BeforeEach
	void setUp() {
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		redis.opsForValue().set(QueueKeys.RISK_SYNCED_AT, "test"); // 위험 점수 캐시 준비됨 (없으면 Oracle 조회)
		email = "e2e" + UUID.randomUUID().toString().substring(0, 8) + "@infra-test.jejari.dev";
	}

	@AfterEach
	void cleanUp() {
		for (Long id : jdbc.queryForList("SELECT member_id FROM member WHERE email = ?", Long.class, email)) {
			jdbc.update("DELETE FROM auth_session WHERE member_id = ?", id);
			jdbc.update("DELETE FROM identity_verification WHERE member_id = ?", id);
			jdbc.update("DELETE FROM member_credential WHERE member_id = ?", id);
			jdbc.update("DELETE FROM member WHERE member_id = ?", id);
		}
	}

	MvcResult postJson(String path, Object body, String bearer) throws Exception {
		var req = post(path).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
		if (bearer != null) {
			req.header("Authorization", "Bearer " + bearer);
		}
		return mvc.perform(req).andReturn();
	}

	static JsonNode json(MvcResult r) throws Exception {
		return JSON.readTree(r.getResponse().getContentAsString());
	}

	/** 가입하고 로그인해서 액세스 토큰을 돌려준다 */
	String signUpAndLogin() throws Exception {
		String token = json(postJson("/api/v1/auth/verify",
				Map.of("name", "홍길동", "phone", "01055556666", "provider", "MOCK_PASS"), null))
				.get("verificationToken").asText();
		assertThat(postJson("/api/v1/auth/signup",
				Map.of("verificationToken", token, "email", email, "password", PW), null).getResponse().getStatus())
				.isEqualTo(201);
		MvcResult login = postJson("/api/v1/auth/login", Map.of("email", email, "password", PW), null);
		assertThat(login.getResponse().getStatus()).isEqualTo(200);
		return json(login).get("accessToken").asText();
	}

	MvcResult enterQueue(String access) throws Exception {
		return mvc.perform(post("/api/v1/runs/" + RUN + "/queue").header("Authorization", "Bearer " + access))
				.andReturn();
	}

	long memberId() {
		return jdbc.queryForObject("SELECT member_id FROM member WHERE email = ?", Long.class, email);
	}

	@Test
	void 가입_로그인_후_실제_회원으로_대기열에_선다() throws Exception {
		String access = signUpAndLogin();
		MvcResult r = enterQueue(access);
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		assertThat(json(r).get("runId").asLong()).isEqualTo(RUN);
		assertThat(redis.hasKey(QueueKeys.member(memberId()))).isTrue();
		// 같은 CI로는 한 운행에 한 줄 (가입 때 만든 ci_hash가 대기열 키로 쓰였는지)
		String ci = jdbc.queryForObject("SELECT ci_hash FROM member WHERE email = ?", String.class, email);
		assertThat(redis.hasKey(QueueKeys.ci(ci))).isTrue();
	}

	@Test
	void 로그인_뒤_정지되면_남은_토큰으로도_못_선다() throws Exception {
		String access = signUpAndLogin();
		jdbc.update("UPDATE member SET status = 'BANNED' WHERE email = ?", email);
		MvcResult r = enterQueue(access);
		assertThat(r.getResponse().getStatus()).isEqualTo(403);
		assertThat(json(r).get("code").asText()).isEqualTo("AUTH_BANNED");
	}

	@Test
	void 로그인_뒤_탈퇴하면_남은_토큰으로도_못_선다() throws Exception {
		String access = signUpAndLogin();
		jdbc.update("UPDATE member SET withdrawn_at = SYSTIMESTAMP WHERE email = ?", email);
		MvcResult r = enterQueue(access);
		assertThat(r.getResponse().getStatus()).isEqualTo(401);
		assertThat(json(r).get("code").asText()).isEqualTo("AUTH_REQUIRED");
	}
}
