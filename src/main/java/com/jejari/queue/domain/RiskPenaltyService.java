package com.jejari.queue.domain;

import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;
import com.jejari.queue.redis.QueueKeys;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * QUE-01 1단계. 회원의 CI·결제 수단·기기 해시로 risk:* 캐시를 읽어 하나의 점수로 합친다 (RISK-03).
 * 캐시에 없는 대상은 0점이다. 계산은 D의 배치가 하고, 여기서는 읽기만 한다.
 */
public class RiskPenaltyService {
	private static final Logger log = LoggerFactory.getLogger(RiskPenaltyService.class);

	/** member.status = RESTRICTED면 최대 패널티 (기능설계서 2장 [가정]). 점수 범위가 0~100이다. */
	static final double RESTRICTED_SCORE = 100.0;

	private final StringRedisTemplate redis;
	private final PolicyProvider policy;

	public RiskPenaltyService(StringRedisTemplate redis, PolicyProvider policy) {
		this.redis = redis;
		this.policy = policy;
	}

	public double score(MemberIdentity m) {
		if ("RESTRICTED".equals(m.status())) {
			return RESTRICTED_SCORE;
		}
		List<String> keys = new ArrayList<>();
		keys.add(QueueKeys.risk("CI", m.ciHash()));
		m.paymentHashes().forEach(h -> keys.add(QueueKeys.risk("PAYMENT", h)));
		m.deviceHashes().forEach(h -> keys.add(QueueKeys.risk("DEVICE", h)));
		List<String> values = redis.opsForValue().multiGet(keys);

		String rule = policy.getString(PolicyProvider.MEMBER_PENALTY_RULE);
		if (!"MAX".equals(rule)) {
			log.warn("지원하지 않는 risk.member_penalty_rule={} → MAX로 처리", rule);
		}
		double max = 0;
		if (values != null) {
			for (String v : values) {
				if (v != null) {
					max = Math.max(max, Double.parseDouble(v));
				}
			}
		}
		return max;
	}

	/** penalty_ms = 점수 × risk.penalty_ms_per_point (예: 80점 → 80초) */
	public long penaltyMs(double score) {
		return Math.round(score * policy.getLong(PolicyProvider.PENALTY_MS_PER_POINT));
	}
}
