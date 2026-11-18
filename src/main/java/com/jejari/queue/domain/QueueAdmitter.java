package com.jejari.queue.domain;

import static com.jejari.queue.port.QueueLogSink.QueueLogEvent.Type;

import com.jejari.queue.port.QueueLogSink;
import com.jejari.queue.port.QueueLogSink.QueueLogEvent;
import com.jejari.queue.redis.QueueKeys;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * QUE-03 입장 토큰 발급. 한 운행의 맨 앞 queue.admit_per_sec명을 입장시킨다.
 * 1초마다 호출하는 것은 QueueAdmitScheduler.
 *
 * <p>한 운행의 처리는 Redis 왕복 1번(admit.lua)이다. 처리할 운행은 활성 운행 목록(queue:runs)에서 한 번에 읽어,
 * 줄 선 사람도 입장 중인 사람도 없는 운행은 아예 건드리지 않는다. 앱이 여러 대여도 입장 속도가 정책값이 되도록
 * 스케줄러 경로는 운행별 "이번 1초에 입장시킨 수"를 Redis에서 함께 세어 상한을 건다 (멀티 인스턴스 규칙 3).
 */
public class QueueAdmitter {
	private static final Logger log = LoggerFactory.getLogger(QueueAdmitter.class);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final StringRedisTemplate redis;
	private final RedisScript<List> admitScript;
	private final RedisScript<Long> reconcileScript;
	private final PolicyProvider policy;
	private final QueueLogSink logSink;
	private final Clock clock;

	public QueueAdmitter(StringRedisTemplate redis, RedisScript<List> admitScript, RedisScript<Long> reconcileScript,
						 PolicyProvider policy, QueueLogSink logSink, Clock clock) {
		this.redis = redis;
		this.admitScript = admitScript;
		this.reconcileScript = reconcileScript;
		this.policy = policy;
		this.logSink = logSink;
		this.clock = clock;
	}

	/**
	 * 스케줄러가 1초마다 부른다: 활성 운행만 처리하고 입장시킨 총 인원을 돌려준다.
	 * 한 운행이 실패해도 다른 운행은 계속한다. 활성 운행이 없으면 Redis 명령은 SMEMBERS 하나뿐이다.
	 */
	public int admitActiveRuns() {
		Set<String> runs = redis.opsForSet().members(QueueKeys.RUNS);
		if (runs == null || runs.isEmpty()) {
			return 0;
		}
		long perSec = policy.getLong(PolicyProvider.ADMIT_PER_SEC);
		long passTtlMs = policy.getLong(PolicyProvider.PASS_TTL_SEC) * 1000;
		int total = 0;
		for (String r : runs) {
			try {
				total += admit(Long.parseLong(r), perSec, passTtlMs, true);
			} catch (RuntimeException e) {
				log.error("입장 처리 실패 runId={}", r, e);
			}
		}
		return total;
	}

	/** 입장시킨 인원 수. 이번 1초의 상한은 걸지 않고 정책값만큼 입장시킨다 (직접 부르는 테스트·수동 처리용). */
	public int admit(long runId) {
		return admit(runId, policy.getLong(PolicyProvider.ADMIT_PER_SEC),
				policy.getLong(PolicyProvider.PASS_TTL_SEC) * 1000, false);
	}

	/** 한 운행 처리. capPerSecond가 true면 이번 1초에 이미 입장시킨 수(앱 전체 합)를 빼고 남은 만큼만 입장시킨다. */
	public int admit(long runId, long perSec, long passTtlMs, boolean capPerSecond) {
		// 이번에 입장시킬 수 있는 최대 인원만큼 토큰을 준비한다 (실제 인원은 스크립트가 정한다)
		int max = (int) Math.max(0, Math.min(perSec, Integer.MAX_VALUE));
		List<String> args = new ArrayList<>(max + 4);
		args.add(String.valueOf(runId));
		args.add(String.valueOf(perSec));
		args.add(String.valueOf(passTtlMs));
		args.add(capPerSecond ? "1" : "0");
		for (int i = 0; i < max; i++) {
			args.add(newToken());
		}
		List<?> admitted = redis.execute(admitScript,
				List.of(QueueKeys.queue(runId), QueueKeys.active(runId), QueueKeys.RUNS, QueueKeys.throughput(runId)),
				args.toArray());

		for (Object o : admitted) {
			String qm = (String) o;
			long memberId = Long.parseLong(qm.substring(qm.indexOf(':') + 1));
			logSink.record(new QueueLogEvent(Type.ADMITTED, memberId, runId, QueueService.ticketNo(qm),
					clock.instant(), null, null, null, null, "ADMITTED"));
		}
		return admitted.size();
	}

	/**
	 * 판매 중인 운행 중 줄 선 사람이나 입장 중인 사람이 있는데 활성 운행 목록에 없는 운행을 넣는다.
	 * 목록 도입 전에 줄 선 사람이나 Redis 키 유실을 대비하며, 앱이 뜰 때와 주기적으로 부른다. Redis 왕복은 1번.
	 */
	public long reconcile(List<Long> onSaleRunIds) {
		if (onSaleRunIds.isEmpty()) {
			return 0;
		}
		Object[] args = onSaleRunIds.stream().map(String::valueOf).toArray();
		Long added = redis.execute(reconcileScript, List.of(QueueKeys.RUNS), args);
		return added == null ? 0 : added;
	}

	/** 256비트 무작위 값. 추측·재사용이 불가능해야 하므로 SecureRandom을 쓴다. */
	static String newToken() {
		byte[] b = new byte[32];
		RANDOM.nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}
}
