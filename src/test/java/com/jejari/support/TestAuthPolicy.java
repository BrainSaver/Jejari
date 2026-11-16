package com.jejari.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.jejari.member.AuthPolicy;

/** 단위 테스트용 인증 정책: 정책 캐시(Redis)가 비어 있어 ERD 기본값을 쓰는 상태. */
public final class TestAuthPolicy {

	private TestAuthPolicy() {
	}

	@SuppressWarnings("unchecked")
	public static AuthPolicy defaults() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		HashOperations<String, Object, Object> hash = mock(HashOperations.class);
		when(redis.opsForHash()).thenReturn(hash);
		when(hash.get(any(), any())).thenReturn(null);
		return new AuthPolicy(redis);
	}
}
