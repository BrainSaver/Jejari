package com.jejari.queue.domain;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.jejari.common.config.AppPolicy;
import com.jejari.common.error.RetryAfterException;
import com.jejari.queue.QueueErrorCode;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 좌석 조회(배치도)·선점 속도 제한. 봇이 빈자리를 찾으려고 새로고침·선점을 계속 두드리는 것을 늦춘다.
 *
 * <ul>
 *   <li><b>1단계 (유하게)</b>: (회원, 운행)마다 토큰 버킷. 처음 {@code burst}번(기본 10)은 그냥 지나가고, 그 뒤로는
 *       초당 {@code rate_per_sec}번(기본 1)씩 채워진다. 호차를 바꾸고 새로고침을 몇 번 눌러 보는 사람은 거의 안 걸린다.</li>
 *   <li><b>2단계 (쉬게 하기)</b>: 물이 바닥난 채로 {@code abuse_window_sec}(60초) 안에 {@code abuse_threshold}(20)번 넘게
 *       거절당하면 {@code cooldown_sec}(60초) 동안 막는다. 한도에 붙어서 계속 두드리는 계정만 걸린다.</li>
 *   <li>조회와 선점은 <b>따로 센다</b>. 배치도를 안 보고 선점만 반복해도 같은 한도가 걸린다.</li>
 *   <li>기준은 IP가 아니라 (회원, 운행): 공유기·회사망의 정상 사용자가 같이 막히지 않게. 비회원(게스트)도 member_id가 있어 똑같이 적용된다.</li>
 *   <li><b>Redis가 안 되면 통과</b>(fail-open): 이 제한이 정상 예매를 막아서는 안 된다. 대신 경고 로그와 지표를 남긴다.</li>
 * </ul>
 * 값은 app_policy {@code seatmap.rate_*}·{@code seatmap.abuse_*}·{@code seatmap.cooldown_sec}(ERD 패치 전에는 코드의 기본값).
 * 한계: 계정을 여러 개 쓰는 봇은 못 막는다 (그건 본인인증·위험 점수의 몫). 새로고침 제한은 속도를 늦추는 장치다.
 */
@Component
public class SeatRateLimiter {
	private static final Logger log = LoggerFactory.getLogger(SeatRateLimiter.class);

	/** 조회와 선점은 키가 다르다 */
	public enum Kind {
		MAP("seatmap"), HOLD("seathold");

		final String prefix;

		Kind(String prefix) {
			this.prefix = prefix;
		}
	}

	@SuppressWarnings("rawtypes")
	private static final DefaultRedisScript<List> SCRIPT = script();

	@SuppressWarnings("rawtypes")
	private static DefaultRedisScript<List> script() {
		DefaultRedisScript<List> s = new DefaultRedisScript<>();
		s.setLocation(new ClassPathResource("lua/seat/seat_rate.lua"));
		s.setResultType(List.class);
		return s;
	}

	private final StringRedisTemplate redis;
	private final AppPolicy policy;
	private final Clock clock;
	private final MeterRegistry meters;

	public SeatRateLimiter(StringRedisTemplate redis, AppPolicy policy, Clock clock, MeterRegistry meters) {
		this.redis = redis;
		this.policy = policy;
		this.clock = clock;
		this.meters = meters;
	}

	public static String key(Kind kind, long runId, long memberId) {
		return "rate:" + kind.prefix + ":" + runId + ":" + memberId;
	}

	/**
	 * 한 번 쓴다. 한도를 넘으면 429 SEATMAP_RATE_LIMITED + Retry-After.
	 * 정책 값이나 Redis에 문제가 있으면 통과시킨다.
	 */
	public void check(Kind kind, long runId, long memberId) {
		long retryMs;
		boolean locked;
		try {
			long burst = policy.getLong(AppPolicy.SEATMAP_RATE_BURST);
			long perSec = policy.getLong(AppPolicy.SEATMAP_RATE_PER_SEC);
			long threshold = policy.getLong(AppPolicy.SEATMAP_ABUSE_THRESHOLD);
			long windowMs = policy.getLong(AppPolicy.SEATMAP_ABUSE_WINDOW_SEC) * 1000;
			long cooldownMs = policy.getLong(AppPolicy.SEATMAP_COOLDOWN_SEC) * 1000;
			List<?> r = redis.execute(SCRIPT, List.of(key(kind, runId, memberId)), Long.toString(clock.millis()),
					Long.toString(burst), Long.toString(perSec), Long.toString(threshold), Long.toString(windowMs),
					Long.toString(cooldownMs));
			if (r == null || r.size() < 3 || ((Number) r.get(0)).longValue() == 1) {
				return;
			}
			retryMs = ((Number) r.get(1)).longValue();
			locked = ((Number) r.get(2)).longValue() == 1;
		} catch (RuntimeException e) {
			meters.counter("jejari.seat.rate_limit_error").increment();
			log.warn("좌석 속도 제한 확인 실패 → 통과시킴: {}", e.toString());
			return;
		}
		meters.counter("jejari.seat.rate_limited", "kind", kind.name(), "locked", String.valueOf(locked)).increment();
		throw new RetryAfterException(QueueErrorCode.SEATMAP_RATE_LIMITED, (retryMs + 999) / 1000);
	}
}
