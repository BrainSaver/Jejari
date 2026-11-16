package com.jejari.queue.domain;

import com.jejari.queue.redis.QueueKeys;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * app_policy 값을 읽는다. 원본은 Redis 해시 policy:cache (ADM-08이 갱신).
 * 값이 없으면 ERD 예시값(v10)을 쓴다. TTL·임계값을 코드에 박지 않기 위한 장치다.
 *
 * 진입 한 건마다 Redis를 서너 번 더 왕복하지 않도록 앱 안에서 cacheMs 동안 값을 들고 있는다.
 * 정책을 바꾸면 최대 cacheMs 뒤에 반영된다 (ADM-08의 "각 서버가 다시 로딩"과 같은 성격). 0이면 캐시하지 않는다.
 */
public class PolicyProvider {

	public static final String ADMIT_PER_SEC = "queue.admit_per_sec";
	public static final String PASS_TTL_SEC = "queue.pass_ttl_sec";
	/** [제안] app_policy에 없는 새 키. 대기 기록(queue:member)이 남는 최대 시간 */
	public static final String ENTRY_TTL_SEC = "queue.entry_ttl_sec";
	public static final String PENALTY_MS_PER_POINT = "risk.penalty_ms_per_point";
	public static final String MEMBER_PENALTY_RULE = "risk.member_penalty_rule";

	private static final Map<String, String> DEFAULTS = Map.of(
			ADMIT_PER_SEC, "200",
			PASS_TTL_SEC, "300",
			ENTRY_TTL_SEC, "3600",
			PENALTY_MS_PER_POINT, "1000",
			MEMBER_PENALTY_RULE, "MAX");

	private record Cached(String value, long expiresAtNanos) {}

	private final StringRedisTemplate redis;
	private final long cacheNanos;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	public PolicyProvider(StringRedisTemplate redis, long cacheMs) {
		this.redis = redis;
		this.cacheNanos = cacheMs * 1_000_000L;
	}

	public String getString(String key) {
		if (cacheNanos > 0) {
			Cached c = cache.get(key);
			if (c != null && System.nanoTime() - c.expiresAtNanos < 0) {
				return c.value;
			}
		}
		String value = load(key);
		if (cacheNanos > 0) {
			cache.put(key, new Cached(value, System.nanoTime() + cacheNanos));
		}
		return value;
	}

	private String load(String key) {
		Object v = redis.opsForHash().get(QueueKeys.POLICY_CACHE, key);
		if (v != null) {
			return v.toString();
		}
		String d = DEFAULTS.get(key);
		if (d == null) {
			throw new IllegalArgumentException("알 수 없는 정책 키: " + key);
		}
		return d;
	}

	public long getLong(String key) {
		return Long.parseLong(getString(key));
	}
}
