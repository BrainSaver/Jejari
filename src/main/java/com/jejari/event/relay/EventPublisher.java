package com.jejari.event.relay;

/**
 * 릴레이가 이벤트를 내보내는 출구. 실제로는 Kafka({@link KafkaEventPublisher})이고,
 * 테스트에서는 실패를 마음대로 만들 수 있는 가짜로 바꿉니다.
 */
public interface EventPublisher {

	/**
	 * 브로커가 받았다고 확인할 때까지 기다립니다 (acks=all). 실패하면 예외를 던집니다.
	 * 돌아오기 전에는 outbox 행을 PUBLISHED로 바꾸지 않습니다.
	 */
	void publish(String topic, String key, String eventId, String eventType, String value) throws Exception;
}
