package com.jejari.behavior.sink;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.jejari.behavior.BehaviorEvent;

import tools.jackson.databind.json.JsonMapper;

class BehaviorDocumentsTest {

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String H = "a".repeat(64);
	static final String UUID1 = "6f1c2d3e-4b5a-4c6d-8e7f-0123456789ab";

	static BehaviorEvent event(String eventId, String type, String ts, String channel, int status) {
		return new BehaviorEvent(eventId, type, ts, 1001L, UUID1, H, H, 9001L, channel, "Mozilla/5.0", status,
				Map.of("seatIds", java.util.List.of(12, 13), "phone", "01012345678"));
	}

	static String json(BehaviorEvent e) {
		return JSON.writeValueAsString(e);
	}

	@Test
	void 정상_메시지는_ERD_컬럼명으로_바뀌고_만료일이_붙는다() {
		var p = BehaviorDocuments.parse(json(event(UUID1, "SEAT_HOLD", "2026-11-20T10:00:00.123+09:00", "WEB", 409)), JSON);
		assertThat(p).isNotNull();
		Document d = BehaviorDocuments.toDocument(p, "c".repeat(64), 250, 180);
		assertThat(d.getString("event_id")).isEqualTo(UUID1);
		assertThat(d.getString("event_type")).isEqualTo("SEAT_HOLD");
		assertThat(d.getInteger("response_status")).isEqualTo(409);
		assertThat(d.getInteger("interval_ms")).isEqualTo(250);
		assertThat(d.getString("ci_hash")).isEqualTo("c".repeat(64));
		assertThat(d.getDate("ts").toInstant()).isEqualTo(Instant.parse("2026-11-20T01:00:00.123Z"));
		Date exp = d.getDate("expire_at");
		assertThat(exp.getTime() - d.getDate("ts").getTime()).isEqualTo(TimeUnit.DAYS.toMillis(180));
		// 보낸 쪽을 거치지 않고 토픽에 직접 넣은 개인정보도 저장 전에 한 번 더 걸러진다
		assertThat(d.get("meta", Document.class)).containsKey("seatIds").doesNotContainKey("phone");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "not json", "{}", "[1,2]", "{\"eventId\":\"x\"}"})
	void 깨진_메시지는_건너뛴다(String msg) {
		assertThat(BehaviorDocuments.parse(msg, JSON)).isNull();
	}

	@Test
	void 필드_형식이_틀리면_건너뛴다() {
		String ts = "2026-11-20T10:00:00+09:00";
		assertThat(BehaviorDocuments.parse(json(event("not-uuid", "SEAT_HOLD", ts, "WEB", 200)), JSON)).isNull();
		assertThat(BehaviorDocuments.parse(json(event(UUID1, "DROP_TABLE", ts, "WEB", 200)), JSON)).isNull();
		assertThat(BehaviorDocuments.parse(json(event(UUID1, "SEAT_HOLD", "yesterday", "WEB", 200)), JSON)).isNull();
		assertThat(BehaviorDocuments.parse(json(event(UUID1, "SEAT_HOLD", ts, "TV", 200)), JSON)).isNull();
		assertThat(BehaviorDocuments.parse(json(event(UUID1, "SEAT_HOLD", ts, "WEB", 999)), JSON)).isNull();
		var rawIp = new BehaviorEvent(UUID1, "SEAT_HOLD", ts, 1L, null, null, "203.0.113.5", null, "WEB", null, 200, Map.of());
		assertThat(BehaviorDocuments.parse(json(rawIp), JSON)).as("해시가 아닌 원문 IP").isNull();
		assertThat(BehaviorDocuments.parse("x".repeat(20_000), JSON)).as("너무 큰 메시지").isNull();
	}

	@Test
	void 처리_시간은_정상_범위만_남긴다_예전_메시지는_없음() {
		String ts = "2026-11-23T10:00:00+09:00";
		java.util.function.Function<Long, Document> doc = ms -> BehaviorDocuments.toDocument(BehaviorDocuments.parse(json(
				new BehaviorEvent(UUID1, "VIEW_SEAT", ts, 1001L, UUID1, H, H, 9001L, "WEB", "Mozilla/5.0", 200, Map.of(), ms)),
				JSON), null, null, 1);
		assertThat(doc.apply(120L).get("elapsed_ms")).isEqualTo(120L); // Kafka JSON을 거쳐도 남음
		assertThat(doc.apply(null).containsKey("elapsed_ms")).isFalse(); // 예전 메시지
		assertThat(doc.apply(-5L).containsKey("elapsed_ms")).isFalse();
		assertThat(doc.apply(90_000_000L).containsKey("elapsed_ms")).isFalse();
	}
}
