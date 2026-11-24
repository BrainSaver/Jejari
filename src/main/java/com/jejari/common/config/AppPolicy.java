package com.jejari.common.config;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jejari.common.policy.PolicyBounds;

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
public class AppPolicy implements PolicyBounds {

	public static final String PAY_DEADLINE_MIN = "reservation.pay_deadline_min";
	public static final String IDEM_TTL_SEC = "idem.ttl_sec";
	public static final String SCANNER_HOLD_INTERVAL_SEC = "scanner.hold_interval_sec";
	public static final String SCANNER_BATCH_SIZE = "scanner.batch_size";
	public static final String REOFFER_HOLD_SEC = "waitlist.reoffer_hold_sec";
	/**
	 * 선점·제안 키(Redis seat:hold)를 결제 마감보다 이만큼 늦게 사라지게 한다 (concurrency.md §0.2). 그 사이 스캐너가 만료 처리하며
	 * 키를 REOFFER로 바꿔 풀린 좌석을 대기자에게 먼저 넘긴다. 0이면 예전 동작(마감에 키가 사라짐). ERD v35에 들어가기 전에는 이 기본값
	 */
	public static final String HOLD_RELEASE_GRACE_SEC = "reservation.hold_release_grace_sec";
	public static final String SEATMAP_CACHE_MS = "seatmap.cache_ms";
	/** 좌석 조회·선점 속도 제한 (SeatRateLimiter). ERD v34에서 app_policy에 들어가기 전에는 아래 기본값으로 동작 */
	public static final String SEATMAP_RATE_BURST = "seatmap.rate_burst";
	public static final String SEATMAP_RATE_PER_SEC = "seatmap.rate_per_sec";
	public static final String SEATMAP_ABUSE_THRESHOLD = "seatmap.abuse_threshold";
	public static final String SEATMAP_ABUSE_WINDOW_SEC = "seatmap.abuse_window_sec";
	public static final String SEATMAP_COOLDOWN_SEC = "seatmap.cooldown_sec";
	public static final String SALE_OPEN_INTERVAL_SEC = "sale.open_interval_sec";
	/** 운행별 단체 할당석 비율 (concurrency.md §18.1). 행사 운행은 sale_event.group_quota_rate가 우선. ERD v36 */
	public static final String GROUP_QUOTA_RATE = "group.quota_rate";
	/** 출발 며칠 전까지 안 쓰인 할당석을 일반 판매로 돌릴지 (§18.4). ERD v36 */
	public static final String GROUP_QUOTA_RELEASE_DAYS = "group.quota_release_days";
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
			Map.entry(HOLD_RELEASE_GRACE_SEC, "10"),
			Map.entry(SEATMAP_CACHE_MS, "1000"),
			Map.entry(SEATMAP_RATE_BURST, "10"),
			Map.entry(SEATMAP_RATE_PER_SEC, "1"),
			Map.entry(SEATMAP_ABUSE_THRESHOLD, "20"),
			Map.entry(SEATMAP_ABUSE_WINDOW_SEC, "60"),
			Map.entry(SEATMAP_COOLDOWN_SEC, "60"),
			Map.entry(SALE_OPEN_INTERVAL_SEC, "10"),
			Map.entry(GROUP_QUOTA_RATE, "0.05"),
			Map.entry(GROUP_QUOTA_RELEASE_DAYS, "30"),
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

	// ── 관리자 화면(ADM-08)이 저장 전에 거절하는 기준 (PolicyBounds, 2026-11-19) ──
	// 이 클래스는 범위를 검사하지 않고 읽으므로(0이면 녹화가 바로 지워지는 등) 관리자 쪽에서 막는다.
	// 대기·리플레이는 강다인 님, idem.ttl_sec 하한은 팀장님 확정. 예약·스캐너·좌석 배치도·판매 오픈(강동균 님)은 답을 받은 뒤 추가

	@Override
	public Map<String, Range> bounds() {
		return Map.ofEntries(
				range(WAITLIST_MAX_PER_RUN, 1, 5),
				range(WAITLIST_MAX_ACTIVE, 1, 10),
				range(REOFFER_HOLD_SEC, 30, 600),
				range(HOLD_RELEASE_GRACE_SEC, 0, 60),      // 0 = 끔. 스캐너 주기(기본 5초)+이벤트 지연보다 길어야 효과가 있다 (권장 ≥ 주기+5)
				range(WAITLIST_PENALTY_MS_PER_POINT, 0, 3_600_000),
				range(REPLAY_RETENTION_DAYS, 7, 180),      // 0이면 녹화가 바로 지워짐
				range(REPLAY_MAX_PATH_POINTS, 5, 200),
				range(REPLAY_BATCH_SEC, 1, 60),
				range(REPLAY_MAX_EVENTS_PER_SESSION, 100, 50_000),
				range(REPLAY_FULL_RECORD_SCORE, 0, 100),
				range(IDEM_TTL_SEC, 600, 86_400),
				// 0이면 좌석 화면이 열리지 않거나(burst·rate) 영영 풀리지 않는(cooldown) 값이라 막는다
				range(SEATMAP_RATE_BURST, 1, 100),
				range(SEATMAP_RATE_PER_SEC, 1, 50),
				range(SEATMAP_ABUSE_THRESHOLD, 3, 1000),
				range(SEATMAP_ABUSE_WINDOW_SEC, 10, 3600),
				range(SEATMAP_COOLDOWN_SEC, 5, 3600),
				range(GROUP_QUOTA_RELEASE_DAYS, 0, 60));       // 판매는 출발 2개월 전부터라 그보다 길면 할당석이 만들어지지 않음         // 결제 마감 전에 중복 방지 키가 사라지지 않게 (팀장님)
	}

	@Override
	public Map<String, DecimalRange> decimalBounds() {
		return Map.of(REPLAY_FULL_RECORD_RATIO, DecimalRange.of("0", "1"),
				GROUP_QUOTA_RATE, DecimalRange.of("0", "0.5")); // 절반 넘게 묶으면 개인 예매가 사실상 막힘
	}

	private static Entry<String, Range> range(String key, long min, long max) {
		return Map.entry(key, new Range(min, max));
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
