package com.jejari.notification;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jejari.event.EventEnvelope;
import com.jejari.event.EventType;

/**
 * 예약 이벤트 → 알림 행 (EVT-06). Inbox 트랜잭션 안에서 불립니다 ({@link NotificationConsumer}).
 *
 * <ul>
 *   <li>{@code RESERVATION_EXPIRED} → {@code EXPIRED} (기한 초과·결제 실패·재선점. 취소표 제안을 놓친 경우도 포함)</li>
 *   <li>{@code REFUND_COMPLETED} → {@code REFUNDED}</li>
 * </ul>
 *
 * <p>취소표 제안·건너뜀(WL-04)은 강동균의 제안 트랜잭션이 이미 행을 남기므로 여기서 다시 만들지 않습니다.
 * 결제 기한 임박은 이벤트가 아니라 시간이 기준이라 {@link PayReminderScanner}가 합니다.
 */
@Component
public class NotificationEventHandler {

	private static final Logger log = LoggerFactory.getLogger(NotificationEventHandler.class);

	private static final Map<String, NotificationType> TYPES = Map.of(
			EventType.RESERVATION_EXPIRED.name(), NotificationType.EXPIRED,
			EventType.REFUND_COMPLETED.name(), NotificationType.REFUNDED);

	private final JdbcTemplate jdbc;
	private final NotificationWriter writer;

	public NotificationEventHandler(JdbcTemplate jdbc, NotificationWriter writer) {
		this.jdbc = jdbc;
		this.writer = writer;
	}

	/** 알림을 만드는 이벤트인가. 아니면 Inbox 도장도 찍지 않습니다 (processed_event 행을 아끼려고) */
	public boolean handles(String eventType) {
		return eventType != null && TYPES.containsKey(eventType);
	}

	public void handle(EventEnvelope e) {
		NotificationType type = TYPES.get(e.eventType());
		if (type == null) {
			return;
		}
		long reservationId = Long.parseLong(e.aggregateId());
		List<Long> member = jdbc.queryForList("SELECT member_id FROM reservation WHERE reservation_id = ?", Long.class,
				reservationId);
		if (member.isEmpty()) {
			// 예약 행이 없으면 받을 사람도 없다. 다시 받아도 같으므로 건너뛴다
			log.warn("알림: 예약 {} 이 없어 {} 알림을 만들지 않음 (이벤트 {})", reservationId, type, e.eventId());
			return;
		}
		writer.create(member.get(0), type, "RESERVATION", reservationId);
	}
}
