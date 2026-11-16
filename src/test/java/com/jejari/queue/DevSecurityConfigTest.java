package com.jejari.queue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.ActorType;
import com.jejari.auth.AuthPrincipal;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.queue.dev.DevSecurityConfig;
import com.jejari.support.WebSecurityTestConfig;

/**
 * 개발 모드의 보안 체인: X-Member-Id 헤더와 /api/v1/dev/** 만 JWT 없이 통과하고, 나머지는 평소처럼 JWT.
 * 운영(dev-mode=false)에서는 이 설정이 없으므로 헤더만으로 로그인되지 않는다는 것도 확인합니다.
 */
@WebMvcTest(controllers = DevSecurityConfigTest.Probe.class)
@WebSecurityTestConfig
@Import({DevSecurityConfig.class, DevSecurityConfigTest.Probe.class})
@TestPropertySource(properties = "jejari.dev-mode=true")
class DevSecurityConfigTest {

	static final String SID = "3f2b8c1e-1111-4222-8333-444455556666";

	@Autowired
	MockMvc mvc;

	@Autowired
	AccessTokenIssuer issuer;

	@RestController
	static class Probe {
		@GetMapping("/api/v1/probe/who")
		java.util.Map<String, Object> who(@AuthenticationPrincipal AuthPrincipal me) {
			return java.util.Map.of("memberId", me.memberId());
		}

		@GetMapping("/api/v1/dev/ping")
		String ping() {
			return "pong";
		}
	}

	@Test
	void 헤더의_회원_ID로_로그인한_것으로_본다() throws Exception {
		mvc.perform(get("/api/v1/probe/who").header("X-Member-Id", "55"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.memberId").value(55));
	}

	@Test
	void 숫자가_아닌_헤더는_401() throws Exception {
		mvc.perform(get("/api/v1/probe/who").header("X-Member-Id", "abc"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
	}

	@Test
	void 개발용_관리_API는_로그인_없이_열린다() throws Exception {
		mvc.perform(get("/api/v1/dev/ping")).andExpect(status().isOk());
	}

	@Test
	void 헤더도_토큰도_없으면_평소처럼_401() throws Exception {
		mvc.perform(get("/api/v1/probe/who")).andExpect(status().isUnauthorized());
	}

	@Test
	void JWT도_그대로_동작한다() throws Exception {
		String token = "Bearer " + issuer.issue(ActorType.MEMBER, 7, SID).value();
		mvc.perform(get("/api/v1/probe/who").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.memberId").value(7));
	}
}
