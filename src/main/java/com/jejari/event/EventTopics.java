package com.jejari.event;

/** Kafka 토픽과 소비 그룹 이름 (기능설계서 8장, docker/kafka/create-topics.sh). */
public final class EventTopics {

	public static final String RESERVATION = "jejari.reservation";
	public static final String WAITLIST = "jejari.waitlist";

	/** 소비 그룹 (concurrency.md §12 표). processed_event.consumer_group에 그대로 들어갑니다. */
	public static final String GROUP_SEAT_RELEASE = "seat-release";
	public static final String GROUP_NOTIFICATION = "notification";
	public static final String GROUP_RISK_SIGNAL = "risk-signal";
	public static final String GROUP_REFUND = "refund";

	/** Kafka 헤더. 값(JSON)을 열지 않고도 중복 판별·라우팅을 할 수 있게 같이 실어 보냅니다. */
	public static final String HEADER_EVENT_ID = "event_id";
	public static final String HEADER_EVENT_TYPE = "event_type";

	private EventTopics() {
	}
}
