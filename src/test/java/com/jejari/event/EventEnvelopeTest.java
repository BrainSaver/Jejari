package com.jejari.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class EventEnvelopeTest {

	final JsonMapper json = JsonMapper.builder().build();

	@Test
	void JSON으로_바꿨다가_되읽으면_같은_값이다() {
		var payload = json.readTree("{\"reservationId\":1024,\"reason\":\"PAY_FAILED\"}");
		var e = new EventEnvelope("e-1", "RESERVATION_EXPIRED", "RESERVATION", "1024", 1001,
				LocalDateTime.of(2026, 11, 16, 10, 5), payload);

		var back = EventEnvelope.parse(json, e.toJson(json));

		assertThat(back).isEqualTo(e);
		assertThat(back.payload().path("reason").asString()).isEqualTo("PAY_FAILED");
	}

	@Test
	void 예약과_결제_이벤트는_같은_토픽으로_간다() {
		// 같은 예약의 "결제 승인"과 "취소"가 다른 토픽으로 가면 소비 순서가 보장되지 않습니다
		assertThat(AggregateType.PAYMENT.topic()).isEqualTo(AggregateType.RESERVATION.topic())
				.isEqualTo(EventTopics.RESERVATION);
		assertThat(AggregateType.WAITLIST.topic()).isEqualTo(EventTopics.WAITLIST);
	}
}
