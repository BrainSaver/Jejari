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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * QUE-03 입장 토큰 발급. 한 번 호출하면 한 운행의 맨 앞 queue.admit_per_sec명을 입장시킨다.
 * 1초마다 호출하는 것은 QueueAdmitScheduler. 1차는 앱 1대 기준이다 (여러 대면 인원이 대수만큼 늘어남).
 */
public class QueueAdmitter {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final RedisScript<List> admitScript;
    private final PolicyProvider policy;
    private final QueueLogSink logSink;
    private final Clock clock;

    public QueueAdmitter(StringRedisTemplate redis, RedisScript<List> admitScript, PolicyProvider policy,
                         QueueLogSink logSink, Clock clock) {
        this.redis = redis;
        this.admitScript = admitScript;
        this.policy = policy;
        this.logSink = logSink;
        this.clock = clock;
    }

    /** 입장시킨 인원 수 */
    public int admit(long runId) {
        long perSec = policy.getLong(PolicyProvider.ADMIT_PER_SEC);
        // 예상 대기 시간 계산용. 지금은 정책값 그대로이며, 부하 테스트 후 실측값으로 바꿀 수 있다.
        redis.opsForValue().set(QueueKeys.throughput(runId), String.valueOf(perSec));

        Long waiting = redis.opsForZSet().zCard(QueueKeys.queue(runId));
        int count = (int) Math.min(perSec, waiting == null ? 0 : waiting);
        if (count == 0) {
            // 입장한 사람이 없어도 만료된 active 항목은 정리한다
            redis.opsForZSet().removeRangeByScore(QueueKeys.active(runId), Double.NEGATIVE_INFINITY, clock.millis());
            return 0;
        }

        long passTtlMs = policy.getLong(PolicyProvider.PASS_TTL_SEC) * 1000;
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(runId));
        args.add(String.valueOf(count));
        args.add(String.valueOf(clock.millis()));
        args.add(String.valueOf(passTtlMs));
        for (int i = 0; i < count; i++) {
            args.add(newToken());
        }
        List<?> admitted = redis.execute(admitScript,
                List.of(QueueKeys.queue(runId), QueueKeys.active(runId)), args.toArray());

        for (Object o : admitted) {
            String qm = (String) o;
            long memberId = Long.parseLong(qm.substring(qm.indexOf(':') + 1));
            logSink.record(new QueueLogEvent(Type.ADMITTED, memberId, runId, QueueService.ticketNo(qm),
                    clock.instant(), null, null, null, null, "ADMITTED"));
        }
        return admitted.size();
    }

    /** 256비트 무작위 값. 추측·재사용이 불가능해야 하므로 SecureRandom을 쓴다. */
    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
