package com.jejari.admin;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.jejari.queue.redis.QueueKeys;

/**
 * ADM-03이 쓰는 대기열 숫자. A 김성원 모듈의 Redis 키를 <b>읽기만</b> 한다 ({@link QueueKeys}가 계약).
 *
 * <ul>
 *   <li>{@code queue:{runId}} ZSET 크기 = 지금 줄 서 있는 사람 수</li>
 *   <li>{@code queue:active:{runId}} ZSET 크기 = 입장해 있는 사람 수. 만료분은 입장 스케줄러가 치우므로
 *       치우기 전 몇 초는 조금 크게 보일 수 있다</li>
 *   <li>{@code queue:throughput:{runId}} = 초당 입장 수. 입장 스케줄러가 매번 덮어쓴다</li>
 * </ul>
 *
 * <p>세 키를 한 번의 왕복으로 읽는다. Redis가 죽어 있어도 현황 화면 전체가 죽지 않게 0으로 돌려준다 —
 * 좌석·예약 숫자는 Oracle에서 오므로 그쪽만이라도 보이는 것이 낫다.
 */
@Repository
public class QueueStatsReader {

	private final StringRedisTemplate redis;

	public QueueStatsReader(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public RunDashboard.Queue read(long runId) {
		try {
			List<Object> out = redis.executePipelined(
					(org.springframework.data.redis.core.RedisCallback<Object>) c -> {
						c.zSetCommands().zCard(bytes(QueueKeys.queue(runId)));
						c.zSetCommands().zCard(bytes(QueueKeys.active(runId)));
						c.stringCommands().get(bytes(QueueKeys.throughput(runId)));
						return null;
					});
			return new RunDashboard.Queue(asLong(out, 0), asLong(out, 1), asLong(out, 2));
		} catch (RuntimeException e) {
			return new RunDashboard.Queue(0, 0, 0);
		}
	}

	private static byte[] bytes(String s) {
		return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
	}

	/** 파이프라인 결과는 Long·String·null이 섞여 온다 */
	private static long asLong(List<Object> out, int i) {
		if (out == null || i >= out.size()) {
			return 0;
		}
		Object v = out.get(i);
		if (v instanceof Number n) {
			return n.longValue();
		}
		if (v instanceof String s && !s.isBlank()) {
			try {
				return Long.parseLong(s.trim());
			} catch (NumberFormatException e) {
				return 0;
			}
		}
		return 0;
	}
}
