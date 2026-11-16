package com.jejari.seat;

import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 좌석·한도 Lua 스크립트 (src/main/resources/lua/seat/, 설명은 redis/README.md · concurrency.md §14.2).
 * 결제·만료·취소표(§4~§9)가 쓰는 extend·sold·rehold·reoffer도 여기서 꺼내 씁니다.
 */
@Component
public class SeatScripts {

	@SuppressWarnings("rawtypes")
	final RedisScript<List> limitIncr = script("limit_incr", List.class);
	final RedisScript<Long> hold = script("hold", Long.class);
	final RedisScript<Long> release = script("release", Long.class);
	final RedisScript<Long> limitDecr = script("limit_decr", Long.class);
	final RedisScript<Long> extend = script("extend", Long.class);
	final RedisScript<Long> sold = script("sold", Long.class);
	final RedisScript<Long> rehold = script("rehold", Long.class);
	final RedisScript<Long> reoffer = script("reoffer", Long.class);

	private static <T> RedisScript<T> script(String name, Class<T> type) {
		DefaultRedisScript<T> s = new DefaultRedisScript<>();
		s.setLocation(new ClassPathResource("lua/seat/" + name + ".lua"));
		s.setResultType(type);
		return s;
	}
}
