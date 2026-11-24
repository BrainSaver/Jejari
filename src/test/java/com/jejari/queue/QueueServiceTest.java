package com.jejari.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jejari.auth.AuthErrorCode;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.ErrorCode;
import com.jejari.queue.domain.QueueAdmitter;
import com.jejari.queue.domain.QueuePassService;
import com.jejari.queue.domain.QueueService;
import com.jejari.queue.domain.QueueStatus;
import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;
import com.jejari.queue.port.QueueLogSink.QueueLogEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class QueueServiceTest extends QueueRedisSupport {

    @Autowired QueueService queue;
    @Autowired QueueAdmitter admitter;
    @Autowired QueuePassService pass;

    private void setRisk(String type, String hash, String score) {
        redis.opsForValue().set("risk:" + type + ":" + hash, score);
    }

    private void setPolicy(String key, String value) {
        redis.opsForHash().put("policy:cache", key, value);
    }

    // ---------- 입장 토큰 연장 (1회) ----------

    @Test
    void 입장_토큰은_한_번만_연장되고_남은_시간에_연장분이_더해진다() {
        setPolicy("queue.pass_ttl_sec", "60");
        setPolicy("queue.pass_extend_sec", "300");
        queue.enter(1, RUN);
        admitter.admit(RUN);
        QueueStatus before = queue.status(1, RUN);
        assertThat(before.state()).isEqualTo(QueueStatus.State.ADMITTED);

        QueueStatus after = queue.extend(1, RUN);

        assertThat(after.passToken()).isEqualTo(before.passToken()); // 토큰은 그대로, 시간만 늘어난다
        assertThat(after.passExpiresAt()).isAfter(before.passExpiresAt().plusSeconds(290));
        assertThat(redis.getExpire("queue:pass:" + RUN + ":1", java.util.concurrent.TimeUnit.SECONDS)).isGreaterThan(300);
        // 두 번째는 거절
        assertThatThrownBy(() -> queue.extend(1, RUN))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(QueueErrorCode.QUEUE_EXTEND_USED));
    }

    @Test
    void 좌석_화면을_나가_이탈한_뒤_다시_줄을_서면_새_토큰과_새_시간을_받고_연장도_다시_된다() {
        setPolicy("queue.pass_ttl_sec", "60");
        setPolicy("queue.pass_extend_sec", "300");
        queue.enter(1, RUN);
        admitter.admit(RUN);
        QueueStatus first = queue.extend(1, RUN); // 첫 입장에서 연장을 써 둔다

        queue.leave(1, RUN); // 좌석 화면을 나감
        QueueStatus again = queue.enter(1, RUN);
        assertThat(again.state()).isEqualTo(QueueStatus.State.WAITING); // 바로 들어오지 않고 다시 줄을 선다
        admitter.admit(RUN);
        QueueStatus second = queue.status(1, RUN);

        assertThat(second.state()).isEqualTo(QueueStatus.State.ADMITTED);
        assertThat(second.passToken()).isNotEqualTo(first.passToken());
        assertThat(second.passExpiresAt()).isBefore(first.passExpiresAt()); // 연장 전 기본 시간(60초)으로 새로 시작
        assertThat(queue.extend(1, RUN).passExpiresAt()).isAfter(second.passExpiresAt().plusSeconds(290)); // 새 차례라 다시 연장 가능
    }

    @Test
    void 입장하지_않았거나_토큰이_없으면_연장할_수_없다() {
        queue.enter(1, RUN); // 대기 중 (토큰 없음)
        assertThatThrownBy(() -> queue.extend(1, RUN))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(QueueErrorCode.QUEUE_TOKEN_INVALID));
    }

    @Test
    void 동시에_여러_번_눌러도_연장은_한_번이다() throws Exception {
        queue.enter(1, RUN);
        admitter.admit(RUN);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var ok = new java.util.concurrent.atomic.AtomicInteger();
        var used = new java.util.concurrent.atomic.AtomicInteger();
        List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            fs.add(pool.submit(() -> {
                try {
                    queue.extend(1, RUN);
                    ok.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == QueueErrorCode.QUEUE_EXTEND_USED) used.incrementAndGet();
                }
            }));
        }
        for (var f : fs) f.get();
        pool.shutdown();
        assertThat(ok.get()).isEqualTo(1);
        assertThat(used.get()).isEqualTo(15);
    }

    // ---------- 활성 운행 목록(queue:runs)과 입장 처리 ----------

    private java.util.Set<String> activeRuns() {
        return redis.opsForSet().members("queue:runs");
    }

    private long redisSec() {
        return redis.execute((org.springframework.data.redis.core.RedisCallback<Long>) c -> c.serverCommands().time(java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void 줄_선_사람이_있는_운행만_활성_운행_목록에_들어가고_비면_빠진다() {
        assertThat(activeRuns()).isEmpty();
        assertThat(admitter.admitActiveRuns()).isZero(); // 목록이 비면 아무 운행도 건드리지 않는다

        queue.enter(1, RUN);
        assertThat(activeRuns()).containsExactly(String.valueOf(RUN));

        assertThat(admitter.admitActiveRuns()).isEqualTo(1); // 입장
        assertThat(activeRuns()).containsExactly(String.valueOf(RUN)); // 토큰이 살아 있는 동안은 정리할 것이 남아 있다

        queue.leave(1, RUN); // 줄도 입장도 모두 비움
        admitter.admitActiveRuns();
        assertThat(activeRuns()).isEmpty();
    }

    @Test
    void 입장한_사람의_토큰이_끝나면_운행이_활성_운행_목록에서_빠진다() throws Exception {
        setPolicy("queue.pass_ttl_sec", "1");
        queue.enter(1, RUN);
        admitter.admitActiveRuns();
        assertThat(activeRuns()).contains(String.valueOf(RUN));

        Thread.sleep(1300); // 토큰·active 만료
        admitter.admitActiveRuns();
        assertThat(activeRuns()).isEmpty();
    }

    @Test
    void 판매_중이어도_사람이_없으면_목록에_없고_목록_맞추기는_있는_운행만_넣는다() {
        queue.enter(1, RUN);
        redis.delete("queue:runs"); // 목록 도입 전에 줄 선 사람·키 유실 흉내
        assertThat(admitter.reconcile(List.of(RUN, 424242L))).isEqualTo(1);
        assertThat(activeRuns()).containsExactly(String.valueOf(RUN));
        assertThat(admitter.reconcile(List.of(RUN, 424242L))).isZero(); // 두 번 해도 같다
    }

    @Test
    void 초당_상한을_켜면_같은_초에_여러_번_불러도_정책값을_넘지_않는다() {
        for (int m = 1; m <= 30; m++) queue.enter(m, RUN);
        long s1 = redisSec();
        int total = 0;
        for (int i = 0; i < 5; i++) total += admitter.admit(RUN, 10, 300_000, true);
        long s2 = redisSec();
        // 5번 모두 같은 초 안이면 10명, 초가 넘어갔으면 그만큼 더 (초당 10명)
        assertThat(total).isLessThanOrEqualTo((int) (10 * (s2 - s1 + 1)));
        assertThat(total).isGreaterThanOrEqualTo(10);
    }

    @Test
    void 앱_여러_대가_동시에_입장_처리해도_입장_속도는_정책값이다() throws Exception {
        for (int m = 1; m <= 200; m++) queue.enter(m, RUN);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> fs = new java.util.ArrayList<>();
        long s1 = redisSec();
        for (int i = 0; i < 16; i++) { // 앱 2대가 같은 시각에 스케줄러를 돌리는 상황
            fs.add(pool.submit(() -> { start.await(); return admitter.admit(RUN, 20, 300_000, true); }));
        }
        start.countDown();
        int total = 0;
        for (var f : fs) total += f.get();
        pool.shutdown();
        long s2 = redisSec();
        assertThat(total).isLessThanOrEqualTo((int) (20 * (s2 - s1 + 1)));
        assertThat(total).isGreaterThanOrEqualTo(20);
        // 입장한 사람은 중복 없이 정확히 total명
        assertThat(redis.opsForZSet().zCard("queue:active:" + RUN)).isEqualTo((long) total);
    }

    // ---------- 진입 ----------

    @Test
    void 먼저_온_순서대로_번호표를_받는다() {
        QueueStatus a = queue.enter(1, RUN);
        QueueStatus b = queue.enter(2, RUN);
        QueueStatus c = queue.enter(3, RUN);

        assertThat(List.of(a.rank(), b.rank(), c.rank())).containsExactly(1L, 2L, 3L);
        assertThat(List.of(a.ticketNo(), b.ticketNo(), c.ticketNo())).containsExactly(1L, 2L, 3L);
        assertThat(c.ahead()).isEqualTo(2);
        assertThat(a.state()).isEqualTo(QueueStatus.State.WAITING);
    }

    @Test
    void 회원_ID와_상관없이_진입_순서대로_정렬된다() {
        // 같은 밀리초면 score가 같고, 그때는 member 문자열 앞의 12자리 seq가 순서를 결정한다 (ID는 비교에 쓰이지 않음)
        queue.enter(30, RUN);
        queue.enter(4, RUN);
        queue.enter(100, RUN);
        assertThat(queue.status(30, RUN).rank()).isEqualTo(1);
        assertThat(queue.status(4, RUN).rank()).isEqualTo(2);
        assertThat(queue.status(100, RUN).rank()).isEqualTo(3);
    }

    @Test
    void 위험_점수만큼_순번이_뒤로_밀리고_기록이_남는다() throws Exception {
        // 진입 시각은 Redis 시계라 테스트에서 시간을 건너뛸 수 없다 → 단가를 10ms/점으로 줄여 80점 = 800ms 뒤
        setPolicy("risk.penalty_ms_per_point", "10");
        members.register(new MemberIdentity(9, "ACTIVE", "ci-9", List.of(), List.of()));
        setRisk("CI", "ci-9", "80.00");

        QueueStatus risky = queue.enter(9, RUN);
        assertThat(risky.rank()).isEqualTo(1); // 아직 아무도 없으니 1번
        queue.enter(1, RUN);
        queue.enter(2, RUN); // 800ms 안에 온 사람들은 회원 9보다 앞선다

        assertThat(queue.status(1, RUN).rank()).isEqualTo(1);
        assertThat(queue.status(2, RUN).rank()).isEqualTo(2);
        assertThat(queue.status(9, RUN).rank()).isEqualTo(3);

        Thread.sleep(1_000); // 패널티 시간이 지난 뒤에 온 사람은 회원 9보다 뒤
        queue.enter(3, RUN);
        assertThat(queue.status(9, RUN).rank()).isEqualTo(3);
        assertThat(queue.status(3, RUN).rank()).isEqualTo(4);

        QueueLogEvent entered = sink.events.stream()
                .filter(e -> e.memberId() == 9 && e.type() == QueueLogEvent.Type.ENTERED).findFirst().orElseThrow();
        assertThat(entered.penaltyMs()).isEqualTo(800);
        assertThat(entered.riskScore()).isEqualTo(80.0);
    }

    @Test
    void 조정_전_순번과_조정_후_순번을_기록한다() {
        queue.enter(1, RUN);
        queue.enter(2, RUN);
        members.register(new MemberIdentity(3, "ACTIVE", "ci-3", List.of(), List.of()));
        setRisk("CI", "ci-3", "50");
        queue.enter(3, RUN);   // 패널티 50초
        queue.enter(4, RUN);

        QueueLogEvent e = sink.events.stream()
                .filter(x -> x.memberId() == 3 && x.type() == QueueLogEvent.Type.ENTERED).findFirst().orElseThrow();
        // 패널티가 없었다면 3번째, 4번이 뒤에 오므로 그대로 3번째. 이후 진입자는 영향을 받는다.
        assertThat(e.initialRank()).isEqualTo(3);
        assertThat(e.adjustedRank()).isEqualTo(3);
        assertThat(queue.status(4, RUN).rank()).isEqualTo(3);  // 4가 3을 앞지름
        assertThat(queue.status(3, RUN).rank()).isEqualTo(4);
    }

    @Test
    void 결제수단과_기기_점수도_합쳐_최댓값을_쓴다() {
        members.register(new MemberIdentity(5, "ACTIVE", "ci-5", List.of("pay-5"), List.of("dev-5")));
        setRisk("CI", "ci-5", "10");
        setRisk("PAYMENT", "pay-5", "90");
        setRisk("DEVICE", "dev-5", "30");
        queue.enter(5, RUN);
        QueueLogEvent e = sink.events.get(0);
        assertThat(e.riskScore()).isEqualTo(90.0);
        assertThat(e.penaltyMs()).isEqualTo(90_000);
    }

    @Test
    void 패널티_단가는_정책에서_읽는다() {
        setPolicy("risk.penalty_ms_per_point", "500");
        members.register(new MemberIdentity(6, "ACTIVE", "ci-6", List.of(), List.of()));
        setRisk("CI", "ci-6", "80");
        queue.enter(6, RUN);
        assertThat(sink.events.get(0).penaltyMs()).isEqualTo(40_000);
    }

    @Test
    void RESTRICTED_회원은_최대_패널티를_받는다() {
        members.register(new MemberIdentity(7, "RESTRICTED", "ci-7", List.of(), List.of()));
        queue.enter(7, RUN);
        assertThat(sink.events.get(0).penaltyMs()).isEqualTo(100_000);
    }

    @Test
    void BANNED_회원은_진입할_수_없다() {
        members.register(new MemberIdentity(8, "BANNED", "ci-8", List.of(), List.of()));
        assertCode(() -> queue.enter(8, RUN), AuthErrorCode.AUTH_BANNED);
    }

    @Test
    void 판매중이_아닌_운행에는_들어갈_수_없다() {
        assertCode(() -> queue.enter(1, 777), QueueErrorCode.RUN_NOT_ON_SALE);
        runs.close(RUN);
        assertCode(() -> queue.enter(1, RUN), QueueErrorCode.RUN_NOT_ON_SALE);
    }

    @Test
    void 진입_시각은_앱_시계가_아니라_Redis_시계를_쓴다() {
        // 테스트 시계는 2026-11-20으로 고정돼 있다. score가 Redis 현재 시각 근처면 Redis TIME을 쓴 것이다.
        long before = redisNowMs();
        queue.enter(1, RUN);
        long after = redisNowMs();
        double score = redis.opsForZSet().score("queue:" + RUN, "000000000001:1");
        assertThat(score).isBetween((double) before, (double) after);
        assertThat(sink.events.get(0).at().toEpochMilli()).isBetween(before, after);
    }

    private long redisNowMs() {
        return redis.execute((org.springframework.data.redis.core.RedisCallback<Long>) c ->
                c.serverCommands().time(java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    // ---------- 중복 진입 ----------

    @Test
    void 새로고침해도_순번은_그대로다() {
        queue.enter(1, RUN);
        QueueStatus first = queue.enter(2, RUN);
        QueueStatus again = queue.enter(2, RUN);

        assertThat(again.ticketNo()).isEqualTo(first.ticketNo());
        assertThat(again.rank()).isEqualTo(first.rank());
        assertThat(redis.opsForZSet().zCard("queue:" + RUN)).isEqualTo(2);
        // 중복 진입은 새 번호표를 만들지 않으므로 ENTERED 로그도 한 번뿐
        assertThat(sink.events.stream().filter(e -> e.memberId() == 2)).hasSize(1);
    }

    @Test
    void 다른_운행에_대기_중이면_거절한다() {
        runs.open(2002);
        queue.enter(1, RUN);
        assertCode(() -> queue.enter(1, 2002), QueueErrorCode.QUEUE_DUPLICATED);
        assertThat(redis.hasKey("queue:2002")).isFalse();
        assertThat(sink.events.stream().anyMatch(e -> e.type() == QueueLogEvent.Type.REJECTED_DUPLICATE)).isTrue();
    }

    @Test
    void 같은_CI의_다른_계정이_대기_중이면_거절한다() {
        members.register(new MemberIdentity(1, "ACTIVE", "same-ci", List.of(), List.of()));
        members.register(new MemberIdentity(2, "ACTIVE", "same-ci", List.of(), List.of()));
        queue.enter(1, RUN);
        assertCode(() -> queue.enter(2, RUN), QueueErrorCode.QUEUE_DUPLICATED);
        assertThat(redis.opsForZSet().zCard("queue:" + RUN)).isEqualTo(1);
        assertThat(sink.events.stream()
                .anyMatch(e -> e.memberId() == 2 && e.type() == QueueLogEvent.Type.REJECTED_DUPLICATE)).isTrue();
    }

    @Test
    void 같은_CI의_이전_계정이_나가면_다른_계정이_들어갈_수_있다() {
        members.register(new MemberIdentity(1, "ACTIVE", "same-ci", List.of(), List.of()));
        members.register(new MemberIdentity(2, "ACTIVE", "same-ci", List.of(), List.of()));
        queue.enter(1, RUN);
        queue.leave(1, RUN);
        assertThat(queue.enter(2, RUN).rank()).isEqualTo(1);
    }

    @Test
    void 만료된_CI_기록은_진입_때_정리된다() {
        members.register(new MemberIdentity(1, "ACTIVE", "same-ci", List.of(), List.of()));
        members.register(new MemberIdentity(2, "ACTIVE", "same-ci", List.of(), List.of()));
        queue.enter(1, RUN);
        // 1번의 대기 기록이 사라지고(Redis 만료) CI 항목의 만료 시각도 한참 지났다고 가정
        redis.delete("queue:member:1");
        redis.opsForZSet().add("queue:ci:same-ci", "1", 1);
        assertThat(queue.enter(2, RUN)).isNotNull();
    }

    // ---------- 번호표 조회 ----------

    @Test
    void 번호표는_앞사람_수와_예상_대기_시간을_알려준다() {
        setPolicy("queue.admit_per_sec", "10");
        for (long m = 1; m <= 25; m++) {
            queue.enter(m, RUN);
        }
        QueueStatus s = queue.status(25, RUN);
        assertThat(s.rank()).isEqualTo(25);
        assertThat(s.ahead()).isEqualTo(24);
        assertThat(s.estimatedWaitSec()).isEqualTo(3); // ceil(25 / 10)
        assertThat(s.pollIntervalSec()).isBetween(1, 5);
    }

    @Test
    void 대기_중이_아니면_번호표_조회가_404다() {
        assertCode(() -> queue.status(1, RUN), QueueErrorCode.QUEUE_NOT_FOUND);
        queue.enter(1, RUN);
        assertCode(() -> queue.status(1, 2002), QueueErrorCode.QUEUE_NOT_FOUND);
    }

    // ---------- 입장·토큰 ----------

    @Test
    void 입장은_맨_앞부터_정책_인원만큼이고_토큰을_받는다() {
        setPolicy("queue.admit_per_sec", "3");
        for (long m = 1; m <= 5; m++) {
            queue.enter(m, RUN);
        }
        assertThat(admitter.admit(RUN)).isEqualTo(3);

        for (long m = 1; m <= 3; m++) {
            QueueStatus s = queue.status(m, RUN);
            assertThat(s.state()).isEqualTo(QueueStatus.State.ADMITTED);
            assertThat(s.passToken()).hasSizeGreaterThanOrEqualTo(40);
            assertThat(s.passExpiresAt()).isNotNull();
        }
        assertThat(queue.status(4, RUN).rank()).isEqualTo(1);
        assertThat(queue.status(5, RUN).rank()).isEqualTo(2);
        assertThat(queue.status(4, RUN).passToken()).isNull();

        long admittedLogs = sink.events.stream().filter(e -> e.type() == QueueLogEvent.Type.ADMITTED).count();
        assertThat(admittedLogs).isEqualTo(3);
    }

    @Test
    void 패널티를_받은_사람은_나중에_입장한다() {
        setPolicy("queue.admit_per_sec", "2");
        members.register(new MemberIdentity(1, "ACTIVE", "ci-1", List.of(), List.of()));
        setRisk("CI", "ci-1", "100");
        queue.enter(1, RUN);          // 가장 먼저 왔지만 100초 뒤로
        queue.enter(2, RUN);
        queue.enter(3, RUN);

        admitter.admit(RUN);
        assertThat(queue.status(2, RUN).state()).isEqualTo(QueueStatus.State.ADMITTED);
        assertThat(queue.status(3, RUN).state()).isEqualTo(QueueStatus.State.ADMITTED);
        assertThat(queue.status(1, RUN).state()).isEqualTo(QueueStatus.State.WAITING);
    }

    @Test
    void 입장한_뒤_다시_진입하면_같은_토큰이_온다() {
        queue.enter(1, RUN);
        admitter.admit(RUN);
        QueueStatus s = queue.status(1, RUN);
        QueueStatus again = queue.enter(1, RUN);
        assertThat(again.state()).isEqualTo(QueueStatus.State.ADMITTED);
        assertThat(again.passToken()).isEqualTo(s.passToken());
    }

    @Test
    void 입장한_회원도_토큰이_살아있는_동안은_다른_운행에_못_들어간다() {
        runs.open(2002);
        queue.enter(1, RUN);
        admitter.admit(RUN);
        assertCode(() -> queue.enter(1, 2002), QueueErrorCode.QUEUE_DUPLICATED);
    }

    @Test
    void 입장한_회원의_같은_CI_다른_계정도_막는다() {
        members.register(new MemberIdentity(1, "ACTIVE", "same-ci", List.of(), List.of()));
        members.register(new MemberIdentity(2, "ACTIVE", "same-ci", List.of(), List.of()));
        queue.enter(1, RUN);
        admitter.admit(RUN);
        assertCode(() -> queue.enter(2, RUN), QueueErrorCode.QUEUE_DUPLICATED);
    }

    @Test
    void 토큰_유효시간은_정책을_따르고_지나면_모든_기록이_사라진다() throws Exception {
        setPolicy("queue.pass_ttl_sec", "1");
        queue.enter(1, RUN);
        admitter.admit(RUN);
        String token = queue.status(1, RUN).passToken();
        pass.verify(RUN, 1, token);

        Thread.sleep(1_300);
        assertCode(() -> pass.verify(RUN, 1, token), QueueErrorCode.QUEUE_TOKEN_INVALID);
        assertThat(redis.hasKey("queue:member:1")).isFalse();
        // 토큰이 만료되면 다시 줄을 설 수 있다
        assertThat(queue.enter(1, RUN).state()).isEqualTo(QueueStatus.State.WAITING);
    }

    // ---------- 토큰 검증 (좌석 API 차단율 100%) ----------

    @Test
    void 토큰이_없거나_틀리면_모두_거절한다() {
        queue.enter(1, RUN);
        queue.enter(2, RUN);
        admitter.admit(RUN);
        String token1 = queue.status(1, RUN).passToken();
        String token2 = queue.status(2, RUN).passToken();

        pass.verify(RUN, 1, token1);
        assertCode(() -> pass.verify(RUN, 1, null), QueueErrorCode.QUEUE_TOKEN_REQUIRED);
        assertCode(() -> pass.verify(RUN, 1, " "), QueueErrorCode.QUEUE_TOKEN_REQUIRED);
        assertCode(() -> pass.verify(RUN, 1, "garbage"), QueueErrorCode.QUEUE_TOKEN_INVALID);
        assertCode(() -> pass.verify(RUN, 1, token2), QueueErrorCode.QUEUE_TOKEN_INVALID);   // 남의 토큰
        assertCode(() -> pass.verify(2002, 1, token1), QueueErrorCode.QUEUE_TOKEN_INVALID);  // 다른 운행
        assertCode(() -> pass.verify(RUN, 3, token1), QueueErrorCode.QUEUE_TOKEN_INVALID);   // 대기열에 없는 회원
    }

    @Test
    void 대기_중인_사람은_토큰이_없다() {
        queue.enter(1, RUN);
        assertCode(() -> pass.verify(RUN, 1, "anything"), QueueErrorCode.QUEUE_TOKEN_INVALID);
    }

    // ---------- 이탈 ----------

    @Test
    void 대기_중_이탈하면_줄에서_빠지고_뒷사람이_당겨진다() {
        queue.enter(1, RUN);
        queue.enter(2, RUN);
        queue.enter(3, RUN);
        queue.leave(2, RUN);
        assertThat(queue.status(3, RUN).rank()).isEqualTo(2);
        assertCode(() -> queue.status(2, RUN), QueueErrorCode.QUEUE_NOT_FOUND);
        assertThat(sink.events.stream().anyMatch(e -> e.type() == QueueLogEvent.Type.LEFT && e.memberId() == 2)).isTrue();
    }

    @Test
    void 입장_후_이탈하면_토큰이_즉시_무효가_된다() {
        queue.enter(1, RUN);
        admitter.admit(RUN);
        String token = queue.status(1, RUN).passToken();
        pass.verify(RUN, 1, token);

        queue.leave(1, RUN);
        assertCode(() -> pass.verify(RUN, 1, token), QueueErrorCode.QUEUE_TOKEN_INVALID);
        assertThat(redis.opsForZSet().score("queue:active:" + RUN, "1")).isNull();
        assertThat(redis.hasKey("queue:member:1")).isFalse();
    }

    @Test
    void 이탈은_여러_번_해도_같고_대기_중이_아니어도_오류가_없다() {
        queue.leave(1, RUN);
        queue.enter(1, RUN);
        queue.leave(1, RUN);
        queue.leave(1, RUN);
        assertThat(sink.events.stream().filter(e -> e.type() == QueueLogEvent.Type.LEFT)).hasSize(1);
    }

    @Test
    void 다른_운행_대기_중인_회원의_이탈_요청은_기록을_건드리지_않는다() {
        runs.open(2002);
        queue.enter(1, RUN);
        queue.leave(1, 2002);
        assertThat(queue.status(1, RUN).rank()).isEqualTo(1);
    }

    // ---------- 동시성 ----------

    @Test
    void 동시에_500명이_진입해도_번호표가_겹치지_않는다() throws Exception {
        int n = 500;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(32);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<QueueStatus>> fs = new java.util.ArrayList<>();
        for (long m = 1; m <= n; m++) {
            long id = m;
            fs.add(pool.submit(() -> {
                start.await();
                return queue.enter(id, RUN);
            }));
        }
        start.countDown();
        java.util.Set<Long> tickets = new java.util.HashSet<>();
        for (var f : fs) {
            tickets.add(f.get().ticketNo());
        }
        pool.shutdown();

        assertThat(tickets).hasSize(n);
        assertThat(redis.opsForZSet().zCard("queue:" + RUN)).isEqualTo(n);
        // 시각을 Redis가 스크립트 안에서 찍으므로, 순번은 번호표 순서와 항상 일치해야 한다
        for (long m = 1; m <= n; m++) {
            QueueStatus s = queue.status(m, RUN);
            assertThat(s.rank()).isEqualTo(s.ticketNo());
        }
    }

    @Test
    void 같은_회원이_동시에_연타해도_줄에는_한_번만_선다() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<QueueStatus>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                return queue.enter(1, RUN);
            }));
        }
        start.countDown();
        java.util.Set<Long> tickets = new java.util.HashSet<>();
        for (var f : fs) {
            tickets.add(f.get().ticketNo());
        }
        pool.shutdown();

        assertThat(tickets).hasSize(1);
        assertThat(redis.opsForZSet().zCard("queue:" + RUN)).isEqualTo(1);
        assertThat(sink.events.stream().filter(e -> e.type() == QueueLogEvent.Type.ENTERED)).hasSize(1);
    }

    @Test
    void 같은_CI의_여러_계정이_동시에_들어와도_한_계정만_선다() throws Exception {
        for (long m = 1; m <= 20; m++) {
            members.register(new MemberIdentity(m, "ACTIVE", "one-person", List.of(), List.of()));
        }
        var pool = java.util.concurrent.Executors.newFixedThreadPool(20);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> fs = new java.util.ArrayList<>();
        for (long m = 1; m <= 20; m++) {
            long id = m;
            fs.add(pool.submit(() -> {
                start.await();
                try {
                    queue.enter(id, RUN);
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int success = 0;
        for (var f : fs) {
            if (f.get()) success++;
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        assertThat(redis.opsForZSet().zCard("queue:" + RUN)).isEqualTo(1);
    }

    @Test
    void 입장_처리와_진입이_동시에_일어나도_한_사람이_두_번_입장하지_않는다() throws Exception {
        setPolicy("queue.admit_per_sec", "50");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
        for (long m = 1; m <= 200; m++) {
            long id = m;
            fs.add(pool.submit(() -> { start.await(); queue.enter(id, RUN); return null; }));
        }
        for (int i = 0; i < 6; i++) {
            fs.add(pool.submit(() -> { start.await(); admitter.admit(RUN); return null; }));
        }
        start.countDown();
        for (var f : fs) f.get();
        pool.shutdown();
        while (admitter.admit(RUN) > 0) { /* 남은 인원 모두 입장 */ }

        long admittedLogs = sink.events.stream().filter(e -> e.type() == QueueLogEvent.Type.ADMITTED).count();
        assertThat(admittedLogs).isEqualTo(200);
        assertThat(sink.events.stream().filter(e -> e.type() == QueueLogEvent.Type.ADMITTED)
                .map(QueueLogEvent::memberId).distinct().count()).isEqualTo(200);
        assertThat(redis.opsForZSet().zCard("queue:active:" + RUN)).isEqualTo(200);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
