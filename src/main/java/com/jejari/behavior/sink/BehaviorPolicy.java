package com.jejari.behavior.sink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import com.jejari.common.policy.PolicyBounds;
import com.jejari.queue.redis.QueueKeys;

/**
 * 행동 로그 정책값 (app_policy, 원본은 Redis policy:cache). 없거나 범위를 벗어나면 ERD 기본값.
 * 보관 기간은 30~730일 (TTL로 지워지므로 너무 짧게 바뀌면 위험 점수 재료가 사라짐), 묶음 크기는 100~5000.
 */
public class BehaviorPolicy implements PolicyBounds {
	private static final Logger log = LoggerFactory.getLogger(BehaviorPolicy.class);

	static final String RETENTION_DAYS = "behavior.retention_days";
	static final String BATCH_SIZE = "behavior.batch_size";
	private static final Range RETENTION_RANGE = new Range(30, 730);
	private static final Range BATCH_RANGE = new Range(100, 5000);

	private final StringRedisTemplate redis;

	public BehaviorPolicy(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public long retentionDays() {
		return read(RETENTION_DAYS, 180, RETENTION_RANGE.min(), RETENTION_RANGE.max());
	}

	public int batchSize() {
		return (int) read(BATCH_SIZE, 1000, BATCH_RANGE.min(), BATCH_RANGE.max());
	}

	/** 관리자 화면(ADM-08)이 저장 전에 같은 범위로 거절하도록 */
	@Override
	public Map<String, Range> bounds() {
		return Map.of(RETENTION_DAYS, RETENTION_RANGE, BATCH_SIZE, BATCH_RANGE);
	}

	private long read(String key, long def, long min, long max) {
		try {
			Object v = redis.opsForHash().get(QueueKeys.POLICY_CACHE, key);
			if (v == null) {
				return def;
			}
			long n = Long.parseLong(v.toString().strip());
			if (n >= min && n <= max) {
				return n;
			}
			log.warn("정책 {} 값이 허용 범위({}~{}) 밖이라 기본값 {}을 씀", key, min, max, def);
		} catch (NumberFormatException e) {
			log.warn("정책 {} 값이 숫자가 아니라 기본값 {}을 씀", key, def);
		} catch (RuntimeException e) {
			// Redis 장애: 기본값으로 계속 적재 (적재를 멈추는 것보다 낫다)
			log.warn("정책 {}를 읽지 못해 기본값 {}을 씀: {}", key, def, e.toString());
		}
		return def;
	}
}
