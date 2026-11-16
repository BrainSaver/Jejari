package com.jejari.member;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 인증 남용 방지 카운터 (Redis). 키 이름은 ERD redis_keys와 맞춘다.
 *
 * <ul>
 *   <li>{@code rate:login:{ipHash}} — IP당 분당 로그인 시도 (ERD에 있음)</li>
 *   <li>{@code rate:signup:{ipHash}} — IP당 시간당 본인인증·가입 시도 [ERD 추가 요청]</li>
 *   <li>{@code login:fail:{emailHash}} — 이메일별 연속 실패 수. 기준에 닿으면 잠금 시간만큼 유지 [ERD 추가 요청].
 *       <b>없는 이메일에도 똑같이 센다</b>: 잠금 응답으로 계정 존재를 알 수 없게.</li>
 *   <li>{@code verify:{tokenHash}} — 본인인증 결과 (1회용, {@code VerificationTokens}) [ERD 추가 요청]</li>
 * </ul>
 * Redis가 안 되면 예외 → 503 (막는 쪽). 제한 없이 통과시키지 않는다.
 */
@Component
@MemberAuthEnabled
public class AuthLimiter {

	/** INCR 후 처음이거나 TTL이 빠진 키면 만료를 건다 (만료 없는 카운터가 영구 차단이 되지 않게) */
	private static final RedisScript<Long> INCR_WINDOW = new DefaultRedisScript<>("""
			local c = redis.call('INCR', KEYS[1])
			if c == 1 or redis.call('PTTL', KEYS[1]) < 0 then
			  redis.call('PEXPIRE', KEYS[1], ARGV[1])
			end
			return c""", Long.class);

	/** 실패 1회 기록. 기준에 닿으면 그때부터 잠금 시간만큼 유지 (마지막 실패 기준으로 연장) */
	private static final RedisScript<Long> FAIL = new DefaultRedisScript<>("""
			local c = redis.call('INCR', KEYS[1])
			if c >= tonumber(ARGV[2]) or c == 1 or redis.call('PTTL', KEYS[1]) < 0 then
			  redis.call('PEXPIRE', KEYS[1], ARGV[1])
			end
			return c""", Long.class);

	private final StringRedisTemplate redis;

	public AuthLimiter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** @return 이번 창에서 몇 번째 시도인지 */
	public long hit(String key, Duration window) {
		Long c = redis.execute(INCR_WINDOW, List.of(key), Long.toString(window.toMillis()));
		if (c == null) {
			throw new IllegalStateException("Redis 응답 없음");
		}
		return c;
	}

	public long failures(String emailHash) {
		String v = redis.opsForValue().get(failKey(emailHash));
		return v == null ? 0 : Long.parseLong(v);
	}

	/** @return 기록 후 연속 실패 수 */
	public long recordFailure(String emailHash, Duration lockFor, long threshold) {
		Long c = redis.execute(FAIL, List.of(failKey(emailHash)), Long.toString(lockFor.toMillis()),
				Long.toString(threshold));
		if (c == null) {
			throw new IllegalStateException("Redis 응답 없음");
		}
		return c;
	}

	public void clearFailures(String emailHash) {
		redis.delete(failKey(emailHash));
	}

	public static String loginRateKey(String ipHash) {
		return "rate:login:" + ipHash;
	}

	public static String signupRateKey(String ipHash) {
		return "rate:signup:" + ipHash;
	}

	static String failKey(String emailHash) {
		return "login:fail:" + emailHash;
	}
}
