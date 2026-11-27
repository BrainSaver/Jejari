package com.jejari.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 잘못된 입력·설정이 "조용히 탐지 0건"이 되지 않고 바로 실패하는지 */
class InputValidationTest {

    static final String H = "ef".repeat(32);
    static final Instant T = Instant.parse("2026-11-01T00:00:00Z");

    private static RiskSignals sig(String hash, long intervals, long fast, double mean, double sd,
                                   long purchases, long seats, long maxSingle, long cancels, long fastC, long lastC,
                                   Double avgC, long linked, long sync, Long age) {
        return new RiskSignals(SubjectType.CI, hash, T, intervals, fast, mean, sd, 0, purchases, seats, maxSingle,
                cancels, fastC, lastC, avgC, linked, sync, age);
    }

    private static RiskSignals ok() {
        return sig(H, 10, 2, 500, 100, 1, 2, 2, 1, 0, 0, 5.0, 1, 1, 10L);
    }

    @Test
    void 정상_입력은_통과() {
        assertThat(ok().cancelRatio()).isEqualTo(1.0);
    }

    @Test
    void 해시는_HMAC_형식만() {
        for (String bad : List.of("", "AB".repeat(32), "ab".repeat(31), "ab".repeat(33), "01012345678",
                "zz".repeat(32), "ab".repeat(31) + "a\n")) {
            assertThatThrownBy(() -> sig(bad, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, null))
                    .as(bad).isInstanceOf(IllegalArgumentException.class)
                    .message().doesNotContain("01012345678");
        }
        assertThatThrownBy(() -> sig(null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void NaN_무한대_음수는_거부() {
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1}) {
            assertThatThrownBy(() -> sig(H, 10, 2, bad, 1, 0, 0, 0, 0, 0, 0, null, 0, 0, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> sig(H, 10, 2, 1, bad, 0, 0, 0, 0, 0, 0, null, 0, 0, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> sig(H, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, bad, 0, 0, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> sig(H, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sig(H, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, -1, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sig(H, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, -1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sig(H, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, -1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 서로_모순인_값은_거부() {
        // 빠른 간격 > 전체 간격
        assertThatThrownBy(() -> sig(H, 5, 6, 1, 1, 0, 0, 0, 0, 0, 0, null, 0, 0, null)).isInstanceOf(IllegalArgumentException.class);
        // 간격 0건인데 평균 있음
        assertThatThrownBy(() -> sig(H, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, null)).isInstanceOf(IllegalArgumentException.class);
        // 단시간 취소 > 전체 취소
        assertThatThrownBy(() -> sig(H, 0, 0, 0, 0, 2, 2, 1, 1, 2, 0, 1.0, 0, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sig(H, 0, 0, 0, 0, 2, 2, 1, 1, 0, 2, 1.0, 0, 0, null)).isInstanceOf(IllegalArgumentException.class);
        // 1회 최대 > 누적, 구매 건수 > 매수
        assertThatThrownBy(() -> sig(H, 0, 0, 0, 0, 1, 2, 3, 0, 0, 0, null, 0, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sig(H, 0, 0, 0, 0, 3, 2, 1, 0, 0, 0, null, 0, 0, null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== 정책 ====================

    @Test
    void 기본_정책은_유효() {
        assertThat(RiskPolicy.defaults().caseThreshold()).isEqualTo(80);
        assertThat(RiskPolicy.fromMap(Map.of())).isEqualTo(RiskPolicy.defaults());
        assertThat(RiskPolicy.fromMap(null)).isEqualTo(RiskPolicy.defaults());
    }

    @Test
    void app_policy로_덮어쓴다() {
        RiskPolicy p = RiskPolicy.fromMap(Map.of(
                "risk.rule.fast_interval_ms", " 150 ",
                "risk.rule.fast_click.t1", "10", "risk.rule.fast_click.p1", "30",
                "risk.rule.case_threshold", "70",
                "risk.unrelated", "x"));
        assertThat(p.fastIntervalMs()).isEqualTo(150);
        assertThat(p.fastClick().threshold()).isEqualTo(10);
        assertThat(p.fastClick().points()).isEqualTo(30);
        assertThat(p.fastClick().highThreshold()).isEqualTo(60); // 없는 키는 기본값
        assertThat(p.caseThreshold()).isEqualTo(70);
        RiskPolicy d = RiskPolicy.fromMap(Map.of("risk.rule.declined_cards_min", "4", "risk.rule.declined_cards_points", "25"));
        assertThat(d.declinedCardsMin()).isEqualTo(4);
        assertThat(d.declinedCardsPoints()).isEqualTo(25);
        assertThat(RiskPolicy.defaults().declinedCardsMin()).isEqualTo(3);   // ERD v41 예시값과 같게
        assertThat(RiskPolicy.defaults().declinedCardsPoints()).isEqualTo(30);
    }

    @Test
    void 탐지를_꺼버리는_설정은_거부() {
        for (Map<String, String> bad : List.<Map<String, String>>of(
                Map.of("risk.rule.fast_click.t1", "NaN"),
                Map.of("risk.rule.fast_click.t1", "Infinity"),
                Map.of("risk.rule.fast_click.t1", "abc"),
                Map.of("risk.rule.fast_click.t1", "0"),
                Map.of("risk.rule.fast_click.t1", "-5"),
                Map.of("risk.rule.fast_click.t1", "100"),          // t2(60)보다 큼 → 단계 역전
                Map.of("risk.rule.fast_click.p1", "50"),           // p2(35)보다 큼 → 단계 역전
                Map.of("risk.rule.fast_click.p2", "101"),
                Map.of("risk.rule.fast_interval_ms", "0"),
                Map.of("risk.rule.fast_interval_ms", "99999999999999999999"),
                Map.of("risk.rule.stats_max_gap_ms", "100"),       // 빠른 기준보다 작음
                Map.of("risk.rule.regular_max_cv", "1.5"),
                Map.of("risk.rule.cancel_ratio.t2", "1.5"),
                Map.of("risk.rule.multi_account_sync.t1", "1"),    // 1계정 = 모든 사용자
                Map.of("risk.rule.linked_accounts.t1", "1"),
                Map.of("risk.rule.half_life_days", "0"),
                Map.of("risk.rule.case_threshold", "0"),
                Map.of("risk.rule.case_threshold", "101"),
                Map.of("risk.rule.new_account_points", "3000000000"),
                Map.of("risk.rule.declined_cards_min", "1"),       // 1장 = 카드번호 한 번 틀린 사람도
                Map.of("risk.rule.declined_cards_points", "101"))) {
            assertThatThrownBy(() -> RiskPolicy.fromMap(bad)).as(bad.toString()).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
