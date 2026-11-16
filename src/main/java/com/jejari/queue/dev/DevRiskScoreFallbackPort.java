package com.jejari.queue.dev;

import com.jejari.queue.domain.RiskPenaltyService;
import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;
import com.jejari.queue.port.RiskScoreFallbackPort;
import com.jejari.queue.redis.QueueKeys;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 개발용: Oracle 대신 Redis의 risk:* 키를 직접 읽습니다 (개발 모드에는 Oracle 점수 원본이 없음). */
public class DevRiskScoreFallbackPort implements RiskScoreFallbackPort {
	private final StringRedisTemplate redis;

	public DevRiskScoreFallbackPort(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public double maxScore(MemberIdentity m) {
		List<String> keys = new ArrayList<>();
		keys.add(QueueKeys.risk("CI", m.ciHash()));
		m.paymentHashes().forEach(h -> keys.add(QueueKeys.risk("PAYMENT", h)));
		m.deviceHashes().forEach(h -> keys.add(QueueKeys.risk("DEVICE", h)));
		return RiskPenaltyService.maxOf(redis.opsForValue().multiGet(keys));
	}
}
