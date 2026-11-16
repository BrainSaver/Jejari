package com.jejari.event;

import java.time.LocalDateTime;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Kafka 메시지 값(JSON). 릴레이가 outbox 행으로 만들고 컨슈머가 읽습니다.
 *
 * <pre>
 * {"eventId":"…uuid…","eventType":"RESERVATION_CONFIRMED","aggregateType":"RESERVATION",
 *  "aggregateId":"1024","seq":1001,"occurredAt":"2026-11-16T10:05:00","payload":{…}}
 * </pre>
 *
 * <p>eventId는 컨슈머의 중복 판별 키입니다 (processed_event). seq는 디버깅용이며 순서는 Kafka 파티션이 보장합니다.
 */
public record EventEnvelope(String eventId, String eventType, String aggregateType, String aggregateId, long seq,
		LocalDateTime occurredAt, JsonNode payload) {

	public String toJson(JsonMapper json) {
		ObjectNode n = json.createObjectNode();
		n.put("eventId", eventId);
		n.put("eventType", eventType);
		n.put("aggregateType", aggregateType);
		n.put("aggregateId", aggregateId);
		n.put("seq", seq);
		n.put("occurredAt", occurredAt == null ? null : occurredAt.toString());
		n.set("payload", payload);
		return json.writeValueAsString(n);
	}

	public static EventEnvelope parse(JsonMapper json, String value) {
		JsonNode n = json.readTree(value);
		String at = n.path("occurredAt").asString(null);
		return new EventEnvelope(
				n.path("eventId").asString(),
				n.path("eventType").asString(),
				n.path("aggregateType").asString(),
				n.path("aggregateId").asString(),
				n.path("seq").asLong(),
				at == null ? null : LocalDateTime.parse(at),
				n.path("payload"));
	}

	/** payload를 원하는 레코드·클래스로 바꿉니다. */
	public <T> T payloadAs(JsonMapper json, Class<T> type) {
		return json.treeToValue(payload, type);
	}
}
