package com.jejari.queue.domain;

import static com.jejari.queue.port.QueueLogSink.QueueLogEvent.Type;

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
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** QUE-01 진입, QUE-02 번호표 조회, QUE-05 이탈. 판단은 Lua 스크립트가 원자적으로 한다. */
public class QueueService {

    private final StringRedisTemplate redis;
    private final RedisScript<List> enterScript;
    private final RedisScript<List> leaveScript;
    private final PolicyProvider policy;
    private final RiskPenaltyService risk;
    private final MemberIdentityPort members;
    private final OnSaleRunProvider runs;
    private final QueueLogSink logSink;
    private final Clock clock;

    public QueueService(StringRedisTemplate redis, RedisScript<List> enterScript, RedisScript<List> leaveScript,
                        PolicyProvider policy, RiskPenaltyService risk, MemberIdentityPort members,
                        OnSaleRunProvider runs, QueueLogSink logSink, Clock clock) {
        this.redis = redis;
        this.enterScript = enterScript;
        this.leaveScript = leaveScript;
        this.policy = policy;
        this.risk = risk;
        this.members = members;
        this.runs = runs;
        this.logSink = logSink;
        this.clock = clock;
    }

    public QueueStatus enter(long memberId, long runId) {
        RunSale run = runs.find(runId).filter(RunSale::onSale)
                .orElseThrow(() -> new QueueException(ErrorCode.RUN_NOT_ON_SALE));
        MemberIdentity identity = members.find(memberId)
                .orElseThrow(() -> new QueueException(ErrorCode.AUTH_REQUIRED));
        if ("BANNED".equals(identity.status())) {
            throw new QueueException(ErrorCode.AUTH_BANNED);
        }

        double score = risk.score(identity);
        long penaltyMs = risk.penaltyMs(score);
        long now = clock.millis();
        long entryTtlMs = policy.getLong(PolicyProvider.ENTRY_TTL_SEC) * 1000;
        long closeAtMs = run.saleCloseAt() == null ? 0 : run.saleCloseAt().toEpochMilli();

        List<?> r = redis.execute(enterScript,
                List.of(QueueKeys.queue(runId), QueueKeys.seq(runId),
                        QueueKeys.member(memberId), QueueKeys.ci(identity.ciHash())),
                String.valueOf(runId), String.valueOf(memberId), identity.ciHash(),
                String.valueOf(now), String.valueOf(penaltyMs), String.valueOf(entryTtlMs),
                String.valueOf(closeAtMs));

        long status = ((Number) r.get(0)).longValue();
        if (status < 0) {
            logSink.record(new QueueLogEvent(Type.REJECTED_DUPLICATE, memberId, runId, null,
                    Instant.ofEpochMilli(now), score, null, null, null, "REJECTED_DUPLICATE"));
            throw new QueueException(ErrorCode.QUEUE_DUPLICATED);
        }
        if (status == 0) {
            String qm = (String) r.get(1);
            logSink.record(new QueueLogEvent(Type.ENTERED, memberId, runId, ticketNo(qm),
                    Instant.ofEpochMilli(now), score, penaltyMs,
                    ((Number) r.get(2)).longValue(), ((Number) r.get(3)).longValue(), null));
        }
        // status == 1: 이미 같은 운행에 있음. 새로 줄 서지 않고 기존 순번을 그대로 돌려준다.
        return status(memberId, runId);
    }

    public QueueStatus status(long memberId, long runId) {
        String val = redis.opsForValue().get(QueueKeys.member(memberId));
        if (val == null) {
            throw new QueueException(ErrorCode.QUEUE_NOT_FOUND);
        }
        String[] parts = val.split("\\|", 3);
        if (Long.parseLong(parts[0]) != runId) {
            throw new QueueException(ErrorCode.QUEUE_NOT_FOUND);
        }
        String qm = parts[1];
        long ticket = ticketNo(qm);

        // 조회 중에 입장 처리(ZPOPMIN)가 끼어들 수 있어, 순번이 없으면 토큰을 한 번 더 본다.
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = redis.opsForValue().get(QueueKeys.pass(runId, memberId));
            if (token != null) {
                return admitted(runId, memberId, ticket, token);
            }
            Long zeroBased = redis.opsForZSet().rank(QueueKeys.queue(runId), qm);
            if (zeroBased != null) {
                return waiting(runId, ticket, zeroBased + 1);
            }
        }
        throw new QueueException(ErrorCode.QUEUE_NOT_FOUND);
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

    private QueueStatus waiting(long runId, long ticket, long rank) {
        long throughput = Math.max(1, currentThroughput(runId));
        long eta = (rank + throughput - 1) / throughput;
        // 앞이 멀수록 천천히 묻게 한다: 1~5초 (기능설계서 QUE-02)
        int poll = (int) Math.min(5, Math.max(1, (eta + 9) / 10));
        return new QueueStatus(QueueStatus.State.WAITING, runId, ticket, rank, rank - 1, eta, poll, null, null);
    }

    private QueueStatus admitted(long runId, long memberId, long ticket, String token) {
        Long ttlMs = redis.getExpire(QueueKeys.pass(runId, memberId), TimeUnit.MILLISECONDS);
        OffsetDateTime expires = OffsetDateTime.now(clock).plus(java.time.Duration.ofMillis(ttlMs == null ? 0 : ttlMs));
        return new QueueStatus(QueueStatus.State.ADMITTED, runId, ticket, null, null, null, 1, token, expires);
    }

    private long currentThroughput(long runId) {
        String v = redis.opsForValue().get(QueueKeys.throughput(runId));
        return v != null ? Long.parseLong(v) : policy.getLong(PolicyProvider.ADMIT_PER_SEC);
    }

    /** queue member 문자열 "{12자리 seq}:{memberId}"에서 번호표 번호(seq)를 꺼낸다. */
    static long ticketNo(String queueMember) {
        return Long.parseLong(queueMember.substring(0, queueMember.indexOf(':')));
    }
}
