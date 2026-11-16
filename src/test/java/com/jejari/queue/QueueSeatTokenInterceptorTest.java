package com.jejari.queue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.ActorType;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.common.error.BusinessException;
import com.jejari.queue.api.QueueSeatTokenInterceptor;
import com.jejari.queue.domain.QueuePassService;
import com.jejari.support.WebSecurityTestConfig;

/** 좌석 API 앞단의 입장 토큰 검사. 검증 서비스는 목이라 Redis 없이 돕니다. */
@WebMvcTest(controllers = QueueSeatTokenInterceptorTest.SeatsProbe.class)
@WebSecurityTestConfig
@Import(QueueSeatTokenInterceptorTest.SeatsProbe.class)
class QueueSeatTokenInterceptorTest {

	static final String SID = "3f2b8c1e-1111-4222-8333-444455556666";

	@Autowired
	MockMvc mvc;

	@Autowired
	AccessTokenIssuer issuer;

	@MockitoBean
	QueuePassService pass;

	/** B의 좌석 배치도 API 자리 */
	@RestController
	@RequestMapping("/api/v1/runs/{runId}/seats")
	static class SeatsProbe {
		@GetMapping
		String seats(@PathVariable String runId) {
			return "seats-" + runId;
		}
	}

	String bearer(long id) {
		return "Bearer " + issuer.issue(ActorType.MEMBER, id, SID).value();
	}

	@Test
	void 로그인하지_않으면_토큰을_보기_전에_401() throws Exception {
		mvc.perform(get("/api/v1/runs/1001/seats")).andExpect(status().isUnauthorized());
		verify(pass, never()).verify(anyLong(), anyLong(), any());
	}

	@Test
	void 토큰이_없으면_403_QUEUE_TOKEN_REQUIRED() throws Exception {
		doThrow(new BusinessException(QueueErrorCode.QUEUE_TOKEN_REQUIRED)).when(pass).verify(1001, 7, null);

		mvc.perform(get("/api/v1/runs/1001/seats").header(HttpHeaders.AUTHORIZATION, bearer(7)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("QUEUE_TOKEN_REQUIRED"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
		verify(pass).verify(1001, 7, null);
	}

	@Test
	void 토큰이_틀리면_403_QUEUE_TOKEN_INVALID() throws Exception {
		doThrow(new BusinessException(QueueErrorCode.QUEUE_TOKEN_INVALID)).when(pass).verify(1001, 7, "fake");

		mvc.perform(get("/api/v1/runs/1001/seats").header(HttpHeaders.AUTHORIZATION, bearer(7))
				.header(QueueSeatTokenInterceptor.HEADER, "fake"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("QUEUE_TOKEN_INVALID"));
	}

	@Test
	void 운행_ID와_회원_ID는_경로와_로그인에서_꺼내_검증에_넘긴다() throws Exception {
		mvc.perform(get("/api/v1/runs/1001/seats").header(HttpHeaders.AUTHORIZATION, bearer(7))
				.header(QueueSeatTokenInterceptor.HEADER, "good"))
				.andExpect(status().isOk());
		verify(pass).verify(1001, 7, "good");
	}

	@Test
	void 운행_ID가_숫자가_아니면_400() throws Exception {
		mvc.perform(get("/api/v1/runs/abc/seats").header(HttpHeaders.AUTHORIZATION, bearer(7)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verify(pass, never()).verify(anyLong(), anyLong(), isNull());
	}
}
