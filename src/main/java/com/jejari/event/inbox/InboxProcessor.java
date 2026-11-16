package com.jejari.event.inbox;

import java.util.function.Consumer;

import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.event.EventEnvelope;

/**
 * 컨슈머가 쓰는 틀. 한 트랜잭션에서 ① processed_event 도장 → ② 업무 처리 → ③ 커밋 순서로 실행합니다 (§12).
 * 오프셋 커밋은 이 메서드가 돌아온 뒤 리스너 컨테이너가 합니다 (enable-auto-commit=false).
 *
 * <pre>
 * &#64;KafkaListener(topics = EventTopics.RESERVATION, groupId = EventTopics.GROUP_NOTIFICATION)
 * void on(String value) {
 *     EventEnvelope e = EventEnvelope.parse(json, value);
 *     inbox.process(EventTopics.GROUP_NOTIFICATION, e, ev -&gt; notifications.create(ev));
 * }
 * </pre>
 *
 * 업무 처리에서 예외가 나면 도장까지 롤백되고 예외가 리스너로 올라가 Kafka가 다시 줍니다.
 */
public class InboxProcessor {

	private final InboxGuard guard;
	private final TransactionTemplate tx;

	public InboxProcessor(InboxGuard guard, TransactionTemplate tx) {
		this.guard = guard;
		this.tx = tx;
	}

	/** @return 이번에 처리했으면 true, 중복이라 건너뛰었으면 false */
	public boolean process(String consumerGroup, EventEnvelope event, Consumer<EventEnvelope> handler) {
		Boolean done = tx.execute(status -> {
			if (!guard.firstTime(consumerGroup, event.eventId())) {
				return false;
			}
			handler.accept(event);
			return true;
		});
		return Boolean.TRUE.equals(done);
	}
}
