package com.jejari.notification;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** 알림이 쓰는 운영 값 (app_policy). 스캐너가 몇 초에 한 번 읽는 정도라 캐시 없이 읽고, 못 읽으면 기본값. */
public class NotificationPolicy {

	private static final Logger log = LoggerFactory.getLogger(NotificationPolicy.class);

	/** 결제 기한 몇 초 전에 PAY_REMINDER (기능설계서 EVT-06 [가정] 2분) */
	public static final String PAY_REMINDER_SEC = "notification.pay_reminder_sec";
	public static final String BATCH_SIZE = "scanner.batch_size";

	private static final Map<String, Long> DEFAULTS = Map.of(PAY_REMINDER_SEC, 120L, BATCH_SIZE, 500L);

	private final JdbcTemplate jdbc;

	public NotificationPolicy(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public long getLong(String key) {
		long def = DEFAULTS.get(key);
		try {
			List<String> v = jdbc.queryForList("SELECT policy_value FROM app_policy WHERE policy_key = ?", String.class, key);
			if (!v.isEmpty() && v.get(0) != null) {
				long n = Long.parseLong(v.get(0).strip());
				return n > 0 ? n : def;
			}
		} catch (RuntimeException e) {
			log.warn("정책 {} 읽기 실패, 기본값 {} 사용: {}", key, def, e.toString());
		}
		return def;
	}
}
