package com.jejari.payment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 결제가 쓰는 운영 값 (app_policy). DB를 못 읽거나 값이 이상하면 아래 기본값(ERD 예시값)을 씁니다.
 * 1초 동안 앱 안에 들고 있으므로 값을 바꾸면 최대 1초 뒤 반영됩니다.
 */
public class PaymentPolicy {

	private static final Logger log = LoggerFactory.getLogger(PaymentPolicy.class);

	/** PG 승인 요청 타임아웃(초) */
	public static final String PG_TIMEOUT_SEC = "payment.pg_timeout_sec";
	/** 결제 시작 시 마감 연장 여유(초) */
	public static final String EXTEND_GRACE_SEC = "payment.extend_grace_sec";
	/** 결과를 모를 때 다음 조회까지(초) */
	public static final String PG_RECHECK_SEC = "payment.pg_recheck_sec";
	/** 결과를 모를 때 관리자 알림까지(분) */
	public static final String PG_UNKNOWN_MAX_MIN = "payment.pg_unknown_max_min";
	/** 멱등 키 보관(초) */
	public static final String IDEM_TTL_SEC = "idem.ttl_sec";
	/** 스캐너 한 번에 읽는 건수 */
	public static final String SCANNER_BATCH_SIZE = "scanner.batch_size";

	private static final Map<String, Long> DEFAULTS = Map.of(
			PG_TIMEOUT_SEC, 180L,
			EXTEND_GRACE_SEC, 60L,
			PG_RECHECK_SEC, 60L,
			PG_UNKNOWN_MAX_MIN, 30L,
			IDEM_TTL_SEC, 600L,
			SCANNER_BATCH_SIZE, 500L);
	private static final long CACHE_NANOS = 1_000_000_000L;

	private record Cached(long value, long expiresAtNanos) {
	}

	private final JdbcTemplate jdbc;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	public PaymentPolicy(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public long getLong(String key) {
		Long def = DEFAULTS.get(key);
		if (def == null) {
			throw new IllegalArgumentException("알 수 없는 정책 키: " + key);
		}
		Cached c = cache.get(key);
		if (c != null && System.nanoTime() - c.expiresAtNanos < 0) {
			return c.value;
		}
		long v = def;
		try {
			List<String> db = jdbc.queryForList("SELECT policy_value FROM app_policy WHERE policy_key = ?", String.class, key);
			if (!db.isEmpty() && db.get(0) != null) {
				v = Long.parseLong(db.get(0).strip());
			}
		} catch (RuntimeException e) {
			log.warn("정책 {} 읽기 실패, 기본값 {} 사용: {}", key, def, e.toString());
		}
		cache.put(key, new Cached(v, System.nanoTime() + CACHE_NANOS));
		return v;
	}
}
