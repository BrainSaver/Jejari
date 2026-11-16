package com.jejari.risk.cache;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.regex.Pattern;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.jejari.queue.redis.QueueKeys;
import com.jejari.risk.SubjectType;

/**
 * RISK-03 위험 점수 캐시 쓰기. 키 {@code risk:{CI|PAYMENT|DEVICE}:{hash}}, 값은 최종 점수 숫자 문자열 (예: {@code "80.50"}).
 *
 * <p>읽는 쪽은 대기열(A, {@code RiskPenaltyService})이고 {@code Double.parseDouble}로 읽으므로 값에 숫자 말고는 넣지 않는다.
 * 키는 A의 {@link QueueKeys#risk}로 만들어 형식이 어긋나지 않게 한다.
 *
 * <p>보안 원칙 (fail-closed): 대기열은 키가 없으면 0점(패널티 없음)으로 보므로, 키가 시간이 지나 저절로 사라지면
 * 위험 사용자가 "쉬었다 오면" 풀린다. 그래서
 * <ul>
 *   <li>TTL을 두지 않는다. 점수가 0이 되었을 때만 지운다.</li>
 *   <li>점수는 배치(엔진의 감쇠 계산)와 관리자 판정만 내린다.</li>
 *   <li>원본은 Oracle {@code risk_score}. Redis가 날아가도 {@link RiskCacheResync}가 다시 채운다.</li>
 * </ul>
 */
@Component
public class RiskScoreCache {

	/** 재동기화가 한 번이라도 끝났다는 표시. 값은 마지막 완료 시각(ISO-8601). risk:* 정리 대상과 겹치지 않게 접두어를 다르게 둔다 */
	public static final String SYNCED_AT_KEY = "riskcache:synced_at";

	private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
	private static final BigDecimal MAX = BigDecimal.valueOf(100);

	private final StringRedisTemplate redis;
	private final Clock clock;

	public RiskScoreCache(StringRedisTemplate redis, Clock clock) {
		this.redis = redis;
		this.clock = clock;
	}

	/** 점수를 기록한다. 0점이면 키를 지운다 (없음 = 0점이라 같은 의미이고, 키 수를 줄인다). */
	public void put(SubjectType type, String hash, BigDecimal finalScore) {
		String key = key(type, hash);
		String value = format(finalScore);
		if (value == null) {
			redis.delete(key);
		} else {
			redis.opsForValue().set(key, value);
		}
	}

	public static String key(SubjectType type, String hash) {
		if (type == null) {
			throw new IllegalArgumentException("subjectType이 없습니다");
		}
		if (hash == null || !HASH.matcher(hash).matches()) {
			// 원문 식별정보가 키에 들어가는 실수를 막는다 (메시지에 값을 싣지 않음)
			throw new IllegalArgumentException("hash는 소문자 16진수 64자(HMAC)여야 합니다");
		}
		return QueueKeys.risk(type.name(), hash);
	}

	/**
	 * Redis에 넣을 값. 0~100만 허용, 소수 둘째 자리(risk_score.final_score NUMBER(5,2)), 지수 표기 없음.
	 * 0점이면 null (키 삭제).
	 */
	static String format(BigDecimal score) {
		if (score == null || score.signum() < 0 || score.compareTo(MAX) > 0) {
			throw new IllegalArgumentException("점수는 0~100이어야 합니다");
		}
		BigDecimal s = score.setScale(2, RoundingMode.HALF_UP);
		return s.signum() == 0 ? null : s.toPlainString();
	}

	void markSynced() {
		redis.opsForValue().set(SYNCED_AT_KEY, clock.instant().toString());
	}
}
