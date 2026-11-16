package com.jejari.event.inbox;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * EVT-03 Inbox (concurrency.md §12). "이 이벤트를 이 그룹이 처리했다"는 도장을 찍습니다.
 *
 * <p>Kafka는 같은 메시지를 두 번 이상 줄 수 있습니다 (릴레이 재발행, 오프셋 커밋 전 재시작). 그래서 업무 처리와
 * <b>같은 트랜잭션</b>에서 processed_event에 먼저 INSERT하고, PK(consumer_group, event_id)가 충돌하면 이미 처리한 것으로 봅니다.
 * 업무가 롤백되면 도장도 같이 롤백되므로 "도장만 찍히고 처리는 안 된" 상태가 생기지 않습니다.
 *
 * <p>Oracle은 문장 하나가 실패해도 트랜잭션 전체를 망가뜨리지 않으므로(문장 단위 롤백), 충돌을 잡은 뒤 그대로 커밋해도 됩니다.
 */
public class InboxGuard {

	private final JdbcTemplate jdbc;

	public InboxGuard(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @return 처음 보는 이벤트면 true (처리하세요), 이미 처리했으면 false (건너뛰세요)
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean firstTime(String consumerGroup, String eventId) {
		try {
			jdbc.update("INSERT INTO processed_event (consumer_group, event_id, processed_at) VALUES (?, ?, SYSTIMESTAMP)",
					consumerGroup, eventId);
			return true;
		} catch (DuplicateKeyException e) {
			return false;
		}
	}
}
