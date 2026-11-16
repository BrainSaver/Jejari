package com.jejari.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.jejari.queue.domain.PolicyProvider;
import com.jejari.queue.domain.RiskPenaltyService;
import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;
import com.jejari.queue.port.RiskScoreFallbackPort;
import com.jejari.queue.redis.QueueKeys;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 위험 점수 읽기의 안전장치: 동기화 표시 키가 없을 때 Oracle 조회(6번), 값 형식 검증(7번). */
class RiskPenaltyServiceTest extends QueueRedisSupport {

    @Autowired PolicyProvider policy;

    private final AtomicInteger sourceCalls = new AtomicInteger();

    private static final MemberIdentity M = new MemberIdentity(1, "ACTIVE", "ci-1", List.of("pay-1"), List.of("dev-1"));

    private RiskPenaltyService service(RiskScoreFallbackPort fallback) {
        return new RiskPenaltyService(redis, policy, m -> {
            sourceCalls.incrementAndGet();
            return fallback.maxScore(m);
        }, clock, Duration.ofMinutes(30));
    }

    private void synced() {
        redis.opsForValue().set(QueueKeys.RISK_SYNCED_AT, "2026-11-20T00:00:00Z");
    }

    // ---------- 6. 동기화 표시 키 ----------

    @Test
    void 표시_키가_있으면_Redis_값만_쓰고_Oracle은_부르지_않는다() {
        synced();
        redis.opsForValue().set("risk:CI:ci-1", "30");
        redis.opsForValue().set("risk:DEVICE:dev-1", "70.5");
        assertThat(service(m -> 99).score(M)).isEqualTo(70.5);
        assertThat(sourceCalls.get()).isZero();
    }

    @Test
    void 표시_키가_있고_키가_없으면_0점이다() {
        synced();
        assertThat(service(m -> 99).score(M)).isZero();
        assertThat(sourceCalls.get()).isZero();
    }

    @Test
    void 표시_키가_없으면_Redis_값을_믿지_않고_Oracle에서_직접_조회한다() {
        redis.opsForValue().set("risk:CI:ci-1", "10"); // 캐시에 값이 있어도 표시 키가 없으면 원본을 본다
        assertThat(service(m -> 85).score(M)).isEqualTo(85.0);
        assertThat(sourceCalls.get()).isEqualTo(1);
    }

    @Test
    void 표시_키가_없고_Oracle_조회도_실패하면_최대_점수다() {
        assertThat(service(m -> { throw new IllegalStateException("db down"); }).score(M)).isEqualTo(100.0);
    }

    @Test
    void Oracle_조회값이_범위를_벗어나면_최대_점수다() {
        assertThat(service(m -> 250).score(M)).isEqualTo(100.0);
        assertThat(service(m -> -1).score(M)).isEqualTo(100.0);
        assertThat(service(m -> Double.NaN).score(M)).isEqualTo(100.0);
    }

    @Test
    void 제한_회원은_표시_키와_상관없이_최대_점수다() {
        MemberIdentity restricted = new MemberIdentity(2, "RESTRICTED", "ci-2", List.of(), List.of());
        assertThat(service(m -> 0).score(restricted)).isEqualTo(100.0);
        assertThat(sourceCalls.get()).isZero();
    }

    // ---------- 7. 값 형식 검증 ----------

    @Test
    void 형식이_잘못된_값은_100점으로_취급하고_예외를_내지_않는다() {
        synced();
        for (String bad : List.of("abc", "", " 50", "50 ", "-1", "101", "1000", "NaN", "Infinity", "1e2", "80d", "0x10",
                "{\"score\":80}", "80,5", "50.1234567")) {
            redis.opsForValue().set("risk:CI:ci-1", bad);
            assertThat(service(m -> 0).score(M)).as("값 [%s]", bad).isEqualTo(100.0);
        }
    }

    @Test
    void 정상_형식은_그대로_읽는다() {
        synced();
        for (String ok : List.of("0", "0.0", "7", "80.5", "99.999999", "100", "100.0")) {
            redis.opsForValue().set("risk:CI:ci-1", ok);
            assertThat(service(m -> 0).score(M)).as("값 [%s]", ok).isEqualTo(Double.parseDouble(ok));
        }
    }

    @Test
    void 오염된_값_하나가_있으면_다른_정상_값이_낮아도_100점이다() {
        synced();
        redis.opsForValue().set("risk:CI:ci-1", "5");
        redis.opsForValue().set("risk:PAYMENT:pay-1", "garbage");
        assertThat(service(m -> 0).score(M)).isEqualTo(100.0);
    }

    // ---------- 표시 키가 오래됐는지 (재동기화 실패 감지) ----------

    @Test
    void 표시_키가_기준보다_오래되면_오래됐다고_본다() {
        RiskPenaltyService svc = service(m -> 0);
        assertThat(svc.isSyncStale(clock.instant().minus(Duration.ofMinutes(31)).toString())).isTrue();
        assertThat(svc.isSyncStale(clock.instant().minus(Duration.ofHours(5)).toString())).isTrue();
    }

    @Test
    void 표시_키가_기준_안이면_오래되지_않았다() {
        RiskPenaltyService svc = service(m -> 0);
        assertThat(svc.isSyncStale(clock.instant().toString())).isFalse();
        assertThat(svc.isSyncStale(clock.instant().minus(Duration.ofMinutes(29)).toString())).isFalse();
    }

    @Test
    void 시각이_아닌_값과_미래_시각은_오래됐다고_보지_않는다() {
        RiskPenaltyService svc = service(m -> 0);
        assertThat(svc.isSyncStale("dev")).isFalse();      // 개발용 reset이 쓰는 값
        assertThat(svc.isSyncStale("")).isFalse();
        assertThat(svc.isSyncStale(clock.instant().plus(Duration.ofMinutes(5)).toString())).isFalse(); // 서버 시계 차이
    }

    @Test
    void 표시_키가_오래돼도_점수는_캐시_값을_그대로_쓴다() {
        redis.opsForValue().set(QueueKeys.RISK_SYNCED_AT, clock.instant().minus(Duration.ofHours(3)).toString());
        redis.opsForValue().set("risk:CI:ci-1", "42");
        assertThat(service(m -> 99).score(M)).isEqualTo(42.0);
        assertThat(sourceCalls.get()).isZero();            // 막거나 Oracle을 부르지 않고 경고만
    }
}
