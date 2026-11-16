package com.jejari.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SignalCollectorTest {

    static final String H = "cd".repeat(32);
    static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");
    static final Instant FROM = NOW.minus(Duration.ofDays(30));

    private final RiskPolicy policy = RiskPolicy.defaults();
    private final RiskEngine engine = new RiskEngine(policy);

    private SignalCollector c() {
        return new SignalCollector(policy, SubjectType.CI, H, FROM);
    }

    // ==================== 집계 정확성 ====================

    @Test
    void 간격_평균_표준편차는_직접_계산한_값과_같다() {
        Random rnd = new Random(1);
        long[] xs = new long[5000];
        SignalCollector col = c();
        for (int i = 0; i < xs.length; i++) {
            xs[i] = rnd.nextInt(10_000);
            col.interval(xs[i]);
        }
        double mean = 0;
        for (long x : xs) mean += x;
        mean /= xs.length;
        double var = 0;
        for (long x : xs) var += (x - mean) * (x - mean);
        double sd = Math.sqrt(var / xs.length);

        RiskSignals s = col.build();
        assertThat(s.intervalCount()).isEqualTo(5000);
        assertThat(s.intervalMeanMs()).isCloseTo(mean, within(1e-6));
        assertThat(s.intervalStdDevMs()).isCloseTo(sd, within(1e-6));
    }

    @Test
    void 구매_취소_집계() {
        RiskSignals s = c().purchase(2).purchase(4).purchase(1)
                .userCancel(10, 5000).userCancel(31, 100).userCancel(500, -10)
                .build();
        assertThat(s.purchaseCount()).isEqualTo(3);
        assertThat(s.totalPurchaseSeats()).isEqualTo(7);
        assertThat(s.maxSinglePurchase()).isEqualTo(4);
        assertThat(s.userCancelCount()).isEqualTo(3);
        assertThat(s.fastCancelCount()).isEqualTo(1);        // 30분 이내: 10분
        assertThat(s.lastMinuteCancelCount()).isEqualTo(2);  // 3시간 이내: 100분, 출발 후(-10)
        assertThat(s.avgMinutesToCancel()).isCloseTo(180.33, within(0.01));
    }

    @Test
    void 같은_좌석_실패는_좌석별_최댓값() {
        RiskSignals s = c().seatHoldFailure(1).seatHoldFailure(2).seatHoldFailure(1).seatHoldFailure(1).build();
        assertThat(s.maxSameSeatFailures()).isEqualTo(3);
    }

    @Test
    void 동시_선점_창_안의_서로_다른_계정_수() {
        Instant t = NOW;
        RiskSignals s = c()
                .seatHold(t, 1).seatHold(t.plusSeconds(1), 1).seatHold(t.plusSeconds(2), 2)   // 1,2 → 2
                .seatHold(t.plusSeconds(200), 3).seatHold(t.plusSeconds(261), 4)            // 61초 차 → 따로
                .build();
        assertThat(s.maxSyncAccounts()).isEqualTo(2);
    }

    @Test
    void 창_경계_60초_정확히는_포함() {
        RiskSignals s = c().seatHold(NOW, 1).seatHold(NOW.plusSeconds(60), 2).build();
        assertThat(s.maxSyncAccounts()).isEqualTo(2);
        RiskSignals s2 = c().seatHold(NOW, 1).seatHold(NOW.plusSeconds(60).plusMillis(1), 2).build();
        assertThat(s2.maxSyncAccounts()).isEqualTo(1);
    }

    @Test
    void 계정_나이() {
        RiskSignals s = c().oldestAccountCreatedAt(NOW.minus(Duration.ofDays(3)), NOW).build();
        assertThat(s.accountAgeDays()).isEqualTo(3);
        RiskSignals future = c().oldestAccountCreatedAt(NOW.plus(Duration.ofDays(3)), NOW).build();
        assertThat(future.accountAgeDays()).isZero(); // 시계 오류는 신규로
    }

    @Test
    void 잘못된_이벤트는_거부() {
        assertThatThrownBy(() -> c().purchase(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c().userCancel(-1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c().linkedAccounts(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c().seatHold(null, 1)).isInstanceOf(NullPointerException.class);
    }

    // ==================== 회피 시도 ====================

    @Nested
    class 회피 {

        @Test
        void 빠른_클릭_뒤에_느린_요청을_잔뜩_섞어도_FAST_CLICK은_그대로() {
            SignalCollector col = c();
            for (int i = 0; i < 25; i++) col.interval(80);
            for (int i = 0; i < 100_000; i++) col.interval(3_000 + (i % 7) * 900); // 평균을 사람처럼 만들기
            RiskResult r = engine.score(col.build(), null, NOW);
            assertThat(r.reasons()).extracting(Reason::rule).contains(RiskRule.FAST_CLICK);
        }

        @Test
        void 고정_간격_매크로가_긴_공백을_끼워도_REGULAR_INTERVAL은_그대로() {
            SignalCollector col = c();
            for (int i = 0; i < 100; i++) {
                col.interval(1000);
                if (i % 10 == 0) col.interval(3_600_000); // 1시간 공백 → 통계에서 빠짐
            }
            RiskResult r = engine.score(col.build(), null, NOW);
            assertThat(r.reasons()).extracting(Reason::rule).contains(RiskRule.REGULAR_INTERVAL);
        }

        @Test
        void 서버_시계_차이로_생긴_음수_간격은_빠른_간격으로_센다() {
            SignalCollector col = c();
            for (int i = 0; i < 20; i++) col.interval(-5);
            assertThat(col.build().fastIntervalCount()).isEqualTo(20);
        }

        @Test
        void 같은_계정이_여러_번_선점해도_다계정이_아니다() {
            SignalCollector col = c();
            for (int i = 0; i < 1000; i++) col.seatHold(NOW.plusMillis(i), 7);
            assertThat(col.build().maxSyncAccounts()).isEqualTo(1);
        }

        @Test
        void 순서가_뒤바뀐_선점_시각도_다계정을_놓치지_않는다() {
            RiskSignals s = c().seatHold(NOW.plusSeconds(10), 1).seatHold(NOW, 2).build();
            assertThat(s.maxSyncAccounts()).isEqualTo(2);
        }

        @Test
        void 좌석_수를_폭증시켜도_메모리_상한이_있고_이미_센_최댓값은_유지() {
            SignalCollector col = c();
            for (int i = 0; i < 6; i++) col.seatHoldFailure(-1); // 먼저 같은 좌석 6번
            for (long seat = 0; seat < SignalCollector.MAX_TRACKED_SEATS + 10_000; seat++) col.seatHoldFailure(seat);
            assertThat(col.build().maxSameSeatFailures()).isEqualTo(6);
        }

        @Test
        void 선점_이벤트를_폭증시켜도_창_상한이_있다() {
            SignalCollector col = c();
            for (int i = 0; i < SignalCollector.MAX_WINDOW_EVENTS + 5_000; i++) col.seatHold(NOW, i % 3);
            assertThat(col.build().maxSyncAccounts()).isEqualTo(3);
        }
    }

    @Test
    void 봇_시나리오_전체_흐름() {
        // 0.1초 간격 클릭 30번, 같은 좌석 6번 실패, 계정 3개로 동시 선점
        SignalCollector col = c().linkedAccounts(3).oldestAccountCreatedAt(NOW.minus(Duration.ofDays(2)), NOW);
        for (int i = 0; i < 30; i++) col.interval(100);
        for (int i = 0; i < 6; i++) col.seatHoldFailure(42);
        col.seatHold(NOW, 1).seatHold(NOW.plusSeconds(1), 2).seatHold(NOW.plusSeconds(2), 3);

        RiskResult r = engine.score(col.build(), null, NOW);
        assertThat(r.finalScore()).isEqualByComparingTo("100");
        assertThat(r.triggerType()).isEqualTo("RISK_THRESHOLD");
        assertThat(r.markerReasons()).extracting(Reason::rule).containsExactlyInAnyOrder(
                RiskRule.MULTI_ACCOUNT_SYNC, RiskRule.FAST_CLICK, RiskRule.SAME_SEAT_RETRY);
    }

    @Test
    void 정상_사용자_시나리오는_0점() {
        // 검색 → 좌석 보기 → 선점 → 결제, 사람 속도(1.5~8초), 계정 1개, 가입 1년
        Random rnd = new Random(7);
        SignalCollector col = c().linkedAccounts(1).oldestAccountCreatedAt(NOW.minus(Duration.ofDays(365)), NOW);
        for (int i = 0; i < 200; i++) col.interval(1500 + rnd.nextInt(6500));
        col.seatHoldFailure(42).seatHold(NOW, 1).purchase(2);
        RiskResult r = engine.score(col.build(), null, NOW);
        assertThat(r.finalScore()).isEqualByComparingTo("0");
        assertThat(r.openCase()).isFalse();
    }
}
