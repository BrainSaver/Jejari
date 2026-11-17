package com.jejari.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 행 남기기. 보내는 것은 여기서 하지 않고 {@link NotificationDispatcher}가 합니다.
 * 그래서 누가 행을 남기든(이 클래스, 강동균의 취소표 제안) 발송 경로는 하나입니다.
 */
@Component
public class NotificationWriter {

	private final JdbcTemplate jdbc;

	public NotificationWriter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 업무(또는 Inbox) 트랜잭션 안에서만. 업무가 롤백되면 알림도 같이 사라져야 합니다 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void create(long memberId, NotificationType type, String refType, long refId) {
		jdbc.update("""
				INSERT INTO notification (notification_id, member_id, type, ref_type, ref_id, channel)
				VALUES (notification_seq.NEXTVAL, ?, ?, ?, ?, 'IN_APP')""",
				memberId, type.name(), refType, refId);
	}
}
