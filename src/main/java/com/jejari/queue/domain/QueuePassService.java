package com.jejari.queue.domain;

import com.jejari.common.error.BusinessException;
import com.jejari.queue.QueueErrorCode;
import com.jejari.queue.redis.QueueKeys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * QUE-04 입장 토큰 검증. 좌석 API 앞단(필터)과 POST /reservations(B)가 함께 쓴다.
 * 토큰은 (운행, 회원) 쌍마다 하나라서, 남의 토큰이나 다른 운행의 토큰은 조회 단계에서 걸러진다.
 */
public class QueuePassService {

	private final StringRedisTemplate redis;

	public QueuePassService(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** 유효하면 그대로 반환, 아니면 BusinessException(QUEUE_TOKEN_REQUIRED / QUEUE_TOKEN_INVALID) */
	public void verify(long runId, long memberId, String token) {
		if (token == null || token.isBlank()) {
			throw new BusinessException(QueueErrorCode.QUEUE_TOKEN_REQUIRED);
		}
		String stored = redis.opsForValue().get(QueueKeys.pass(runId, memberId));
		if (stored == null || !MessageDigest.isEqual(
				stored.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
			throw new BusinessException(QueueErrorCode.QUEUE_TOKEN_INVALID);
		}
	}
}
