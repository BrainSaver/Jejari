package com.jejari.event;

/**
 * outbox_event.aggregate_type (DDL CHECK: RESERVATION / PAYMENT / WAITLIST)과 발행할 Kafka 토픽.
 *
 * <p>토픽은 기능설계서 8장: 예약·결제·취소는 한 토픽 {@code jejari.reservation}(키 reservation_id)에 모아
 * 같은 예약의 이벤트가 한 파티션에서 순서대로 소비되게 합니다. 결제를 따로 두면 "확정"과 "취소"의 순서가 토픽 사이에서 깨집니다.
 */
public enum AggregateType {

	RESERVATION(EventTopics.RESERVATION),
	PAYMENT(EventTopics.RESERVATION),
	WAITLIST(EventTopics.WAITLIST);

	private final String topic;

	AggregateType(String topic) {
		this.topic = topic;
	}

	public String topic() {
		return topic;
	}
}
