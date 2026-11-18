package com.jejari.queue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.json.JsonMapper;

import com.jejari.auth.ActorType;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.queue.api.QueueController;
import com.jejari.queue.api.QueueSeatTokenInterceptor;
import com.jejari.queue.config.QueueConfig;
import com.jejari.queue.dev.DevOnSaleRunProvider;
import com.jejari.queue.domain.QueueAdmitter;
import com.jejari.support.NoRunningApp;
import com.jejari.support.WebSecurityTestConfig;

/**
 * 로그인(JWT) → 진입 → 번호표 → 입장 토큰 → 좌석 API → 이탈까지 HTTP로 한 번에 확인합니다. 실제 Redis가 필요합니다
 * ({@code ./gradlew infraTest}).
 */
@Tag("infra")
@ExtendWith(NoRunningApp.class)
@WebMvcTest(controllers = {QueueController.class, QueueApiInfraTest.SeatsProbe.class})
@WebSecurityTestConfig
@ImportAutoConfiguration(DataRedisAutoConfiguration.class)
@Import({QueueConfig.class, QueueTestBeans.class, QueueApiInfraTest.SeatsProbe.class})
@TestPropertySource(properties = {
		"jejari.queue.scheduler-enabled=false",
		"jejari.queue.policy-cache-ms=0",
		"spring.data.redis.database=15"})
class QueueApiInfraTest {

	static final long RUN = 1001;
	static final String SID = "3f2b8c1e-1111-4222-8333-444455556666";
	static final String Q = "/api/v1/runs/" + RUN + "/queue";
	static final String SEATS = "/api/v1/runs/" + RUN + "/seats";

	@Autowired
	MockMvc mvc;
	@Autowired
	AccessTokenIssuer issuer;
	@Autowired
	QueueAdmitter admitter;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	DevOnSaleRunProvider runs;

	/** B의 좌석 배치도 API 자리 */
	@RestController
	@RequestMapping("/api/v1/runs/{runId}/seats")
	static class SeatsProbe {
		@GetMapping
		String seats(@PathVariable long runId) {
			return "seats-" + runId;
		}
	}

	@BeforeEach
	void reset() {
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		runs.clear();
		runs.open(RUN);
	}

	String bearer(long memberId) {
		return "Bearer " + issuer.issue(ActorType.MEMBER, memberId, SID).value();
	}

	@Test
	void 진입_조회_이탈_흐름() throws Exception {
		mvc.perform(post(Q).header(HttpHeaders.AUTHORIZATION, bearer(1)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("WAITING"))
				.andExpect(jsonPath("$.rank").value(1))
				.andExpect(jsonPath("$.ahead").value(0))
				.andExpect(jsonPath("$.passToken").doesNotExist());

		mvc.perform(get(Q + "/me").header(HttpHeaders.AUTHORIZATION, bearer(1)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ticketNo").value(1));

		mvc.perform(delete(Q + "/me").header(HttpHeaders.AUTHORIZATION, bearer(1))).andExpect(status().isNoContent());
		mvc.perform(delete(Q + "/me").header(HttpHeaders.AUTHORIZATION, bearer(1))).andExpect(status().isNoContent());
		mvc.perform(get(Q + "/me").header(HttpHeaders.AUTHORIZATION, bearer(1)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("QUEUE_NOT_FOUND"))
				.andExpect(jsonPath("$.traceId").exists());
	}

	@Test
	void 판매중이_아니면_409() throws Exception {
		mvc.perform(post("/api/v1/runs/999/queue").header(HttpHeaders.AUTHORIZATION, bearer(1)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("RUN_NOT_ON_SALE"));
	}

	@Test
	void 다른_운행_중복_진입은_409() throws Exception {
		runs.open(2002);
		mvc.perform(post(Q).header(HttpHeaders.AUTHORIZATION, bearer(1))).andExpect(status().isOk());
		mvc.perform(post("/api/v1/runs/2002/queue").header(HttpHeaders.AUTHORIZATION, bearer(1)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("QUEUE_DUPLICATED"));
	}

	@Test
	void 좌석_API는_토큰_없이_부르면_모두_403() throws Exception {
		mvc.perform(post(Q).header(HttpHeaders.AUTHORIZATION, bearer(1))).andExpect(status().isOk());

		mvc.perform(get(SEATS).header(HttpHeaders.AUTHORIZATION, bearer(1)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("QUEUE_TOKEN_REQUIRED"));
		mvc.perform(get(SEATS).header(HttpHeaders.AUTHORIZATION, bearer(1))
				.header(QueueSeatTokenInterceptor.HEADER, "fake"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("QUEUE_TOKEN_INVALID"));
		// 아예 줄을 안 선 회원
		mvc.perform(get(SEATS).header(HttpHeaders.AUTHORIZATION, bearer(2))
				.header(QueueSeatTokenInterceptor.HEADER, "fake"))
				.andExpect(status().isForbidden());
		mvc.perform(get(SEATS)).andExpect(status().isUnauthorized());
	}

	@Test
	void 입장_후_받은_토큰으로_좌석_API가_열리고_이탈하면_다시_막힌다() throws Exception {
		mvc.perform(post(Q).header(HttpHeaders.AUTHORIZATION, bearer(1))).andExpect(status().isOk());
		admitter.admit(RUN);

		String me = mvc.perform(get(Q + "/me").header(HttpHeaders.AUTHORIZATION, bearer(1)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("ADMITTED"))
				.andExpect(jsonPath("$.passToken").exists())
				.andExpect(jsonPath("$.passExpiresAt").exists())
				.andReturn().getResponse().getContentAsString();
		String token = JsonMapper.builder().build().readTree(me).get("passToken").asText();

		mvc.perform(get(SEATS).header(HttpHeaders.AUTHORIZATION, bearer(1)).header(QueueSeatTokenInterceptor.HEADER, token))
				.andExpect(status().isOk());
		// 다른 회원이 훔쳐 써도 막힌다
		mvc.perform(get(SEATS).header(HttpHeaders.AUTHORIZATION, bearer(2)).header(QueueSeatTokenInterceptor.HEADER, token))
				.andExpect(status().isForbidden());

		mvc.perform(delete(Q + "/me").header(HttpHeaders.AUTHORIZATION, bearer(1))).andExpect(status().isNoContent());
		mvc.perform(get(SEATS).header(HttpHeaders.AUTHORIZATION, bearer(1)).header(QueueSeatTokenInterceptor.HEADER, token))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("QUEUE_TOKEN_INVALID"));
	}
}
