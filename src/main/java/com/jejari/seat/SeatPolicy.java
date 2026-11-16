package com.jejari.seat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 좌석 선점이 쓰는 운영 값 (app_policy). TTL·마감을 코드에 박지 않기 위한 장치입니다 (CLAUDE.md "Redis").
 *
 * <p>읽는 순서: Redis {@code policy:cache} → Oracle {@code app_policy} → 아래 기본값(ERD 예시값).
 * 요청마다 Redis·DB를 왕복하지 않도록 앱 안에서 1초 들고 있습니다. 바꾸면 최대 1초 뒤 반영됩니다.
 */
@Component
public class SeatPolicy {

	public static final String PAY_DEADLINE_MIN = "reservation.pay_deadline_min";
	public static final String IDEM_TTL_SEC = "idem.ttl_sec";
	public static final String SCANNER_HOLD_INTERVAL_SEC = "scanner.hold_interval_sec";
	public static final String SCANNER_BATCH_SIZE = "scanner.batch_size";
	public static final String REOFFER_HOLD_SEC = "waitlist.reoffer_hold_sec";
	public static final String SEATMAP_CACHE_MS = "seatmap.cache_ms";

	private static final Map<String, String> DEFAULTS = Map.of(
			PAY_DEADLINE_MIN, "10",
			IDEM_TTL_SEC, "600",
			SCANNER_HOLD_INTERVAL_SEC, "5",
			SCANNER_BATCH_SIZE, "500",
			REOFFER_HOLD_SEC, "60",
			SEATMAP_CACHE_MS, "1000");
	private static final long CACHE_NANOS = 1_000_000_000L;

	private record Cached(String value, long expiresAtNanos) {
	}

	private final StringRedisTemplate redis;
	private final JdbcTemplate jdbc;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	public SeatPolicy(StringRedisTemplate redis, JdbcTemplate jdbc) {
		this.redis = redis;
		this.jdbc = jdbc;
	}

	public long getLong(String key) {
		Cached c = cache.get(key);
		if (c != null && System.nanoTime() - c.expiresAtNanos < 0) {
			return Long.parseLong(c.value);
		}
		String value = load(key);
		cache.put(key, new Cached(value, System.nanoTime() + CACHE_NANOS));
		return Long.parseLong(value);
	}

	private String load(String key) {
		Object v = redis.opsForHash().get("policy:cache", key);
		if (v != null) {
			return v.toString();
		}
		List<String> db = jdbc.queryForList("SELECT policy_value FROM app_policy WHERE policy_key = ?", String.class, key);
		if (!db.isEmpty() && db.get(0) != null) {
			return db.get(0);
		}
		String d = DEFAULTS.get(key);
		if (d == null) {
			throw new IllegalArgumentException("알 수 없는 정책 키: " + key);
		}
		return d;
	}
}
