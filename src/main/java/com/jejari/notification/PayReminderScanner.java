package com.jejari.notification;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 결제 기한 임박 알림 PAY_REMINDER (EVT-06). 아직 HOLD인 예약 중 기한이 {@code notification.pay_reminder_sec}
 * 안으로 들어온 것에 알림 행을 하나씩 남깁니다.
 *
 * <ul>
 *   <li>후보 찾기는 HOLD 만료 스캐너와 같은 인덱스 {@code ix_resv_hold_due}(HOLD일 때만 값이 있는 CASE 인덱스)를 탑니다.</li>
 *   <li>예약당 한 번: 같은 회원·예약의 PAY_REMINDER가 있으면 건너뜀 ({@code ix_notification_member_id}).</li>
 *   <li>결제 중(PAYMENT_PENDING)은 이미 결제를 눌렀으므로 보내지 않습니다.</li>
 *   <li>1차는 앱 1대 기준. 여러 대가 동시에 돌면 드물게 2건이 생길 수 있습니다 (알림이라 감수).</li>
 * </ul>
 */
public class PayReminderScanner {

	private static final Logger log = LoggerFactory.getLogger(PayReminderScanner.class);

	private static final String INSERT_DUE = """
			INSERT INTO notification (notification_id, member_id, type, ref_type, ref_id, channel)
			SELECT notification_seq.NEXTVAL, r.member_id, 'PAY_REMINDER', 'RESERVATION', r.reservation_id, 'IN_APP'
			  FROM reservation r
			 WHERE CASE WHEN r.status = 'HOLD' THEN r.pay_deadline_at END > ?
			   AND CASE WHEN r.status = 'HOLD' THEN r.pay_deadline_at END <= ?
			   AND NOT EXISTS (SELECT 1 FROM notification n
			                    WHERE n.member_id = r.member_id AND n.type = 'PAY_REMINDER' AND n.ref_id = r.reservation_id)
			   AND ROWNUM <= ?""";

	private final JdbcTemplate jdbc;
	private final NotificationPolicy policy;
	private final Clock clock;

	public PayReminderScanner(JdbcTemplate jdbc, NotificationPolicy policy, Clock clock) {
		this.jdbc = jdbc;
		this.policy = policy;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${jejari.notification.reminder-interval-ms:10000}")
	void scheduled() {
		try {
			scanOnce();
		} catch (RuntimeException e) {
			log.warn("결제 기한 임박 알림 스캔 실패 (다음 주기에 다시)", e);
		}
	}

	/** @return 이번에 만든 알림 수 */
	public int scanOnce() {
		// 마감 시각은 앱 시계 기준 (concurrency.md §0.7)
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDateTime until = now.plusSeconds(policy.getLong(NotificationPolicy.PAY_REMINDER_SEC));
		int n = jdbc.update(INSERT_DUE, Timestamp.valueOf(now), Timestamp.valueOf(until),
				policy.getLong(NotificationPolicy.BATCH_SIZE));
		if (n > 0) {
			log.info("결제 기한 임박 알림 {}건", n);
		}
		return n;
	}
}
