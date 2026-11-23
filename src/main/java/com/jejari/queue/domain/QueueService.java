package com.jejari.queue.domain;

import static com.jejari.queue.port.QueueLogSink.QueueLogEvent.Type;

import com.jejari.auth.AuthErrorCode;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.queue.QueueErrorCode;
import com.jejari.queue.port.MemberIdentityPort;
import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;
import com.jejari.queue.port.OnSaleRunProvider;
import com.jejari.queue.port.OnSaleRunProvider.RunSale;
import com.jejari.queue.port.QueueLogSink;
import com.jejari.queue.port.QueueLogSink.QueueLogEvent;
import com.jejari.queue.redis.QueueKeys;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** QUE-01 진입, QUE-02 번호표 조회, QUE-05 이탈. 판단은 Lua 스크립트가 원자적으로 한다. */
public class QueueService {

	private final StringRedisTemplate redis;
	private final RedisScript<List> enterScript;
	private final RedisScript<List> leaveScript;
	private final RedisScript<List> statusScript;
	private final RedisScript<List> extendScript;
	private final PolicyProvider policy;
	private final RiskPenaltyService risk;
	private final MemberIdentityPort members;
	private final OnSaleRunProvider runs;
	private final QueueLogSink logSink;
	private final Clock clock;

	public QueueService(StringRedisTemplate redis, RedisScript<List> enterScript, RedisScript<List> leaveScript,
						RedisScript<List> statusScript, RedisScript<List> extendScript, PolicyProvider policy, RiskPenaltyService risk, MemberIdentityPort members,
						OnSaleRunProvider runs, QueueLogSink logSink, Clock clock) {
		this.redis = redis;
		this.enterScript = enterScript;
		this.leaveScript = leaveScript;
		this.statusScript = statusScript;
		this.extendScript = extendScript;
		this.policy = policy;
		this.risk = risk;
		this.members = members;
		this.runs = runs;
		this.logSink = logSink;
		this.clock = clock;
	}

	public QueueStatus enter(long memberId, long runId) {
		RunSale run = runs.find(runId).filter(RunSale::onSale)
				.orElseThrow(() -> new BusinessException(QueueErrorCode.RUN_NOT_ON_SALE));
		MemberIdentity identity = members.find(memberId)
				.orElseThrow(() -> new BusinessException(CommonErrorCode.AUTH_REQUIRED));
		if ("BANNED".equals(identity.status())) {
			throw new BusinessException(AuthErrorCode.AUTH_BANNED);
		}

		double score = risk.score(identity);
		long penaltyMs = risk.penaltyMs(score);
		long entryTtlMs = policy.getLong(PolicyProvider.ENTRY_TTL_SEC) * 1000;
		long closeAtMs = run.saleCloseAt() == null ? 0 : run.saleCloseAt().toEpochMilli();

		List<?> r = redis.execute(enterScript,
				List.of(QueueKeys.queue(runId), QueueKeys.seq(runId),
						QueueKeys.member(memberId), QueueKeys.ci(identity.ciHash()),
						QueueKeys.throughput(runId), QueueKeys.RUNS),
				String.valueOf(runId), String.valueOf(memberId), identity.ciHash(),
				String.valueOf(penaltyMs), String.valueOf(entryTtlMs), String.valueOf(closeAtMs));

		long status = ((Number) r.get(0)).longValue();
		// 진입 시각은 Redis TIME 값 (스크립트가 돌려준다)
		long now = ((Number) r.get(4)).longValue();
		if (status < 0) {
			logSink.record(new QueueLogEvent(Type.REJECTED_DUPLICATE, memberId, runId, null,
					Instant.ofEpochMilli(now), score, null, null, null, "REJECTED_DUPLICATE"));
			throw new BusinessException(QueueErrorCode.QUEUE_DUPLICATED);
		}
		if (status == 0) {
			String qm = (String) r.get(1);
			long adjustedRank = ((Number) r.get(3)).longValue();
			logSink.record(new QueueLogEvent(Type.ENTERED, memberId, runId, ticketNo(qm),
					Instant.ofEpochMilli(now), score, penaltyMs,
					((Number) r.get(2)).longValue(), adjustedRank, null));
			// 순번은 스크립트 안에서 ZADD 직후 읽은 값이라, 다시 조회하지 않고 그대로 번호표를 만든다 (Redis 왕복 절약)
			return waiting(runId, ticketNo(qm), adjustedRank, ((Number) r.get(5)).longValue());
		}
		// status == 1: 이미 같은 운행에 있음. 새로 줄 서지 않고 기존 순번을 그대로 돌려준다.
		return status(memberId, runId);
	}

