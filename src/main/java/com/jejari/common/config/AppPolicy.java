package com.jejari.common.config;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 운영 값 읽기 (app_policy). TTL·마감·주기를 코드에 박지 않기 위한 장치입니다 (CLAUDE.md "Redis").
 *
 * <p>좌석(seat)·열차(train) 양쪽이 쓰므로 common에 둡니다. seat가 이미 train을 참조하고 있어
 * 어느 한쪽에 두면 패키지가 서로를 참조하게 됩니다.
 *
 * <p>읽는 순서: Redis {@code policy:cache} → Oracle {@code app_policy} → 아래 기본값(ERD 예시값).
 * 요청마다 Redis·DB를 왕복하지 않도록 앱 안에서 1초 들고 있습니다. 바꾸면 최대 1초 뒤 반영됩니다.
 */
@Component
public class AppPolicy {

	public static final String PAY_DEADLINE_MIN = "reservation.pay_deadline_min";
	public static final String IDEM_TTL_SEC = "idem.ttl_sec";
	public static final String SCANNER_HOLD_INTERVAL_SEC = "scanner.hold_interval_sec";
	public static final String SCANNER_BATCH_SIZE = "scanner.batch_size";
	public static final String REOFFER_HOLD_SEC = "waitlist.reoffer_hold_sec";
	public static final String SEATMAP_CACHE_MS = "seatmap.cache_ms";
	public static final String SALE_OPEN_INTERVAL_SEC = "sale.open_interval_sec";
	/** 회원당 같은 운행 활성 대기 수 (WL-01) */
	public static final String WAITLIST_MAX_PER_RUN = "waitlist.max_per_run";
	/** 회원당 전체 활성 대기 수 (WL-01) */
	public static final String WAITLIST_MAX_ACTIVE = "waitlist.max_active";
	/** 위험 점수 1점당 대기 순서를 미루는 ms (concurrency.md §9.3) */
	public static final String WAITLIST_PENALTY_MS_PER_POINT = "waitlist.penalty_ms_per_point";
	/** ADM-11 화면 녹화 (ERD app_policy 예시값) */
	public static final String REPLAY_FULL_RECORD_RATIO = "replay.full_record_ratio";
	public static final String REPLAY_FULL_RECORD_SCORE = "replay.full_record_score";
	public static final String REPLAY_RETENTION_DAYS = "replay.retention_days";
	public static final String REPLAY_MAX_PATH_POINTS = "replay.max_path_points";
	public static final String REPLAY_BATCH_SEC = "replay.batch_sec";
	public static final String REPLAY_MAX_EVENTS_PER_SESSION = "replay.max_events_per_session";

	// Map.of는 10쌍까지라 항목이 늘면서 ofEntries로 바꿨다
	private static final Map<String, String> DEFAULTS = Map.ofEntries(
			Map.entry(PAY_DEADLINE_MIN, "10"),
			Map.entry(IDEM_TTL_SEC, "600"),
			Map.entry(SCANNER_HOLD_INTERVAL_SEC, "5"),
			Map.entry(SCANNER_BATCH_SIZE, "500"),
			Map.entry(REOFFER_HOLD_SEC, "60"),
			Map.entry(SEATMAP_CACHE_MS, "1000"),
			Map.entry(SALE_OPEN_INTERVAL_SEC, "10"),
			Map.entry(WAITLIST_MAX_PER_RUN, "1"),
			Map.entry(WAITLIST_MAX_ACTIVE, "3"),
			Map.entry(WAITLIST_PENALTY_MS_PER_POINT, "180000"),
			Map.entry(REPLAY_FULL_RECORD_RATIO, "0.10"),
			Map.entry(REPLAY_FULL_RECORD_SCORE, "70"),
			Map.entry(REPLAY_RETENTION_DAYS, "90"),
			Map.entry(REPLAY_MAX_PATH_POINTS, "50"),
			Map.entry(REPLAY_BATCH_SEC, "5"),
			Map.entry(REPLAY_MAX_EVENTS_PER_SESSION, "5000"));
	private static final long CACHE_NANOS = 1_000_000_000L;

	private record Cached(String value, long expiresAtNanos) {
	}

	private final StringRedisTemplate redis;
	private final JdbcTemplate jdbc;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	public AppPolicy(StringRedisTemplate redis, JdbcTemplate jdbc) {
		this.redis = redis;
		this.jdbc = jdbc;
	}

	public long getLong(String key) {
		return Long.parseLong(get(key));
	}

	/** 소수 값 (예: replay.full_record_ratio = 0.10). 정수 값에도 쓸 수 있다 */
	public double getDouble(String key) {
		return Double.parseDouble(get(key));
	}

	private String get(String key) {
		Cached c = cache.get(key);
		if (c != null && System.nanoTime() - c.expiresAtNanos < 0) {
			return c.value;
		}
		String value = load(key);
		cache.put(key, new Cached(value, System.nanoTime() + CACHE_NANOS));
		return value;
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
