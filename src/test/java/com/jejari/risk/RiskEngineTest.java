package com.jejari.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jejari.risk.PreviousScore.ReviewStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RiskEngineTest {

    static final String H = "ab".repeat(32);
    static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");
    static final Instant FROM = NOW.minus(Duration.ofDays(30));
    /** 숫자 지수 표기 (1E+12). JSON 소비 쪽(관리자 화면)이 다르게 읽을 수 있어 금지 */
    static final String EXPONENT = "\\d[eE][+-]?\\d";

    private final RiskEngine engine = new RiskEngine(RiskPolicy.defaults());

    /** 모든 필드를 바꿔 끼우기 쉬운 빌더 */
    static final class S {
        SubjectType type = SubjectType.CI;
        Instant from = FROM;
        long intervals, fast, sameSeat, purchases, seats, maxSingle, cancels, fastCancels, lastMinute, linked, sync;
        double mean, sd;
        Long age;

        S intervals(long n, long fast, double mean, double sd) {
            this.intervals = n; this.fast = fast; this.mean = mean; this.sd = sd; return this;
        }
        S sameSeat(long n) { sameSeat = n; return this; }
        S purchases(long n, long seats) { purchases = n; this.seats = seats; maxSingle = Math.min(seats, Math.max(1, seats / Math.max(1, n))); return this; }
        S cancels(long all, long fast, long lastMinute) { cancels = all; fastCancels = fast; this.lastMinute = lastMinute; return this; }
        S linked(long n) { linked = n; return this; }
        S sync(long n) { sync = n; return this; }
        S age(Long d) { age = d; return this; }
        S from(Instant f) { from = f; return this; }

        RiskSignals build() {
            return new RiskSignals(type, H, from, intervals, fast, mean, sd, sameSeat, purchases, seats, maxSingle,
                    cancels, fastCancels, lastMinute, cancels == 0 ? null : 10.0, linked, sync, age);
        }
    }

    static S s() {
        return new S();
    }

    private RiskResult score(S s) {
        return engine.score(s.build(), null, NOW);
    }

    // ==================== 규칙별 경계값 ====================

    @Nested
    class 규칙 {

        @Test
        void 신호가_없으면_0점_케이스_없음() {
            RiskResult r = score(s());
            assertThat(r.finalScore()).isEqualByComparingTo("0");
            assertThat(r.reasons()).isEmpty();
            assertThat(r.openCase()).isFalse();
            assertThat(r.triggerType()).isNull();
            assertThat(r.reasonsJson()).isEqualTo("[]");
        }

        @Test
        void FAST_CLICK_19회는_없음_20회_MID_60회_HIGH() {
            assertThat(score(s().intervals(100, 19, 500, 300)).reasons()).isEmpty();
            RiskResult mid = score(s().intervals(100, 20, 500, 300));
            assertThat(mid.reasons()).singleElement().satisfies(r -> {
                assertThat(r.rule()).isEqualTo(RiskRule.FAST_CLICK);
                assertThat(r.points()).isEqualTo(25);
                assertThat(r.severity()).isEqualTo(Severity.MID);
            });
            assertThat(score(s().intervals(100, 60, 500, 300)).reasons().get(0).points()).isEqualTo(35);
            assertThat(score(s().intervals(100, 60, 500, 300)).reasons().get(0).severity()).isEqualTo(Severity.HIGH);
        }

        @Test
        void SAME_SEAT_RETRY() {
            assertThat(score(s().sameSeat(4)).reasons()).isEmpty();
            assertThat(score(s().sameSeat(5)).ruleScore()).isEqualByComparingTo("20");
            assertThat(score(s().sameSeat(15)).ruleScore()).isEqualByComparingTo("30");
        }

        @Test
        void CANCEL_AFTER_PAY() {
            assertThat(score(s().purchases(2, 2).cancels(1, 1, 0)).reasons()).isEmpty();
            assertThat(score(s().purchases(2, 2).cancels(2, 2, 0)).reasons())
                    .extracting(Reason::rule).contains(RiskRule.CANCEL_AFTER_PAY);
        }

        @Test
        void MULTI_ACCOUNT_SYNC_1계정은_정상_2계정부터() {
            assertThat(score(s().sync(1)).reasons()).isEmpty();
            assertThat(score(s().sync(2)).ruleScore()).isEqualByComparingTo("30");
            assertThat(score(s().sync(3)).ruleScore()).isEqualByComparingTo("40");
        }

        @Test
        void REGULAR_INTERVAL_기계처럼_일정한_간격() {
            assertThat(score(s().intervals(30, 0, 1000, 40)).reasons())
                    .extracting(Reason::rule).containsExactly(RiskRule.REGULAR_INTERVAL);
            assertThat(score(s().intervals(29, 0, 1000, 40)).reasons()).isEmpty(); // 표본 부족
            assertThat(score(s().intervals(30, 0, 1000, 300)).reasons()).isEmpty(); // 사람 수준 변동
        }

        @Test
        void 취소_비율은_구매가_4건_이상일_때만_본다() {
            // 1건 사고 1건 취소한 정상 사용자 = 비율 100%지만 점수 없음
            assertThat(score(s().purchases(1, 1).cancels(1, 0, 0)).reasons()).isEmpty();
            assertThat(score(s().purchases(4, 4).cancels(2, 0, 0)).reasons())
                    .extracting(Reason::rule).containsExactly(RiskRule.HIGH_CANCEL_RATIO);
        }

        @Test
        void 연결_계정_1개는_정상() {
            assertThat(score(s().linked(1)).reasons()).isEmpty();
            assertThat(score(s().linked(2)).ruleScore()).isEqualByComparingTo("10");
        }

        @Test
        void 신규_계정은_조금_높게_시작_나이_모르면_없음() {
            assertThat(score(s().age(0L)).ruleScore()).isEqualByComparingTo("5");
            assertThat(score(s().age(6L)).ruleScore()).isEqualByComparingTo("5");
            assertThat(score(s().age(7L)).reasons()).isEmpty();
            assertThat(score(s().age(null)).reasons()).isEmpty();
        }

        @Test
        void 합계는_100에서_자른다() {
            RiskResult r = score(s().intervals(1000, 900, 50, 1).sameSeat(100).purchases(10, 50)
                    .cancels(10, 10, 10).linked(10).sync(10).age(0L));
            assertThat(r.ruleScore()).isEqualByComparingTo("100");
            assertThat(r.finalScore()).isEqualByComparingTo("100");
        }

        @Test
        void 근거는_점수_큰_순() {
            RiskResult r = score(s().sameSeat(5).sync(3).age(1L));
            assertThat(r.reasons()).extracting(Reason::rule)
                    .containsExactly(RiskRule.MULTI_ACCOUNT_SYNC, RiskRule.SAME_SEAT_RETRY, RiskRule.NEW_ACCOUNT);
        }
    }

    // ==================== 케이스 (RISK-04) ====================

    @Nested
    class 케이스 {

        @Test
        void marker_규칙이_하나라도_걸리면_RULE() {
            RiskResult r = score(s().sameSeat(5));
            assertThat(r.openCase()).isTrue();
            assertThat(r.triggerType()).isEqualTo("RULE");
            assertThat(r.markerReasons()).extracting(Reason::rule).containsExactly(RiskRule.SAME_SEAT_RETRY);
        }

        @Test
        void 보조_신호만으로는_케이스를_열지_않는다() {
            RiskResult r = score(s().purchases(10, 40).linked(4));
            assertThat(r.finalScore()).isEqualByComparingTo("40");
            assertThat(r.openCase()).isFalse();
        }

        @Test
        void 점수_80_이상이면_RISK_THRESHOLD() {
            RiskResult r = score(s().intervals(100, 60, 100, 50).sameSeat(15).purchases(4, 4).cancels(4, 4, 0));
            assertThat(r.finalScore()).isGreaterThanOrEqualTo(new BigDecimal("80"));
            assertThat(r.triggerType()).isEqualTo("RISK_THRESHOLD");
        }
    }

    // ==================== 이전 점수 감쇠·CLEAR ====================

    @Nested
    class 감쇠 {

        private PreviousScore prev(String score, Instant at) {
            return new PreviousScore(new BigDecimal(score), at, ReviewStatus.NONE, null);
        }

        @Test
        void 반감기_30일이면_절반() {
            RiskResult r = engine.score(s().build(), prev("80", NOW.minus(Duration.ofDays(30))), NOW);
            assertThat(r.finalScore()).isEqualByComparingTo("40");
            assertThat(engine.score(s().build(), prev("80", NOW.minus(Duration.ofDays(60))), NOW).finalScore())
                    .isEqualByComparingTo("20");
        }

        @Test
        void 잠깐_조용히_있어도_점수는_거의_그대로() {
            RiskResult r = engine.score(s().build(), prev("90", NOW.minus(Duration.ofHours(1))), NOW);
            assertThat(r.finalScore()).isGreaterThan(new BigDecimal("89.9"));
            assertThat(r.triggerType()).isEqualTo("RISK_THRESHOLD"); // 감쇠 점수로도 80 이상이면 케이스 유지
        }

        @Test
        void 이전_점수가_미래_시각이어도_부풀지_않는다() {
            RiskResult r = engine.score(s().build(), prev("50", NOW.plus(Duration.ofDays(3650))), NOW);
            assertThat(r.finalScore()).isEqualByComparingTo("50");
        }

        @Test
        void 새_증거가_더_크면_새_점수() {
            RiskResult r = engine.score(s().sync(3).build(), prev("10", NOW.minus(Duration.ofDays(1))), NOW);
            assertThat(r.finalScore()).isEqualByComparingTo("40");
        }

        @Test
        void CLEAR된_대상은_이전_점수를_버린다() {
            Instant cleared = NOW.minus(Duration.ofMinutes(5));
            PreviousScore p = new PreviousScore(new BigDecimal("95"), cleared, ReviewStatus.CLEARED, cleared);
            RiskResult r = engine.score(s().from(cleared).build(), p, NOW);
            assertThat(r.finalScore()).isEqualByComparingTo("0");
        }

        @Test
        void CLEAR_이전_증거로_다시_계산하면_예외() {
            Instant cleared = NOW.minus(Duration.ofMinutes(5));
            PreviousScore p = new PreviousScore(new BigDecimal("95"), cleared, ReviewStatus.CLEARED, cleared);
            assertThatThrownBy(() -> engine.score(s().from(cleared.minusSeconds(1)).sync(3).build(), p, NOW))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CLEAR");
        }

        @Test
        void 이전_점수_형식_검사() {
            assertThatThrownBy(() -> new PreviousScore(new BigDecimal("100.01"), NOW, ReviewStatus.NONE, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PreviousScore(new BigDecimal("-1"), NOW, ReviewStatus.NONE, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PreviousScore(BigDecimal.ONE, NOW, ReviewStatus.CLEARED, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PreviousScore(BigDecimal.ONE, NOW, ReviewStatus.NONE, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================== 무작위 성질 검사 ====================

    @Nested
    class 성질 {

        private final Random rnd = new Random(20261120L); // 고정 시드: 실패하면 같은 입력으로 재현

        private S random() {
            S s = s();
            long n = rnd.nextInt(200);
            s.intervals(n, n == 0 ? 0 : rnd.nextInt((int) n + 1), n == 0 ? 0 : rnd.nextDouble() * 5000,
                    n == 0 ? 0 : rnd.nextDouble() * 3000);
            s.sameSeat(rnd.nextInt(30));
            long p = rnd.nextInt(15);
            s.purchases(p, p + rnd.nextInt(30));
            long c = rnd.nextInt(15);
            s.cancels(c, c == 0 ? 0 : rnd.nextInt((int) c + 1), c == 0 ? 0 : rnd.nextInt((int) c + 1));
            s.linked(rnd.nextInt(8));
            s.sync(rnd.nextInt(6));
            s.age(rnd.nextBoolean() ? null : (long) rnd.nextInt(30));
            return s;
        }

        @Test
        void 어떤_입력이든_점수는_0_이상_100_이하() {
            for (int i = 0; i < 20_000; i++) {
                RiskResult r = score(random());
                assertThat(r.finalScore()).isBetween(BigDecimal.ZERO, new BigDecimal("100"));
                assertThat(r.ruleScore()).isBetween(BigDecimal.ZERO, new BigDecimal("100"));
                assertThat(r.reasonsJson()).matches("^\\[(\\{[^{}]*\\})?(,\\{[^{}]*\\})*\\]$").doesNotContain("NaN", "Infinity").doesNotContainPattern(EXPONENT);
            }
        }

        /** 나쁜 신호 하나를 늘렸을 때 점수가 내려가면 봇이 "더 나쁘게 행동해서" 점수를 낮출 수 있다는 뜻 */
        @Test
        void 나쁜_신호를_늘려도_점수가_내려가지_않는다() {
            for (int i = 0; i < 20_000; i++) {
                S base = random();
                BigDecimal before = score(base).finalScore();
                for (UnaryOperator<S> worse : java.util.List.<UnaryOperator<S>>of(
                        x -> x.intervals(x.intervals + 1, x.fast + 1, x.mean, x.sd),
                        x -> x.sameSeat(x.sameSeat + 1),
                        x -> x.cancels(x.cancels + 1, x.fastCancels + 1, x.lastMinute),
                        x -> x.cancels(x.cancels + 1, x.fastCancels, x.lastMinute + 1),
                        x -> x.cancels(x.cancels + 1, x.fastCancels, x.lastMinute),
                        x -> { x.seats++; return x; },
                        x -> x.linked(x.linked + 1),
                        x -> x.sync(x.sync + 1),
                        x -> x.age(x.age == null ? null : Math.max(0, x.age - 1)))) {
                    S copy = copyOf(base);
                    BigDecimal after = score(worse.apply(copy)).finalScore();
                    assertThat(after).as("시드 반복 %d", i).isGreaterThanOrEqualTo(before);
                }
            }
        }

        private S copyOf(S o) {
            S c = s();
            c.intervals(o.intervals, o.fast, o.mean, o.sd);
            c.sameSeat = o.sameSeat; c.purchases = o.purchases; c.seats = o.seats; c.maxSingle = o.maxSingle;
            c.cancels = o.cancels; c.fastCancels = o.fastCancels; c.lastMinute = o.lastMinute;
            c.linked = o.linked; c.sync = o.sync; c.age = o.age;
            return c;
        }
    }

    // ==================== 근거 JSON ====================

    @Test
    void 근거_JSON은_코드와_숫자만() {
        RiskResult r = score(s().intervals(100, 23, 500, 300).purchases(4, 4).cancels(3, 0, 0));
        assertThat(r.reasonsJson()).isEqualTo(
                "[{\"rule\":\"FAST_CLICK\",\"observed\":23,\"threshold\":20,\"points\":25,\"severity\":\"MID\"},"
                        + "{\"rule\":\"HIGH_CANCEL_RATIO\",\"observed\":0.75,\"threshold\":0.5,\"points\":10,\"severity\":\"MID\"}]");
        assertThat(r.cancelRatioForDb()).isEqualByComparingTo("0.750");
    }

    @Test
    void 아주_큰_관측값도_지수_표기_없이() {
        RiskResult r = score(s().sameSeat(1_000_000_000_000L));
        assertThat(r.reasonsJson()).contains("\"observed\":1000000000000").doesNotContainPattern(EXPONENT);
    }
}