	/** 번호표 조회. 스크립트 한 번으로 순번·토큰·입장 속도를 함께 읽어, 조회 중 입장 처리가 끼어들어도 어긋나지 않는다. */
	public QueueStatus status(long memberId, long runId) {
		List<?> r = redis.execute(statusScript, List.of(QueueKeys.member(memberId)),
				String.valueOf(runId), String.valueOf(memberId));
		long kind = ((Number) r.get(0)).longValue();
		if (kind == 0) {
			throw new BusinessException(QueueErrorCode.QUEUE_NOT_FOUND);
		}
		long ticket = ticketNo((String) r.get(1));
		if (kind == 2) {
			return admitted(runId, ticket, (String) r.get(3), ((Number) r.get(2)).longValue());
		}
		return waiting(runId, ticket, ((Number) r.get(2)).longValue(), ((Number) r.get(4)).longValue());
	}

	/**
	 * 입장 토큰 연장. 회원당 운행마다 1회, queue.pass_extend_sec(기본 300초)만큼 남은 시간에 더한다.
	 * 토큰이 없으면 QUEUE_TOKEN_INVALID, 이미 연장했으면 QUEUE_EXTEND_USED. 판정은 Lua 한 번이라 동시에 눌러도 1회다.
	 */
	public QueueStatus extend(long memberId, long runId) {
		long addMs = policy.getLong(PolicyProvider.PASS_EXTEND_SEC) * 1000;
		List<?> r = redis.execute(extendScript,
				List.of(QueueKeys.pass(runId, memberId), QueueKeys.passExt(runId, memberId),
						QueueKeys.active(runId), QueueKeys.member(memberId)),
				String.valueOf(memberId), String.valueOf(addMs));
		long result = ((Number) r.get(0)).longValue();
		if (result == -1) {
			throw new BusinessException(QueueErrorCode.QUEUE_TOKEN_INVALID);
		}
		if (result == -2) {
			throw new BusinessException(QueueErrorCode.QUEUE_EXTEND_USED);
		}
		return status(memberId, runId);
	}

	/** 대기 중이든 입장했든 지운다. 이미 나갔으면 아무 일도 하지 않는다 (멱등). */
	public void leave(long memberId, long runId) {
		List<?> r = redis.execute(leaveScript, List.of(QueueKeys.member(memberId)),
				String.valueOf(runId), String.valueOf(memberId));
		if (((Number) r.get(0)).longValue() == 1) {
			logSink.record(new QueueLogEvent(Type.LEFT, memberId, runId, null, clock.instant(),
					null, null, null, null, "LEFT"));
		}
	}

	private QueueStatus waiting(long runId, long ticket, long rank, long throughputPerSec) {
		// 입장 속도 기록이 아직 없으면(첫 입장 처리 전) 정책값을 쓴다
		long throughput = Math.max(1, throughputPerSec > 0 ? throughputPerSec : policy.getLong(PolicyProvider.ADMIT_PER_SEC));
		long eta = (rank + throughput - 1) / throughput;
		// 앞이 멀수록 천천히 묻게 한다: 1~5초 (기능설계서 QUE-02)
		int poll = (int) Math.min(5, Math.max(1, (eta + 9) / 10));
		return new QueueStatus(QueueStatus.State.WAITING, runId, ticket, rank, rank - 1, eta, poll, null, null);
	}

	private QueueStatus admitted(long runId, long ticket, String token, long ttlMs) {
		OffsetDateTime expires = OffsetDateTime.now(clock).plus(java.time.Duration.ofMillis(Math.max(0, ttlMs)));
		return new QueueStatus(QueueStatus.State.ADMITTED, runId, ticket, null, null, null, 1, token, expires);
	}

	/** queue member 문자열 "{12자리 seq}:{memberId}"에서 번호표 번호(seq)를 꺼낸다. */
	static long ticketNo(String queueMember) {
		return Long.parseLong(queueMember.substring(0, queueMember.indexOf(':')));
	}
}
