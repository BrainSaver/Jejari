package com.jejari.chat;

import java.util.List;

import tools.jackson.databind.JsonNode;

/** 대화 모델 호출 (테스트에서는 가짜로 바꿉니다). 메시지·도구 정의는 OpenAI 호환 JSON입니다. */
public interface ChatModel {

	/** 모델이 한 번 답한 결과. 도구를 부르면 toolCalls가 채워지고 content는 비어 있을 수 있다 */
	record Reply(String content, List<ToolCall> toolCalls, JsonNode rawMessage) {
		public boolean wantsTools() {
			return toolCalls != null && !toolCalls.isEmpty();
		}
	}

	record ToolCall(String id, String name, String argumentsJson) {
	}

	Reply complete(List<JsonNode> messages, List<JsonNode> tools);

	/** 키가 없으면 false: 챗봇을 끈 상태 */
	boolean enabled();
}
