package com.jejari.admin.traffic;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.jejari.queue.redis.QueueKeys;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 대기열 인원을 Prometheus 지표로 내보낸다 (A-10 트래픽 관제의 "대기열" 그래프).
 *
 * <ul>
 *   <li>{@code jejari_queue_waiting} = 판매 중인 모든 운행의 {@code queue:{runId}} ZSET 크기 합 (줄 선 사람)</li>
 *   <li>{@code jejari_queue_active} = {@code queue:active:{runId}} 크기 합 (입장해서 좌석을 고르는 사람)</li>
 * </ul>
 *
 * <p>대기열 모듈(김성원)의 Redis 키를 <b>읽기만</b> 한다 ({@link QueueKeys}가 계약). 운행 목록은 {@code queue:runs}.
 * Prometheus가 5초마다 긁을 때 읽고, 두 지표가 같은 값을 쓰도록 1초 동안 재사용한다. Redis가 죽어 있으면 값 없음(NaN).
 * 앱이 여러 대면 같은 값을 각자 내보내므로 화면 쿼리는 {@code max}로 묶는다.
 */
@Component
public class QueueMetrics {

	private final StringRedisTemplate redis;
	private final Clock clock;
	private volatile long readAt;
	private volatile double[] last = { Double.NaN, Double.NaN };

	public QueueMetrics(StringRedisTemplate redis, Clock clock, MeterRegistry meters) {
		this.redis = redis;
		this.clock = clock;
		Gauge.builder("jejari.queue.waiting", this, m -> m.snapshot()[0])
				.description("대기열에 줄 선 사람 (판매 중인 운행 합)").register(meters);
		Gauge.builder("jejari.queue.active", this, m -> m.snapshot()[1])
				.description("대기열을 통과해 입장한 사람 (판매 중인 운행 합)").register(meters);
	}

	synchronized double[] snapshot() {
		long now = clock.millis();
		if (now - readAt < 1000) {
			return last;
		}
		readAt = now;
		try {
			Set<String> runs = redis.opsForSet().members(QueueKeys.RUNS);
			if (runs == null || runs.isEmpty()) {
				last = new double[] { 0, 0 };
				return last;
			}
			List<Long> ids = new ArrayList<>();
			for (String r : runs) {
				try {
					ids.add(Long.parseLong(r));
				} catch (NumberFormatException ignored) {
					// 다른 모양의 값은 무시
				}
			}
			List<Object> out = redis.executePipelined((RedisCallback<Object>) (RedisConnection c) -> {
				for (long id : ids) {
					c.zSetCommands().zCard(QueueKeys.queue(id).getBytes(StandardCharsets.UTF_8));
					c.zSetCommands().zCard(QueueKeys.active(id).getBytes(StandardCharsets.UTF_8));
				}
				return null;
			});
			double w = 0, a = 0;
			for (int i = 0; i < out.size(); i++) {
				if (out.get(i) instanceof Number n) {
					if (i % 2 == 0) {
						w += n.doubleValue();
					} else {
						a += n.doubleValue();
					}
				}
			}
			last = new double[] { w, a };
		} catch (RuntimeException e) {
			last = new double[] { Double.NaN, Double.NaN };
		}
		return last;
	}
}
