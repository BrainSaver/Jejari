package com.jejari.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.jejari.queue.domain.PolicyProvider;
import org.junit.jupiter.api.Test;

class PolicyProviderTest extends RedisIntegrationTest {

    @Test
    void 캐시_시간_안에는_Redis를_다시_읽지_않고_지나면_새_값을_본다() throws Exception {
        PolicyProvider cached = new PolicyProvider(redis, 300);
        redis.opsForHash().put("policy:cache", "queue.admit_per_sec", "50");
        assertThat(cached.getLong("queue.admit_per_sec")).isEqualTo(50);

        redis.opsForHash().put("policy:cache", "queue.admit_per_sec", "70");
        assertThat(cached.getLong("queue.admit_per_sec")).isEqualTo(50);   // 아직 캐시

        Thread.sleep(400);
        assertThat(cached.getLong("queue.admit_per_sec")).isEqualTo(70);   // 만료 후 새 값
    }

    @Test
    void 캐시를_끄면_바로_반영된다() {
        PolicyProvider uncached = new PolicyProvider(redis, 0);
        redis.opsForHash().put("policy:cache", "queue.pass_ttl_sec", "10");
        assertThat(uncached.getLong("queue.pass_ttl_sec")).isEqualTo(10);
        redis.opsForHash().put("policy:cache", "queue.pass_ttl_sec", "20");
        assertThat(uncached.getLong("queue.pass_ttl_sec")).isEqualTo(20);
    }

    @Test
    void 정책에_없으면_ERD_예시값을_쓴다() {
        PolicyProvider p = new PolicyProvider(redis, 1000);
        assertThat(p.getLong("queue.admit_per_sec")).isEqualTo(200);
        assertThat(p.getLong("queue.pass_ttl_sec")).isEqualTo(300);
        assertThat(p.getString("risk.member_penalty_rule")).isEqualTo("MAX");
    }
}
