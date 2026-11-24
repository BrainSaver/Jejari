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
    void 여러_좌석의_재시도_실패를_합친다_첫_실패는_세지_않는다() {
        // 좌석마다 4번씩 3좌석: 최댓값은 4(문턱 5 아래)지만 재시도 실패 합은 3+3+3 = 9
        SignalCollector col = c();
        for (long seat = 1; seat <= 3; seat++) {
            for (int i = 0; i < 4; i++) col.seatHoldFailure(seat);
        }
        RiskSignals s = col.build();
        assertThat(s.maxSameSeatFailures()).isEqualTo(4);
        assertThat(s.seatRetryFailures()).isEqualTo(9);
        // 사람처럼 이미 잡힌 좌석을 한 번씩 만나고 다른 좌석으로 가면 0
        assertThat(c().seatHoldFailure(1).seatHoldFailure(2).seatHoldFailure(3).build().seatRetryFailures()).isZero();
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
                RiskRule.MULTI_ACCOUNT_SYNC, RiskRule.FAST_CLICK, RiskRule.SAME_SEAT_RETRY, RiskRule.REGULAR_INTERVAL);
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

    // ==================== 오탐 시험 보강 (2026-11-24) ====================

    @Nested
    class 가족_한_컴퓨터 {

        private SignalCollector device() {
            return new SignalCollector(policy, SubjectType.DEVICE, H, FROM);
        }

        /** 한 계정의 흐름: 조회 → 좌석표 → 선점 → 결제 (사람 속도), 끝난 시각을 돌려줌 */
        private Instant flow(SignalCollector col, long member, Instant start) {
            col.actor(member, start).actor(member, start.plusSeconds(4));
            col.seatHold(start.plusSeconds(8), member);
            col.actor(member, start.plusSeconds(12));
            return start.plusSeconds(12);
        }

        @Test
        void 차례로_로그인해_사면_기기_동시_선점이_아니다() {
            SignalCollector col = device();
            Instant end1 = flow(col, 1, NOW);
            Instant end2 = flow(col, 2, end1.plusSeconds(10)); // 로그아웃·로그인 10초
            flow(col, 3, end2.plusSeconds(5));
            assertThat(col.build().maxSyncAccounts()).isZero();
        }

        @Test
        void 계정을_빠르게_오가며_선점하면_그대로_잡힌다() {
            SignalCollector col = device();
            for (long m = 1; m <= 3; m++) col.actor(m, NOW.plusMillis(m * 100)); // 세 계정이 함께 준비
            col.seatHold(NOW.plusSeconds(1), 1).seatHold(NOW.plusSeconds(1).plusMillis(50), 2)
                    .seatHold(NOW.plusSeconds(1).plusMillis(90), 3);
            assertThat(col.build().maxSyncAccounts()).isEqualTo(3);
        }

        @Test
        void 차례로_돌려도_전환이_3초_안이면_잡힌다() {
            SignalCollector col = device();
            Instant end1 = flow(col, 1, NOW);
            flow(col, 2, end1.plusSeconds(1)); // 봇: 앞 계정이 끝나자마자
            assertThat(col.build().maxSyncAccounts()).isEqualTo(2);
        }

        @Test
        void 명의_대상은_그대로_창_안의_계정을_센다() {
            RiskSignals s = c().seatHold(NOW, 1).seatHold(NOW.plusSeconds(30), 2).build();
            assertThat(s.maxSyncAccounts()).isEqualTo(2);
        }
    }

    @Nested
    class 선점_버튼_두_번 {

        @Test
        void 첫_선점이_밀려_좌석표가_다시_그려진_직후_두_번째_요청은_세지_않는다() {
            SignalCollector col = c();
            Instant t = NOW;
            for (int i = 0; i < 2; i++) {
                col.seatView("s", t);
                col.holdStep("s", t.plusSeconds(3), t.plusSeconds(3).plusMillis(30), java.util.List.of(10L + i)); // 사람 속도, 409
                col.seatView("s", t.plusSeconds(3).plusMillis(60)); // 화면이 좌석표를 새로 받음
                col.holdStep("s", t.plusSeconds(3).plusMillis(100), t.plusSeconds(3).plusMillis(120),
                        java.util.List.of(10L + i)); // 같은 좌석 두 번째 클릭
                t = t.plusSeconds(10);
            }
            assertThat(col.build().inhumanSteps()).isZero();
        }

        @Test
        void 다른_좌석을_바로_누르면_센다() {
            SignalCollector col = c();
            col.seatView("s", NOW);
            col.holdStep("s", NOW.plusSeconds(3), NOW.plusSeconds(3), java.util.List.of(1L));
            col.seatView("s", NOW.plusSeconds(3).plusMillis(50));
            col.holdStep("s", NOW.plusSeconds(3).plusMillis(100), NOW.plusSeconds(3).plusMillis(100), java.util.List.of(2L));
            assertThat(col.build().inhumanSteps()).isEqualTo(1);
        }

        @Test
        void 두_번_누름의_기준은_첫_요청이라_결제_단계는_첫_응답부터_잰다() {
            SignalCollector col = c();
            col.holdStep("s", NOW, NOW.plusMillis(20), java.util.List.of(1L));
            col.holdStep("s", NOW.plusMillis(100), NOW.plusMillis(120), java.util.List.of(1L)); // 두 번째 (무시)
            col.payStep("s", NOW.plusMillis(400)); // 첫 응답부터 0.38초 → 셈
            assertThat(col.build().inhumanSteps()).isEqualTo(1);
        }

        @Test
        void 좌석표를_보자마자_선점한_첫_요청은_두_번_눌러도_센다() {
            SignalCollector col = c();
            col.seatView("s", NOW);
            col.holdStep("s", NOW.plusMillis(50), NOW.plusMillis(60), java.util.List.of(1L));
            col.holdStep("s", NOW.plusMillis(120), NOW.plusMillis(130), java.util.List.of(1L));
            assertThat(col.build().inhumanSteps()).isEqualTo(1);
        }
    }
}
