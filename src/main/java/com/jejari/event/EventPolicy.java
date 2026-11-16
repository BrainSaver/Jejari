package com.jejari.event;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 이벤트 모듈이 쓰는 app_policy 값. 원본은 Oracle app_policy 테이블이고, 앱 안에서 cacheMs 동안 들고 있습니다.
 * 값이 없거나 DB를 못 읽으면 ERD 예시값(DDL 시드와 같은 값)을 씁니다. 숫자를 코드 곳곳에 박지 않기 위한 장치입니다.
 */
public class EventPolicy {

	public static final String RELAY_BATCH = "outbox.relay_batch";
	public static final String LOCK_WAIT_SEC = "relay.lock_wait_sec";
	public static final String MAX_RETRY = "outbox.max_retry";
	public static final String OUTBOX_RETENTION_HOURS = "outbox.retention_hours";
	public static final String INBOX_RETENTION_DAYS = "inbox.retention_days";

	private static final Map<String, Long> DEFAULTS = Map.of(
			RELAY_BATCH, 500L,
			LOCK_WAIT_SEC, 5L,
			MAX_RETRY, 5L,
			OUTBOX_RETENTION_HOURS, 24L,
			INBOX_RETENTION_DAYS, 8L);

	private static final Logger log = LoggerFactory.getLogger(EventPolicy.class);

	private record Cached(long value, long expiresAtNanos) {}

	private final JdbcTemplate jdbc;
	private final long cacheNanos;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	public EventPolicy(JdbcTemplate jdbc, long cacheMs) {
		this.jdbc = jdbc;
		this.cacheNanos = cacheMs * 1_000_000L;
	}

	public long getLong(String key) {
		Long fallback = DEFAULTS.get(key);
		if (fallback == null) {
			throw new IllegalArgumentException("알 수 없는 정책 키: " + key);
		}
		Cached c = cache.get(key);
		if (c != null && System.nanoTime() - c.expiresAtNanos < 0) {
			return c.value;
		}
		long value = load(key, fallback);
		if (cacheNanos > 0) {
			cache.put(key, new Cached(value, System.nanoTime() + cacheNanos));
		}
		return value;
	}

	private long load(String key, long fallback) {
		try {
			var rows = jdbc.queryForList("SELECT policy_value FROM app_policy WHERE policy_key = ?", String.class, key);
			return rows.isEmpty() ? fallback : Long.parseLong(rows.get(0).trim());
		} catch (DataAccessException | NumberFormatException e) {
			log.warn("app_policy {} 를 읽지 못해 기본값 {} 사용: {}", key, fallback, e.toString());
			return fallback;
		}
	}
}
