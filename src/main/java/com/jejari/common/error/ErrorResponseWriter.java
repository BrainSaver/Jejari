package com.jejari.common.error;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * 컨트롤러 밖(서블릿 필터, Spring Security)에서 실패 응답을 쓸 때 사용합니다.
 * GlobalExceptionHandler와 같은 { code, message, traceId } 형식입니다.
 */
@Component
public class ErrorResponseWriter {

	private final JsonMapper jsonMapper;

	public ErrorResponseWriter(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	public void write(HttpServletResponse response, ErrorCode code) throws IOException {
		write(response, code, code.message());
	}

	public void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
		if (response.isCommitted()) {
			return;
		}
		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		jsonMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code, message));
	}
}
