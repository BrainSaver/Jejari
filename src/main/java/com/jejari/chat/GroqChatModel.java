package com.jejari.chat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Groq(OpenAI 호환) chat/completions 호출. 키는 환경변수 GROQ_API_KEY로만 받는다 (코드·설정 파일·로그에 남기지 않음).
 * 실패 로그에는 상태 코드·예외 종류만 남기고 요청·응답 본문은 남기지 않는다.
 */
@Component
public class GroqChatModel implements ChatModel {
	private static final Logger log = LoggerFactory.getLogger(GroqChatModel.class);

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final JsonMapper json;
	private final String apiKey;
	private final String model;
	private final String url;

	public GroqChatModel(JsonMapper json, @Value("${jejari.chat.groq-api-key:}") String apiKey,
			@Value("${jejari.chat.model:openai/gpt-oss-120b}") String model,
			@Value("${jejari.chat.url:https://api.groq.com/openai/v1/chat/completions}") String url) {
		this.json = json;
		this.apiKey = apiKey == null ? "" : apiKey.strip();
		this.model = model;
		this.url = url;
	}

	@Override
	public boolean enabled() {
		return !apiKey.isEmpty();
	}

	@Override
	public Reply complete(List<JsonNode> messages, List<JsonNode> tools) {
		if (!enabled()) {
			throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "챗봇이 아직 켜져 있지 않습니다.");
		}
		ObjectNode body = json.createObjectNode();
		body.put("model", model);
		body.put("temperature", 0.2);
		body.put("max_tokens", 1500); // 추론 모델은 생각하는 데도 토큰을 쓴다
		body.set("messages", json.valueToTree(messages));
		if (!tools.isEmpty()) {
			body.set("tools", json.valueToTree(tools));
			body.put("tool_choice", "auto");
		}
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
					.header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
					.build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (res.statusCode() == 429) {
				throw new BusinessException(CommonErrorCode.RATE_LIMITED, "챗봇이 바쁩니다. 잠시 후 다시 물어봐 주세요.");
			}
			if (res.statusCode() != 200) {
				log.warn("Groq 응답 {}", res.statusCode());
				throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "챗봇이 지금 답하지 못합니다.");
			}
			JsonNode msg = json.readTree(res.body()).path("choices").path(0).path("message");
			List<ToolCall> calls = new ArrayList<>();
			for (JsonNode c : msg.path("tool_calls")) {
				calls.add(new ToolCall(c.path("id").asString(""), c.path("function").path("name").asString(""),
						c.path("function").path("arguments").asString("{}")));
			}
			return new Reply(msg.path("content").asString(""), calls, msg);
		} catch (BusinessException e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "챗봇이 지금 답하지 못합니다.");
		} catch (Exception e) {
			log.warn("Groq 호출 실패: {}", e.getClass().getSimpleName());
			throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "챗봇이 지금 답하지 못합니다.");
		}
	}
}
