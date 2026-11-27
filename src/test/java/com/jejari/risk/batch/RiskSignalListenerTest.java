package com.jejari.risk.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** 어떤 예약 이벤트가 위험 점수 재계산을 깨우는가 (DB·Redis 없이 이벤트 해석만) */
class RiskSignalListenerTest {

	final RiskSignalListener listener = new RiskSignalListener(null, JsonMapper.builder().build(), null);

	static String event(String type, String reason) {
		String payload = reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}";
		return "{\"eventId\":\"e1\",\"eventType\":\"" + type + "\",\"aggregateType\":\"RESERVATION\",\"aggregateId\":\"42\","
				+ "\"seq\":1,\"payload\":" + payload + "}";
	}

	@Test
	void 확정_취소는_깨운다() {
		assertThat(listener.reservationId(event("RESERVATION_CONFIRMED", null))).isEqualTo(42L);
		assertThat(listener.reservationId(event("RESERVATION_CANCELED", "USER"))).isEqualTo(42L);
	}

	@Test
	void 결제_실패로_만료된_예약은_깨운다_카드_대입을_바로_세게() {
		assertThat(listener.reservationId(event("RESERVATION_EXPIRED", "PAY_FAILED"))).isEqualTo(42L);
	}

	@Test
	void 시간_만료나_다른_이벤트는_깨우지_않는다() {
		assertThat(listener.reservationId(event("RESERVATION_EXPIRED", "TIMEOUT"))).isNull();
		assertThat(listener.reservationId(event("RESERVATION_EXPIRED", null))).isNull();
		assertThat(listener.reservationId(event("SEAT_HELD", null))).isNull();
	}
}
