package com.jejari.queue.domain;

import com.jejari.queue.redis.QueueKeys;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * app_policy 값을 읽는다. 원본은 Redis 해시 policy:cache (ADM-08이 갱신).
 * 값이 없으면 ERD 예시값(v10)을 쓴다. TTL·임계값을 코드에 박지 않기 위한 장치다.
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

    private final StringRedisTemplate redis;

    public PolicyProvider(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String getString(String key) {
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
