package com.jejari.member;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.jejari.common.policy.PolicyBounds;
import com.jejari.queue.redis.QueueKeys;

/**
 * 인증 정책값(app_policy auth.*). 원본은 Redis 해시 policy:cache, 없으면 ERD 시드값.
 *
 * <p>보안 하한: 정책 캐시(Redis)가 조작되거나 오타가 나도 보안이 약해지지 않게, 범위를 벗어난 값은 버리고 기본값을 쓴다.
 * 예: Argon2 메모리를 OWASP 최소(19 MiB) 아래로, 잠금 기준을 20회 위로 올리는 값은 받지 않는다.
 */
@Component
@MemberAuthEnabled
public class AuthPolicy implements PolicyBounds {
	private static final Logger log = LoggerFactory.getLogger(AuthPolicy.class);
	private static final long CACHE_NANOS = 1_000_000_000L;

	public static final String ARGON2_PARAMS = "auth.argon2_params";
	public static final String MAX_CONCURRENT_HASH = "auth.max_concurrent_hash";
	public static final String LOCK_AFTER_FAILURES = "auth.lock_after_failures";
	public static final String LOCK_MINUTES = "auth.lock_minutes";
	/** [제안] app_policy에 없는 새 키: IP당 분당 로그인 시도 */
	public static final String LOGIN_RATE_PER_MIN = "auth.login_rate_per_min";
	/** [제안] IP당 시간당 본인인증·가입 시도 */
	public static final String SIGNUP_RATE_PER_HOUR = "auth.signup_rate_per_hour";
	/** [제안] 본인인증 토큰 유효 시간(초) */
	public static final String VERIFY_TOKEN_TTL_SEC = "auth.verify_token_ttl_sec";
	public static final String LOGIN_ATTEMPT_RETENTION_DAYS = "login_attempt.retention_days";

	/** 키 → {기본값, 최소, 최대} */
	private static final Map<String, long[]> NUMBERS = Map.of(
			MAX_CONCURRENT_HASH, new long[] {32, 1, 256},
			LOCK_AFTER_FAILURES, new long[] {5, 3, 20},
			LOCK_MINUTES, new long[] {15, 5, 1440},
			LOGIN_RATE_PER_MIN, new long[] {20, 1, 600},
			SIGNUP_RATE_PER_HOUR, new long[] {10, 1, 100},
			VERIFY_TOKEN_TTL_SEC, new long[] {600, 60, 1800},
			LOGIN_ATTEMPT_RETENTION_DAYS, new long[] {30, 1, 365});

	private record Cached(Object value, long expiresAt) {}

	private final StringRedisTemplate redis;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	public AuthPolicy(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** 관리자 화면(ADM-08)이 저장 전에 같은 범위로 거절하도록 알려 준다 (읽을 때 쓰는 NUMBERS와 같은 값) */
	@Override
	public Map<String, Range> bounds() {
		Map<String, Range> out = new java.util.HashMap<>();
		NUMBERS.forEach((k, spec) -> out.put(k, new Range(spec[1], spec[2])));
		return out;
	}

	public long number(String key) {
		long[] spec = NUMBERS.get(key);
		if (spec == null) {
			throw new IllegalArgumentException("알 수 없는 인증 정책 키: " + key);
		}
		return (long) cached(key, () -> {
			String raw = raw(key);
			if (raw == null) {
				return spec[0];
			}
			try {
				long v = Long.parseLong(raw.strip());
				if (v >= spec[1] && v <= spec[2]) {
					return v;
				}
			} catch (NumberFormatException ignored) {
				// 아래에서 경고
			}
			log.warn("인증 정책 {} 값이 허용 범위({}~{}) 밖이라 기본값 {}을 씀", key, spec[1], spec[2], spec[0]);
			return spec[0];
		});
	}

	public Argon2Params argon2() {
		return (Argon2Params) cached(ARGON2_PARAMS, () -> {
			String raw = raw(ARGON2_PARAMS);
			if (raw == null) {
				return Argon2Params.DEFAULT;
			}
			try {
				return Argon2Params.parse(raw);
			} catch (IllegalArgumentException e) {
				log.warn("인증 정책 {} 값을 쓸 수 없어 기본값 {}을 씀: {}", ARGON2_PARAMS, Argon2Params.DEFAULT, e.getMessage());
				return Argon2Params.DEFAULT;
			}
		});
	}

	private Object cached(String key, java.util.function.Supplier<Object> loader) {
		Cached c = cache.get(key);
		long now = System.nanoTime();
		if (c != null && now - c.expiresAt < 0) {
			return c.value;
		}
		Object v = loader.get();
		cache.put(key, new Cached(v, now + CACHE_NANOS));
		return v;
	}

	private String raw(String key) {
		Object v = redis.opsForHash().get(QueueKeys.POLICY_CACHE, key);
		return v == null ? null : v.toString();
	}

	/**
	 * Argon2id 파라미터. 형식 {@code m=19456,t=2,p=1} (m은 KiB).
	 * 하한은 OWASP 권장 최소(m=19456,t=2,p=1), 상한은 서버 메모리·CPU 보호(로그인 폭주 시 해시 1회 × 동시 수).
	 */
	public record Argon2Params(int memoryKib, int iterations, int parallelism) {
		public static final Argon2Params DEFAULT = new Argon2Params(19456, 2, 1);

		public Argon2Params {
			if (memoryKib < 19456 || memoryKib > 262144) {
				throw new IllegalArgumentException("m은 19456~262144 KiB");
			}
			if (iterations < 2 || iterations > 10) {
				throw new IllegalArgumentException("t는 2~10");
			}
			if (parallelism < 1 || parallelism > 8) {
				throw new IllegalArgumentException("p는 1~8");
			}
		}

		public static Argon2Params parse(String s) {
			Integer m = null, t = null, p = null;
			for (String part : s.strip().split(",")) {
				String[] kv = part.strip().split("=", 2);
				if (kv.length != 2) {
					throw new IllegalArgumentException("형식은 m=…,t=…,p=…");
				}
				int v;
				try {
					v = Integer.parseInt(kv[1].strip());
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("숫자가 아님");
				}
				switch (kv[0].strip()) {
					case "m" -> m = v;
					case "t" -> t = v;
					case "p" -> p = v;
					default -> throw new IllegalArgumentException("알 수 없는 항목");
				}
			}
			if (m == null || t == null || p == null) {
				throw new IllegalArgumentException("m, t, p가 모두 있어야 함");
			}
			return new Argon2Params(m, t, p);
		}

		/** member_credential.hash_params에 저장하는 형식 */
		@Override
		public String toString() {
			return "m=" + memoryKib + ",t=" + iterations + ",p=" + parallelism;
		}
	}
}
