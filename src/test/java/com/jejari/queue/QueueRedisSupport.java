package com.jejari.queue;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.jejari.queue.QueueTestBeans.RecordingSink;
import com.jejari.queue.dev.DevMemberIdentityPort;
import com.jejari.queue.dev.DevOnSaleRunProvider;
import com.jejari.support.MutableClock;

/** 대기열 통합 테스트의 공통 틀: 매 테스트 전에 Redis(15번 DB)와 가짜 회원·운행·로그를 비웁니다. */
@QueueInfraTest
abstract class QueueRedisSupport {

	protected static final long RUN = 1001;

	@Autowired
	protected StringRedisTemplate redis;
	@Autowired
	protected MutableClock clock;
	@Autowired
	protected RecordingSink sink;
	@Autowired
	protected DevMemberIdentityPort members;
	@Autowired
	protected DevOnSaleRunProvider runs;

	@BeforeEach
	void resetState() {
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		members.clear();
		runs.clear();
		runs.open(RUN);
		sink.events.clear();
	}
}
