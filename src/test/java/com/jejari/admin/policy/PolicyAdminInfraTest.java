package com.jejari.admin.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jejari.auth.ActorType;
import com.jejari.auth.AdminRole;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.support.NoRunningApp;

import tools.jackson.databind.json.JsonMapper;

/** ADM-08 운영 설정 API를 실제 Oracle·Redis로 확인한다. 바꾼 정책은 끝나면 원래 값·버전으로 되돌린다 */
@Tag("infra")
@ExtendWith(NoRunningApp.class)
@SpringBootTest(properties = {
		"spring.data.redis.database=15",
		"jejari.queue.scheduler-enabled=false",
		"jejari.risk.cache-resync-enabled=false",
		"jejari.risk.batch-enabled=false",
		"jejari.seat.expiry-scanner-enabled=false",
		"jejari.train.sale-open-scheduler-enabled=false"})
@AutoConfigureMockMvc
class PolicyAdminInfraTest {

	static final String KEY = "replay.batch_sec";
	static final long ADMIN = 962901;
	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	AccessTokenIssuer tokens;

	Map<String, Object> before;

	@BeforeEach
	void setUp() {
		before = jdbc.queryForMap("SELECT policy_value, version, updated_by FROM app_policy WHERE policy_key = ?", KEY);
		// updated_by·changed_by가 admin_user를 가리키는 FK라 테스트 관리자 행이 있어야 한다 (해시는 쓰지 않으므로 가짜 값)
		jdbc.update("""
				INSERT INTO admin_user (admin_id, login_id, password_hash, role, status)
				SELECT ?, 'policy-infra-test', 'x', 'SUPERVISOR', 'ACTIVE' FROM dual
				 WHERE NOT EXISTS (SELECT 1 FROM admin_user WHERE admin_id = ?)""", ADMIN, ADMIN);
	}

	@AfterEach
	void tearDown() {
		jdbc.update("UPDATE app_policy SET policy_value = ?, version = ?, updated_by = ? WHERE policy_key = ?",
				before.get("POLICY_VALUE"), before.get("VERSION"), before.get("UPDATED_BY"), KEY);
		jdbc.update("DELETE FROM app_policy_history WHERE changed_by = ?", ADMIN);
		jdbc.update("DELETE FROM admin_user WHERE admin_id = ?", ADMIN);
		redis.opsForHash().delete(PolicyAdminService.CACHE, KEY);
	}

	@Test
	void SUPERVISOR가_바꾸면_버전이_오르고_이력과_Redis_캐시가_같이_바뀐다() throws Exception {
		long v = ((Number) before.get("VERSION")).longValue();
		mvc.perform(put("/api/v1/admin/policies/" + KEY).header("Authorization", admin(AdminRole.SUPERVISOR))
				.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("value", "7", "version", v))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.value").value("7"))
				.andExpect(jsonPath("$.version").value(v + 1))
				.andExpect(jsonPath("$.cachedValue").value("7"));
		assertThat(redis.opsForHash().get(PolicyAdminService.CACHE, KEY)).isEqualTo("7");
		mvc.perform(get("/api/v1/admin/policies/" + KEY + "/history").header("Authorization", admin(AdminRole.VIEWER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].newValue").value("7"))
				.andExpect(jsonPath("$[0].oldValue").value(String.valueOf(before.get("POLICY_VALUE"))));
	}

	@Test
	void 읽은_버전이_옛것이면_409_다른_관리자가_먼저_바꿈() throws Exception {
		long v = ((Number) before.get("VERSION")).longValue();
		mvc.perform(put("/api/v1/admin/policies/" + KEY).header("Authorization", admin(AdminRole.SUPERVISOR))
				.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("value", "6", "version", v - 1))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("POLICY_VERSION_CONFLICT"));
	}

	@Test
	void 숫자_정책에_글자를_넣으면_400_VIEWER는_못_바꾼다() throws Exception {
		long v = ((Number) before.get("VERSION")).longValue();
		mvc.perform(put("/api/v1/admin/policies/" + KEY).header("Authorization", admin(AdminRole.SUPERVISOR))
				.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("value", "abc", "version", v))))
				.andExpect(status().isBadRequest());
		mvc.perform(put("/api/v1/admin/policies/" + KEY).header("Authorization", admin(AdminRole.VIEWER))
				.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("value", "6", "version", v))))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/admin/policies").header("Authorization", admin(AdminRole.VIEWER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].key").exists());
	}

	private String admin(AdminRole role) {
		return "Bearer " + tokens.issue(ActorType.ADMIN, ADMIN, UUID.randomUUID().toString(), role).value();
	}
}
