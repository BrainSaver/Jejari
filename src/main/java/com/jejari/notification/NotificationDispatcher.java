package com.jejari.notification;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 알림 발송 (가상 채널, EVT-06). 아직 안 보낸 행({@code sent_at IS NULL})을 골라 로그로 "보내고" {@code sent_at}을 찍습니다.
 * 실제 푸시·메일은 1차 범위 밖이라, 사용자는 알림함 API로 봅니다. {@code sent_at}은 알림 → 결제 전환 분석에 씁니다.
 *
 * <p>강동균의 취소표 제안 알림(PUSH)과 이 패키지가 만든 알림(IN_APP) 모두 여기 한 곳에서 나갑니다.
 *
 * <p>{@code sent_at IS NULL} 조건은 표에 인덱스가 없어 전체를 읽습니다. 1차 데이터 크기에서는 문제없고,
 * 커지면 {@code CREATE INDEX ix_notif_unsent ON notification (CASE WHEN sent_at IS NULL THEN notification_id END);}
 * 를 ERD에 추가합니다 (아래 쿼리는 이미 그 식으로 씀).
 */
public class NotificationDispatcher {

	private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

	private static final String PICK = """
			SELECT notification_id, member_id, type, ref_type, ref_id, channel FROM notification
			 WHERE CASE WHEN sent_at IS NULL THEN notification_id END IS NOT NULL
			   AND ROWNUM <= ?""";

	private final JdbcTemplate jdbc;
	private final NotificationPolicy policy;

	public NotificationDispatcher(JdbcTemplate jdbc, NotificationPolicy policy) {
		this.jdbc = jdbc;
		this.policy = policy;
	}

	@Scheduled(fixedDelayString = "${jejari.notification.dispatch-interval-ms:1000}")
	void scheduled() {
		try {
			dispatchOnce();
		} catch (RuntimeException e) {
			log.warn("알림 발송 실패 (다음 주기에 다시)", e);
		}
	}

	/** @return 이번에 보낸 알림 수 */
	public int dispatchOnce() {
		List<Map<String, Object>> rows = jdbc.queryForList(PICK, policy.getLong(NotificationPolicy.BATCH_SIZE));
		if (rows.isEmpty()) {
			return 0;
		}
		for (Map<String, Object> r : rows) {
			// 가상 채널: 실제로 보내는 대신 기록만. 회원 ID는 내부 번호라 그대로 남긴다 (개인정보 아님)
			log.info("알림 발송(가상) id={} member={} type={} ref={}:{} channel={}", r.get("NOTIFICATION_ID"),
					r.get("MEMBER_ID"), r.get("TYPE"), r.get("REF_TYPE"), r.get("REF_ID"), r.get("CHANNEL"));
		}
		String ids = rows.stream().map(r -> String.valueOf(((Number) r.get("NOTIFICATION_ID")).longValue()))
				.collect(Collectors.joining(","));
		// sent_at IS NULL을 다시 걸어 두 번 찍지 않게 (먼저 찍은 시각이 남음)
		return jdbc.update("UPDATE notification SET sent_at = SYSTIMESTAMP, updated_at = SYSTIMESTAMP"
				+ " WHERE notification_id IN (" + ids + ") AND sent_at IS NULL");
	}
}
