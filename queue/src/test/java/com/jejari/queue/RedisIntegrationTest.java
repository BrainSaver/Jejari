package com.jejari.queue;

import com.jejari.queue.TestSupport.MutableClock;
import com.jejari.queue.TestSupport.RecordingSink;
import com.jejari.queue.port.DevMemberIdentityPort;
import com.jejari.queue.port.DevOnSaleRunProvider;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 진짜 Redis에 붙는 통합 테스트의 공통 틀. Redis는 REDIS_HOST:REDIS_PORT(기본 localhost:6379)에 떠 있어야 하고,
 * 데이터는 15번 DB만 쓴다 (매 테스트 전에 비움). Docker Compose의 redis를 쓰면 된다.
 */
@SpringBootTest(properties = {
        "jejari.dev-mode=true",
        "jejari.queue.scheduler-enabled=false",
        "jejari.queue.policy-cache-ms=0",   // 테스트에서 정책을 바꾸면 바로 보이게 (캐시는 PolicyProviderTest가 따로 확인)
        "spring.data.redis.database=15"
})
@Import(TestSupport.class)
abstract class RedisIntegrationTest {

    @Autowired protected StringRedisTemplate redis;
    @Autowired protected MutableClock clock;
    @Autowired protected RecordingSink sink;
    @Autowired protected DevMemberIdentityPort members;
    @Autowired protected DevOnSaleRunProvider runs;

    protected static final long RUN = 1001;

    @BeforeEach
    void resetState() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
        members.clear();
        runs.clear();
        runs.open(RUN);
        sink.events.clear();
    }
}
