package com.jejari.queue.domain;

import com.jejari.common.policy.PolicyBounds;
import com.jejari.queue.redis.QueueKeys;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * app_policy 값을 읽는다. 원본은 Redis 해시 policy:cache (ADM-08이 갱신).
 * 값이 없으면 ERD 예시값(v10)을 쓴다. TTL·임계값을 코드에 박지 않기 위한 장치다.
 *
 * 진입 한 건마다 Redis를 서너 번 더 왕복하지 않도록 앱 안에서 cacheMs 동안 값을 들고 있는다.
 * 정책을 바꾸면 최대 cacheMs 뒤에 반영된다 (ADM-08의 "각 서버가 다시 로딩"과 같은 성격). 0이면 캐시하지 않는다.
 */
public class PolicyProvider implements PolicyBounds {

	public static final String ADMIT_PER_SEC = "queue.admit_per_sec";
	public static final String PASS_TTL_SEC = "queue.pass_ttl_sec";
	/** [제안] app_policy에 없는 새 키. 입장 토큰을 1회 연장할 때 더해 주는 시간 */
	public static final String PASS_EXTEND_SEC = "queue.pass_extend_sec";
	/** [제안] app_policy에 없는 새 키. 대기 기록(queue:member)이 남는 최대 시간 */
	public static final String ENTRY_TTL_SEC = "queue.entry_ttl_sec";
	public static final String PENALTY_MS_PER_POINT = "risk.penalty_ms_per_point";
	public static final String MEMBER_PENALTY_RULE = "risk.member_penalty_rule";

	private static final Map<String, String> DEFAULTS = Map.of(
			ADMIT_PER_SEC, "200",
			PASS_TTL_SEC, "300",
			PASS_EXTEND_SEC, "300",
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

	// ── 관리자 화면(ADM-08)이 저장 전에 거절하는 기준 (PolicyBounds). 김성원 님 확정 2026-11-19 ──
	// 이 클래스는 범위를 검사하지 않고 읽으므로(0이면 입장이 멈춤) 관리자 쪽에서 막는다

	@Override
	public Map<String, Range> bounds() {
		return Map.of(
				ADMIT_PER_SEC, new Range(1, 2000),        // 0이면 입장이 멈춤. 상한 2000은 서버 한계가 아니라 입력 실수를 막는 값 (운영 값 200, 공용 서버 11-26 측정은 동시 600명·초당 554건)
				PASS_TTL_SEC, new Range(60, 1800),
				PASS_EXTEND_SEC, new Range(60, 1800),     // 토큰 유효 시간과 같은 범위 (app_policy에 행이 생기면 적용)
				ENTRY_TTL_SEC, new Range(1200, 86400),    // 대기 기록이 줄 서는 동안 사라지지 않게
				PENALTY_MS_PER_POINT, new Range(0, 10000));
	}

	@Override
	public Map<String, Set<String>> allowedValues() {
		return Map.of(MEMBER_PENALTY_RULE, Set.of("MAX"));
	}

	@Override
	public List<Rule> rules() {
		// 최대 지연 = 위험 점수 최대 100점 × 점당 ms ÷ 1000 = 점당 ms ÷ 10 초. 대기 기록이 그보다 먼저 사라지면 지연된 사람이 줄에서 빠짐
		return List.of(new Rule(Set.of(ENTRY_TTL_SEC, PENALTY_MS_PER_POINT),
				v -> v.num(ENTRY_TTL_SEC).compareTo(v.num(PENALTY_MS_PER_POINT).divide(BigDecimal.TEN)) > 0,
				"queue.entry_ttl_sec(초)는 risk.penalty_ms_per_point ÷ 10(위험 점수 100점의 최대 지연, 초)보다 커야 합니다. 대기 기록이 먼저 사라지면 지연된 사람이 줄에서 빠집니다."));
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
