package com.jejari.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;

import com.jejari.common.error.GlobalExceptionHandlerAccess;
import com.jejari.support.WebSecurityTestConfig;

/**
 * 화면 주소는 로그인 없이 열리고, 없는 주소는 401이 아니라 404 (2026-11-25 사이트 점검).
 * 예전엔 화면 목록에 없던 /guest·/guest/lookup·/favicon.ico·오타 주소가 모두 "로그인이 필요합니다" JSON(401)이었다.
 * API와 운영 엔드포인트는 그대로 막혀 있어야 한다.
 */
@WebMvcTest(controllers = SpaForwardController.class)
@WebSecurityTestConfig
class SpaPagesWebTest {

	@Autowired
	MockMvc mvc;

	@Test
	void 비회원_화면은_새로고침해도_화면을_준다() throws Exception {
		for (String path : new String[] { "/guest", "/guest/lookup" }) {
			mvc.perform(get(path).accept(MediaType.TEXT_HTML))
					.andExpect(status().isOk())
					.andExpect(forwardedUrl("/index.html"));
		}
	}

	@Test
	void 없는_화면_주소는_401이_아니라_404() throws Exception {
		mvc.perform(get("/this-page-does-not-exist").accept(MediaType.TEXT_HTML))
				.andExpect(status().isNotFound());
		mvc.perform(head("/no/such/page").accept(MediaType.TEXT_HTML))
				.andExpect(status().isNotFound());
	}

	@Test
	void 없는_정적_파일도_404() throws Exception {
		mvc.perform(get("/favicon.ico.missing"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void API와_actuator는_여전히_로그인_필요() throws Exception {
		mvc.perform(get("/api/v1/reservations").accept(MediaType.TEXT_HTML))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
		mvc.perform(get("/api/v1/no-such-api"))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/env"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 화면_주소라도_GET_HEAD_말고는_열지_않는다() throws Exception {
		mvc.perform(post("/guest/lookup"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 브라우저_화면_요청만_index_html로_답한다() {
		assertThat(GlobalExceptionHandlerAccess.isBrowserPage(req("GET", "/nope", "text/html,application/xhtml+xml"))).isTrue();
		assertThat(GlobalExceptionHandlerAccess.isBrowserPage(req("GET", "/nope", "application/json"))).isFalse();
		assertThat(GlobalExceptionHandlerAccess.isBrowserPage(req("GET", "/api/v1/nope", "text/html"))).isFalse();
		assertThat(GlobalExceptionHandlerAccess.isBrowserPage(req("GET", "/actuator/nope", "text/html"))).isFalse();
		assertThat(GlobalExceptionHandlerAccess.isBrowserPage(req("GET", "/assets/x.js", "text/html"))).isFalse();
		assertThat(GlobalExceptionHandlerAccess.isBrowserPage(req("POST", "/nope", "text/html"))).isFalse();
	}

	private static MockHttpServletRequest req(String method, String uri, String accept) {
		MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
		r.addHeader("Accept", accept);
		return r;
	}
}
