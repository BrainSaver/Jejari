package com.jejari.queue.domain;

import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;
import com.jejari.queue.port.RiskScoreFallbackPort;
import com.jejari.queue.redis.QueueKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * QUE-01 1단계. 회원의 CI·결제 수단·기기 해시로 risk:* 캐시를 읽어 하나의 점수로 합친다 (RISK-03).
 * 계산은 D의 배치가 하고, 여기서는 읽기만 한다. 안전장치 두 가지 (기능 설계: 점수는 모르면 막는 쪽으로):
 * <ul>
 * <li>동기화 표시 키(risk:meta:synced_at)가 없으면 캐시가 비었을 수 있으니(Redis 재시작·유실) 0점으로 믿지 않고
 *     Oracle에서 직접 조회한다. 조회도 실패하면 최대 점수(100)로 처리한다.</li>
 * <li>값이 0~100 숫자 문자열이 아니면 오염으로 보고 100점으로 처리하고 경고를 남긴다.</li>
 * </ul>
 */
public class RiskPenaltyService {
	private static final Logger log = LoggerFactory.getLogger(RiskPenaltyService.class);

	/** member.status = RESTRICTED면 최대 패널티 (기능설계서 2장 [가정]). 점수 범위가 0~100이다. */
	static final double RESTRICTED_SCORE = 100.0;
	static final double MAX_SCORE = 100.0;

	/** 0~100의 십진수 문자열만 허용한다. "NaN", "1e2", "80d", "0x10", 음수 같은 Double.parseDouble 통과 값을 막는다. */
	private static final Pattern SCORE_FORMAT = Pattern.compile("(?:\\d{1,3})(?:\\.\\d{1,6})?");
	private static final long WARN_INTERVAL_MS = 10_000;
	private static final AtomicLong lastWarnAt = new AtomicLong();

	private final StringRedisTemplate redis;
	private final PolicyProvider policy;
	private final RiskScoreFallbackPort fallback;

	public RiskPenaltyService(StringRedisTemplate redis, PolicyProvider policy, RiskScoreFallbackPort fallback) {
		this.redis = redis;
		this.policy = policy;
		this.fallback = fallback;
	}

	public double score(MemberIdentity m) {
		if ("RESTRICTED".equals(m.status())) {
			return RESTRICTED_SCORE;
		}
		List<String> keys = new ArrayList<>();
		keys.add(QueueKeys.risk("CI", m.ciHash()));
		m.paymentHashes().forEach(h -> keys.add(QueueKeys.risk("PAYMENT", h)));
		m.deviceHashes().forEach(h -> keys.add(QueueKeys.risk("DEVICE", h)));
		keys.add(QueueKeys.RISK_SYNCED_AT); // 같은 왕복에 함께 읽는다
		List<String> values = redis.opsForValue().multiGet(keys);

		String rule = policy.getString(PolicyProvider.MEMBER_PENALTY_RULE);
		if (!"MAX".equals(rule)) {
			log.warn("지원하지 않는 risk.member_penalty_rule={} → MAX로 처리", rule);
		}
		if (values == null || values.get(values.size() - 1) == null) {
			return lookupFromSource(m);
		}
		return maxOf(values.subList(0, values.size() - 1));
	}

	/** 캐시가 비었을 때: Oracle 직접 조회. 실패하면 최대 점수. */
	private double lookupFromSource(MemberIdentity m) {
		try {
			double s = fallback.maxScore(m);
			if (Double.isNaN(s) || s < 0 || s > MAX_SCORE) {
				throttledWarn("위험 점수 원본 조회값이 범위를 벗어남 member={} value={} → {}점", m.memberId(), s, MAX_SCORE);
				return MAX_SCORE;
			}
			return s;
		} catch (RuntimeException e) {
			throttledWarn("risk:meta:synced_at 없음 + 위험 점수 원본 조회 실패 member={} → {}점으로 처리: {}", m.memberId(),
					MAX_SCORE, e.toString());
			return MAX_SCORE;
		}
	}

	/** 값 중 가장 큰 점수. 없는 값(null)은 무시, 형식이 잘못된 값은 100점. */
	public static double maxOf(List<String> values) {
		double max = 0;
		if (values != null) {
			for (String v : values) {
				if (v != null) {
					max = Math.max(max, parse(v));
				}
			}
		}
		return max;
	}

	static double parse(String v) {
		if (SCORE_FORMAT.matcher(v).matches()) {
			double d = Double.parseDouble(v);
			if (d <= MAX_SCORE) {
				return d;
			}
		}
		throttledWarn("risk:* 값 형식 오류 value='{}' → {}점으로 처리", v.length() > 40 ? v.substring(0, 40) + "…" : v, MAX_SCORE);
		return MAX_SCORE;
	}

	/** 폭주 중에 같은 경고가 초당 수천 줄 나가지 않도록 10초에 한 번만 남긴다. */
	private static void throttledWarn(String msg, Object... args) {
		long now = System.currentTimeMillis();
		long last = lastWarnAt.get();
		if (now - last >= WARN_INTERVAL_MS && lastWarnAt.compareAndSet(last, now)) {
			log.warn(msg, args);
		}
	}

	/** penalty_ms = 점수 × risk.penalty_ms_per_point (예: 80점 → 80초) */
	public long penaltyMs(double score) {
		return Math.round(score * policy.getLong(PolicyProvider.PENALTY_MS_PER_POINT));
	}
}
