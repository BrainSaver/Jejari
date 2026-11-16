package com.jejari.common.error;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.common.security.SecurityConfig;
import com.jejari.common.web.TraceId;
import com.jejari.common.web.TraceIdFilter;

/** 실패 응답 형식 { code, message, traceId }와 traceId 전달, 임시 보안 설정을 확인합니다. DB·Redis 없이 돕니다. */
@WebMvcTest(controllers = ErrorResponseTest.TestController.class)
@Import({ErrorResponseTest.TestController.class, SecurityConfig.class, ErrorResponseWriter.class, GlobalExceptionHandler.class, TraceIdFilter.class})
class ErrorResponseTest {

	@Autowired
	MockMvc mvc;

	@Test
	void 인증_없으면_401_공통_형식() throws Exception {
		mvc.perform(get("/api/v1/test/ok"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
				.andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")))
				.andExpect(header().string(TraceId.HEADER, matchesPattern("[0-9a-f]{32}")));
	}

	@Test
	@WithMockUser
	void 일반_회원이_관리자_경로면_403() throws Exception {
		mvc.perform(get("/api/v1/admin/anything"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	@Test
	@WithMockUser
	void BusinessException은_코드의_상태와_메시지() throws Exception {
		mvc.perform(get("/api/v1/test/business").header(TraceId.HEADER, "k6-run-0001"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TEST_CONFLICT"))
				.andExpect(jsonPath("$.message").value("이미 처리됨"))
				.andExpect(jsonPath("$.traceId").value("k6-run-0001"))
				.andExpect(header().string(TraceId.HEADER, "k6-run-0001"));
	}

	@Test
	@WithMockUser
	void 허용되지_않은_traceId_헤더는_새로_만든다() throws Exception {
		mvc.perform(get("/api/v1/test/business").header(TraceId.HEADER, "bad id\r\nINJECT"))
				.andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")));
	}

	@Test
	@WithMockUser
	void 본문_검증_실패는_400() throws Exception {
		mvc.perform(post("/api/v1/test/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.message").value(matchesPattern("name: .+")));
	}

	@Test
	@WithMockUser
	void 깨진_JSON은_400() throws Exception {
		mvc.perform(post("/api/v1/test/valid").contentType(MediaType.APPLICATION_JSON).content("{"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	@WithMockUser
	void 예상못한_오류는_500이고_내부_메시지를_숨긴다() throws Exception {
		mvc.perform(get("/api/v1/test/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.message").value(CommonErrorCode.INTERNAL_ERROR.message()));
	}

	enum TestErrorCode implements ErrorCode {
		TEST_CONFLICT;

		public String code() { return name(); }
		public HttpStatus status() { return HttpStatus.CONFLICT; }
		public String message() { return "충돌"; }
	}

	record Body(@NotBlank String name) {
	}

	@RestController
	static class TestController {

		@GetMapping("/api/v1/test/ok")
		String ok() {
			return "ok";
		}

		@GetMapping("/api/v1/test/business")
		String business() {
			throw new BusinessException(TestErrorCode.TEST_CONFLICT, "이미 처리됨");
		}

		@PostMapping("/api/v1/test/valid")
		String valid(@Valid @RequestBody Body body) {
			return body.name();
		}

		@GetMapping("/api/v1/test/boom")
		String boom() {
			throw new IllegalStateException("secret internal detail");
		}
	}
}
