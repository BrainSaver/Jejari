package com.jejari.member;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
	/** 비회원(게스트) 예매 사용 여부 Y/N. 원본 app_policy guest.enabled (ERD v33), 없으면 N */
	public static final String GUEST_ENABLED = "guest.enabled";
	/** 게스트 개인정보 보관: 그 게스트의 마지막 운행일 + 이 일수 */
	public static final String GUEST_RETAIN_DAYS_AFTER_RUN = "guest.retain_days_after_run";
	/** IP당 시간당 비회원 예약 조회 시도 한도 (rate:guest_lookup) */
	public static final String GUEST_LOOKUP_RATE_PER_HOUR = "guest.lookup_rate_per_hour";

	// ── 인증 시도 제한 임시 해제 (ERD v39, docs/강동균/설계안-인증-시도제한-임시해제.md) ──
	/** 해제 종료 시각 {@code yyyy-MM-dd HH:mm} (Asia/Seoul). {@code OFF}면 해제 안 함. 지금 + 24시간을 넘는 값은 무시 */
	public static final String RATE_RELAX_UNTIL = "auth.rate_relax_until";
	/** 해제할 IP의 ip_hash(HMAC) 목록, 쉼표로 최대 3개. {@code NONE}이면 없음 (원본 IP는 저장하지 않음) */
	public static final String RATE_RELAX_IP_HASHES = "auth.rate_relax_ip_hashes";
	/** 해제한 사유 (정책 이력에 누가·언제와 함께 남음) */
	public static final String RATE_RELAX_REASON = "auth.rate_relax_reason";
	/** 해제 동안 IP당 시간당 본인인증·가입 한도 */
	public static final String RATE_RELAX_SIGNUP_PER_HOUR = "auth.rate_relax_signup_per_hour";
	/** 해제 동안 IP당 분당 회원 로그인 한도 (관리자 로그인은 해제하지 않음) */
	public static final String RATE_RELAX_LOGIN_PER_MIN = "auth.rate_relax_login_per_min";

	/** 해제 종료 시각은 지금부터 이 시간을 넘길 수 없다. 넘는 값은 저장도 거절하고(관리자 화면), 읽을 때도 무시한다 */
	public static final long RATE_RELAX_MAX_HOURS = 24;
	public static final int RATE_RELAX_MAX_IPS = 3;
	public static final String RELAX_OFF = "OFF";
	public static final String RELAX_NO_IPS = "NONE";
	public static final DateTimeFormatter RELAX_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final Pattern IP_HASH = Pattern.compile("[0-9a-f]{64}");
	/** 해제할 수 있는 키 → 해제 동안 쓰는 한도 키. 비회원 조회·관리자 로그인은 일부러 뺐다 */
	private static final Map<String, String> RELAXABLE = Map.of(
			SIGNUP_RATE_PER_HOUR, RATE_RELAX_SIGNUP_PER_HOUR,
			LOGIN_RATE_PER_MIN, RATE_RELAX_LOGIN_PER_MIN);

	/** 키 → {기본값, 최소, 최대} */
	// Map.of는 10쌍까지라 해제 한도를 더하면서 ofEntries로 바꿨다
	private static final Map<String, long[]> NUMBERS = Map.ofEntries(
			Map.entry(MAX_CONCURRENT_HASH, new long[] {32, 1, 256}),
			Map.entry(LOCK_AFTER_FAILURES, new long[] {5, 3, 20}),
			Map.entry(LOCK_MINUTES, new long[] {15, 5, 1440}),
			Map.entry(LOGIN_RATE_PER_MIN, new long[] {20, 1, 600}),
			Map.entry(SIGNUP_RATE_PER_HOUR, new long[] {10, 1, 100}),
			Map.entry(VERIFY_TOKEN_TTL_SEC, new long[] {600, 60, 1800}),
			Map.entry(LOGIN_ATTEMPT_RETENTION_DAYS, new long[] {30, 1, 365}),
			Map.entry(GUEST_RETAIN_DAYS_AFTER_RUN, new long[] {30, 1, 365}),
			Map.entry(GUEST_LOOKUP_RATE_PER_HOUR, new long[] {10, 1, 1000}),
			Map.entry(RATE_RELAX_SIGNUP_PER_HOUR, new long[] {2000, 1, 5000}),
			Map.entry(RATE_RELAX_LOGIN_PER_MIN, new long[] {3000, 1, 10000}));

	private record Cached(Object value, long expiresAt) {}

	private final StringRedisTemplate redis;
	private final Clock clock;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	/** 테스트용. 임시 해제 시각 비교는 시스템 시계(Asia/Seoul) */
	public AuthPolicy(StringRedisTemplate redis) {
		this(redis, Clock.system(java.time.ZoneId.of("Asia/Seoul")));
	}

	@Autowired
	public AuthPolicy(StringRedisTemplate redis, Clock clock) {
		this.redis = redis;
		this.clock = clock;
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

	/**
	 * IP별 시도 제한 한도. 이 IP가 임시 해제 대상이고 종료 시각 전이면 해제 한도, 아니면 {@link #number}와 같다.
	 *
	 * <p><b>자동 복구는 읽을 때 비교로 한다.</b> 값을 되돌리는 스케줄러가 없어서, 앱이 죽어 있었거나 아무도 손대지 않아도
	 * 종료 시각이 지나는 순간부터 평소 한도다 (1차 봇 대회 때 손으로 올린 가입 한도를 4일간 되돌리지 않았던 일의 재발 방지).
	 *
	 * @param ipHash {@code ClientIpResolver}로 정한 연결 IP의 해시. 신뢰 프록시 규칙을 그대로 쓰므로 헤더 위조로 해제 IP인 척할 수 없다
	 */
	public long limitFor(String key, String ipHash) {
		long normal = number(key);
		String relaxKey = RELAXABLE.get(key);
		if (relaxKey == null || ipHash == null || !relaxActive() || !relaxIpHashes().contains(ipHash)) {
			return normal;
		}
		return Math.max(normal, number(relaxKey)); // 해제 값을 평소보다 낮게 잘못 넣어도 더 조이지는 않는다
	}

	/** 지금 임시 해제 중인가 (종료 시각 전이고, 그 시각이 지금 + 24시간 이내) */
	public boolean relaxActive() {
		LocalDateTime until = relaxUntil();
		if (until == null) {
			return false;
		}
		LocalDateTime now = LocalDateTime.now(clock);
		return now.isBefore(until) && !until.isAfter(now.plusHours(RATE_RELAX_MAX_HOURS));
	}

	/** 해제 종료 시각. OFF·형식 오류면 null */
	public LocalDateTime relaxUntil() {
		return (LocalDateTime) cached(RATE_RELAX_UNTIL, () -> parseRelaxUntil(raw(RATE_RELAX_UNTIL)));
	}

	@SuppressWarnings("unchecked")
	public Set<String> relaxIpHashes() {
		return (Set<String>) cached(RATE_RELAX_IP_HASHES, () -> parseIpHashes(raw(RATE_RELAX_IP_HASHES)));
	}

	static LocalDateTime parseRelaxUntil(String raw) {
		if (raw == null || raw.isBlank() || RELAX_OFF.equalsIgnoreCase(raw.strip())) {
			return null;
		}
		try {
			return LocalDateTime.parse(raw.strip(), RELAX_TIME);
		} catch (DateTimeParseException e) {
			log.warn("인증 정책 {} 값 형식이 맞지 않아 해제하지 않음 (yyyy-MM-dd HH:mm 또는 OFF)", RATE_RELAX_UNTIL);
			return null;
		}
	}

	/** 형식이 맞는 해시만 남긴다 (최대 3개). NONE·빈 값이면 빈 집합 */
	static Set<String> parseIpHashes(String raw) {
		if (raw == null || raw.isBlank() || RELAX_NO_IPS.equalsIgnoreCase(raw.strip())) {
			return Set.of();
		}
		return Arrays.stream(raw.split(",")).map(String::strip).filter(s -> IP_HASH.matcher(s).matches())
				.limit(RATE_RELAX_MAX_IPS).collect(Collectors.toUnmodifiableSet());
	}

	/** 관리자 화면이 저장 전에 거절하는 조건: 종료 시각은 OFF이거나 지금 이후 24시간 이내, IP 해시는 NONE이거나 형식이 맞는 최대 3개 */
	@Override
	public List<Rule> rules() {
		return List.of(
				new Rule(Set.of(RATE_RELAX_UNTIL), v -> {
					String s = v.str(RATE_RELAX_UNTIL);
					if (s == null || RELAX_OFF.equalsIgnoreCase(s.strip())) {
						return true;
					}
					LocalDateTime until = parseRelaxUntil(s);
					LocalDateTime now = LocalDateTime.now(clock);
					return until != null && until.isAfter(now) && !until.isAfter(now.plusHours(RATE_RELAX_MAX_HOURS));
				}, "임시 해제 종료 시각은 OFF이거나, 지금 이후 " + RATE_RELAX_MAX_HOURS + "시간 안의 yyyy-MM-dd HH:mm 이어야 합니다."),
				new Rule(Set.of(RATE_RELAX_IP_HASHES), v -> {
					String s = v.str(RATE_RELAX_IP_HASHES);
					if (s == null || RELAX_NO_IPS.equalsIgnoreCase(s.strip())) {
						return true;
					}
					List<String> parts = Arrays.stream(s.split(",")).map(String::strip).toList();
					return parts.size() <= RATE_RELAX_MAX_IPS && parts.stream().allMatch(p -> IP_HASH.matcher(p).matches());
				}, "임시 해제 IP는 NONE이거나 IP 해시 최대 " + RATE_RELAX_MAX_IPS + "개입니다. IP는 \"인증 제한 임시 해제\" 칸에서 넣어 주세요."));
	}

	/** Y/N 정책. 없거나 Y가 아니면 false (켜는 쪽은 명시적으로 Y일 때만) */
	public boolean flag(String key) {
		return (boolean) cached(key, () -> "Y".equalsIgnoreCase(String.valueOf(raw(key)).strip()));
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
