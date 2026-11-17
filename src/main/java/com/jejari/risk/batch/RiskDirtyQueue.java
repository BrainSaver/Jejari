package com.jejari.risk.batch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * "다시 계산할 대상" 목록 (Redis SET). 새 신호가 생긴 대상만 1분 배치가 다시 계산하게 한다.
 *
 * <pre>
 * 표시: 행동 로그 적재(BehaviorSink) · 예약 확정·취소 이벤트(RiskSignalListener) · 감쇠 갱신 → SADD risk:dirty
 * 배치: risk:dirty:work가 없으면 RENAMENX risk:dirty → risk:dirty:work (원자적으로 통째로 넘겨받음)
 *       → work에서 꺼내 계산 → 성공한 대상만 SREM
 * </pre>
 *
 * <ul>
 *   <li><b>잃지 않음</b>: 계산 도중 앱이 죽으면 work가 남고, 다음 배치가 그것부터 다시 한다.</li>
 *   <li><b>놓치지 않음</b>: 계산하는 동안 들어온 새 표시는 새 risk:dirty에 쌓이므로 SREM에 지워지지 않는다
 *       (같은 집합에서 꺼내고 지우면, 계산 중 다시 들어온 표시가 SADD 무시 → SREM으로 사라진다).</li>
 *   <li>SADD는 몇 번 해도 같으므로 Kafka 재전달로 같은 표시가 두 번 와도 문제없다 (inbox 불필요).</li>
 * </ul>
 */
@Component
public class RiskDirtyQueue {
	private static final Logger log = LoggerFactory.getLogger(RiskDirtyQueue.class);

	/** 새로 표시된 대상 */
	public static final String DIRTY_KEY = "risk:dirty";
	/** 배치가 넘겨받아 처리 중인 대상 */
	public static final String WORK_KEY = "risk:dirty:work";

	private final StringRedisTemplate redis;

	public RiskDirtyQueue(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public void mark(RiskSubject s) {
		redis.opsForSet().add(DIRTY_KEY, s.member());
	}

	/** null은 건너뛴다. 실패하면 예외 (호출한 쪽이 재시도: 행동 로그는 같은 묶음 재처리, 이벤트는 Kafka 재전달) */
	public void markAll(Collection<RiskSubject> subjects) {
		String[] members = subjects.stream().filter(s -> s != null).map(RiskSubject::member).distinct()
				.toArray(String[]::new);
		if (members.length > 0) {
			redis.opsForSet().add(DIRTY_KEY, members);
		}
	}

	/**
	 * 처리할 대상을 최대 {@code max}개 꺼낸다 (work에서 지우지는 않음 → {@link #done}).
	 * work가 비었으면 그때 risk:dirty를 통째로 넘겨받는다.
	 */
	public List<RiskSubject> claim(int max) {
		if (!Boolean.TRUE.equals(redis.hasKey(WORK_KEY)) && Boolean.TRUE.equals(redis.hasKey(DIRTY_KEY))) {
			// 배치는 락 안에서 하나만 돈다. 그래도 RENAMENX라서 work가 이미 있으면 덮어쓰지 않는다
			redis.renameIfAbsent(DIRTY_KEY, WORK_KEY);
		}
		Set<String> raw = new LinkedHashSet<>();
		try (Cursor<String> c = redis.opsForSet().scan(WORK_KEY, ScanOptions.scanOptions().count(max).build())) {
			while (c.hasNext() && raw.size() < max) {
				raw.add(c.next());
			}
		}
		List<RiskSubject> out = new ArrayList<>(raw.size());
		List<String> broken = new ArrayList<>();
		for (String m : raw) {
			RiskSubject s = RiskSubject.parse(m);
			if (s == null) {
				broken.add(m);
			} else {
				out.add(s);
			}
		}
		if (!broken.isEmpty()) {
			// 값은 남기지 않는다. 계속 남아 있으면 매번 같은 원소를 다시 꺼내므로 지운다
			log.warn("위험 점수 대상 목록에 형식이 틀린 원소 {}개를 지움", broken.size());
			redis.opsForSet().remove(WORK_KEY, broken.toArray());
		}
		return out;
	}

	/** 계산(저장)을 마친 대상 */
	public void done(RiskSubject s) {
		redis.opsForSet().remove(WORK_KEY, s.member());
	}

	/** 계산에 실패한 대상: work에서 빼고 다음 배치로 미룬다 (한 대상 때문에 이번 배치가 같은 원소만 붙잡지 않게) */
	public void retryLater(RiskSubject s) {
		redis.opsForSet().add(DIRTY_KEY, s.member());
		redis.opsForSet().remove(WORK_KEY, s.member());
	}

	public long pending() {
		Long a = redis.opsForSet().size(DIRTY_KEY);
		Long b = redis.opsForSet().size(WORK_KEY);
		return (a == null ? 0 : a) + (b == null ? 0 : b);
	}
}
