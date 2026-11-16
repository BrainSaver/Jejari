package com.jejari.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class BehaviorMetaTest {

	@Test
	void ID_숫자_코드는_통과() {
		var r = BehaviorMeta.sanitize(Map.of("reservationId", 123L, "seatIds", List.of(12, 13), "seatCount", 2,
				"errorCode", "SEAT_ALREADY_TAKEN", "rejected", true, "fromStationId", "3900114",
				"cancelKind", BehaviorType.CANCEL));
		assertThat(r.dropped()).isZero();
		assertThat(r.meta()).containsEntry("seatIds", List.of(12L, 13L)).containsEntry("errorCode", "SEAT_ALREADY_TAKEN")
				.containsEntry("cancelKind", "CANCEL");
	}

	@Test
	void 개인정보처럼_보이는_값은_빠진다() {
		Map<String, Object> raw = new HashMap<>();
		raw.put("email", "hong@example.com"); // @
		raw.put("name", "홍길동"); // 한글
		raw.put("phone", "01012345678"); // 9자리 이상 숫자 문자열
		raw.put("card", "4111111111111111");
		raw.put("memo", "free text here"); // 공백
		raw.put("ok", 1);
		var r = BehaviorMeta.sanitize(raw);
		assertThat(r.meta()).containsOnlyKeys("ok");
		assertThat(r.dropped()).isEqualTo(5);
	}

	@Test
	void 크기_폭탄과_이상한_키는_빠진다() {
		Map<String, Object> raw = new HashMap<>();
		raw.put("$where", 1); // Mongo 연산자처럼 보이는 키
		raw.put("a.b", 1);
		raw.put("big", java.util.Collections.nCopies(51, 1)); // 목록 51개
		raw.put("long", "x".repeat(65));
		raw.put("nan", Double.NaN);
		raw.put("nested", Map.of("k", 1));
		var r = BehaviorMeta.sanitize(raw);
		assertThat(r.meta()).isEmpty();
		assertThat(r.dropped()).isEqualTo(6);
	}

	@Test
	void 키는_최대_20개() {
		Map<String, Object> raw = new HashMap<>();
		for (int i = 0; i < 25; i++) {
			raw.put("k" + i, i);
		}
		var r = BehaviorMeta.sanitize(raw);
		assertThat(r.meta()).hasSize(20);
		assertThat(r.dropped()).isEqualTo(5);
	}
}
