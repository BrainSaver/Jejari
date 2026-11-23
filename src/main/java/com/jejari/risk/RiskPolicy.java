package com.jejari.risk;

import java.util.Map;

/**
 * 규칙별 문턱값과 배점. 기본값은 {@link #defaults()}, 운영 중 조정은 {@code app_policy}의 {@code risk.rule.*} 키로 한다.
 *
 * <p>잘못된 값(음수, NaN, 단계 역전, 범위 밖)은 생성 시 예외. 설정을 다시 읽을 때 예외가 나면 호출 쪽은
 * <b>이전 정책을 그대로 쓴다</b> (잘못된 설정 하나로 탐지가 꺼지지 않게).
 *
 * @param fastIntervalMs       이보다 짧은 요청 간격을 "빠른 클릭"으로 센다
 * @param fastClick            빠른 간격 개수 → FAST_CLICK
 * @param regularMinSamples    REGULAR_INTERVAL을 보려면 필요한 최소 간격 수
 * @param regularMaxCv         변동계수(표준편차/평균)가 이 이하면 기계처럼 일정한 것으로 본다
 * @param regularPoints        REGULAR_INTERVAL 배점
 * @param statsMaxGapMs        이보다 긴 간격은 "쉬었다 온 것"으로 보고 규칙성 통계에서 뺀다 (긴 공백으로 규칙성을 흐리는 회피 차단)
 * @param sameSeatRetry        같은 좌석 선점 실패 최대 횟수 → SAME_SEAT_RETRY
 * @param cancelAfterPayMin    구매 후 이 시간(분) 이내 취소를 "단시간 취소"로 센다
 * @param cancelAfterPay       단시간 취소 수 → CANCEL_AFTER_PAY
 * @param multiAccountWindowSec 이 시간(초) 안에 선점한 서로 다른 계정 수를 센다
 * @param multiAccountSync     창 안의 최대 서로 다른 계정 수 → MULTI_ACCOUNT_SYNC
 * @param cancelRatioMinPurchases 취소 비율을 보려면 필요한 최소 구매 수 (1건 사고 1건 취소한 정상 사용자 보호)
 * @param cancelRatio          취소 비율 → HIGH_CANCEL_RATIO
 * @param lastMinuteMin        출발 전 이 시간(분) 이내 취소를 "출발 직전 취소"로 센다
 * @param lastMinuteCancel     출발 직전 취소 수 → LAST_MINUTE_CANCEL
 * @param bulkPurchase         누적 구매 매수 → BULK_PURCHASE
 * @param linkedAccounts       연결 계정 수 → LINKED_ACCOUNTS
 * @param newAccountDays       가입 후 이 일수 미만이면 신규 계정
 * @param newAccountPoints     NEW_ACCOUNT 배점
 * @param linearMouse          화면 녹화의 기계적 마우스 이동 수 → LINEAR_MOUSE ({@link MouseMotion})
 * @param headlessHold         화면 녹화 없이 선점한 세션 수 → HEADLESS_HOLD
 * @param noSearchEntryPoints  조회 없이 대기열 진입 NO_SEARCH_ENTRY 배점 (LOW)
 * @param halfLifeDays         이전 점수 반감기 (일)
 * @param caseThreshold        최종 점수가 이 이상이면 케이스를 연다 (RISK_THRESHOLD)
 */
public record RiskPolicy(
        long fastIntervalMs, Tier fastClick,
        long regularMinSamples, double regularMaxCv, int regularPoints, long statsMaxGapMs,
        Tier sameSeatRetry,
        long cancelAfterPayMin, Tier cancelAfterPay,
        long multiAccountWindowSec, Tier multiAccountSync,
        long cancelRatioMinPurchases, Tier cancelRatio,
        long lastMinuteMin, Tier lastMinuteCancel,
        Tier bulkPurchase,
        Tier linkedAccounts,
        long newAccountDays, int newAccountPoints,
        Tier linearMouse, Tier headlessHold, int noSearchEntryPoints,
        double halfLifeDays, int caseThreshold) {

    public RiskPolicy {
        positive(fastIntervalMs, "fastIntervalMs");
        positive(regularMinSamples, "regularMinSamples");
        if (!Double.isFinite(regularMaxCv) || regularMaxCv <= 0 || regularMaxCv >= 1) {
            throw new IllegalArgumentException("regularMaxCv는 0과 1 사이여야 합니다");
        }
        points(regularPoints, "regularPoints");
        positive(statsMaxGapMs, "statsMaxGapMs");
        if (statsMaxGapMs <= fastIntervalMs) {
            throw new IllegalArgumentException("statsMaxGapMs는 fastIntervalMs보다 커야 합니다");
        }
        positive(cancelAfterPayMin, "cancelAfterPayMin");
        positive(multiAccountWindowSec, "multiAccountWindowSec");
        positive(cancelRatioMinPurchases, "cancelRatioMinPurchases");
        positive(lastMinuteMin, "lastMinuteMin");
        positive(newAccountDays, "newAccountDays");
        points(newAccountPoints, "newAccountPoints");
        points(noSearchEntryPoints, "noSearchEntryPoints");
        if (!Double.isFinite(halfLifeDays) || halfLifeDays <= 0) {
            throw new IllegalArgumentException("halfLifeDays는 0보다 커야 합니다");
        }
        if (caseThreshold < 1 || caseThreshold > 100) {
            throw new IllegalArgumentException("caseThreshold는 1~100이어야 합니다");
        }
        for (Tier t : new Tier[] {fastClick, sameSeatRetry, cancelAfterPay, multiAccountSync, cancelRatio,
                lastMinuteCancel, bulkPurchase, linkedAccounts, linearMouse,
                headlessHold}) {
            if (t == null) throw new IllegalArgumentException("배점 단계가 비어 있습니다");
        }
        if (cancelRatio.highThreshold() > 1) {
            throw new IllegalArgumentException("취소 비율 문턱값은 1 이하여야 합니다");
        }
        if (multiAccountSync.threshold() < 2) {
            throw new IllegalArgumentException("동시 계정 문턱값은 2 이상이어야 합니다 (1이면 모든 사용자가 걸림)");
        }
        if (linkedAccounts.threshold() < 2) {
            throw new IllegalArgumentException("연결 계정 문턱값은 2 이상이어야 합니다 (1이면 모든 사용자가 걸림)");
        }
    }

    private static void positive(long v, String name) {
        if (v <= 0) throw new IllegalArgumentException(name + "는 0보다 커야 합니다");
    }

    private static void points(int v, String name) {
        if (v < 0 || v > 100) throw new IllegalArgumentException(name + "는 0~100이어야 합니다");
    }

    /**
     * 기본값. 근거: 사람은 화면을 보고 누르므로 200ms 미만 간격이 20번 이상 나오기 어렵고,
     * 매진 좌석을 5번 넘게 다시 누르거나 30분 안에 두 번 이상 사고 무르는 일은 드물다.
     * k6 봇 시나리오(정답 라벨)로 탐지율 90%·오탐률 5%를 측정한 뒤 조정한다.
     * HEADLESS_HOLD 1 / 3: 사람은 화면으로만 선점하므로 한 번이라도 화면 없이 잡으면 의심 (녹화 장애 보호는 읽는 쪽).
     * NO_SEARCH_ENTRY 10 (LOW): 정상 경로로도 생겨 낮게.
     * LINEAR_MOUSE 8 / 20: 로컬 실측(2026-11-23) botb 세션마다 17~29, botc·사람 0~1
     */
    public static RiskPolicy defaults() {
        return new RiskPolicy(
                200, new Tier(20, 25, 60, 35),
                30, 0.05, 15, 600_000,
                new Tier(5, 20, 15, 30),
                30, new Tier(2, 15, 5, 25),
                60, new Tier(2, 30, 3, 40),
                4, new Tier(0.5, 10, 0.8, 20),
                180, new Tier(3, 10, 6, 15),
                new Tier(20, 10, 40, 20),
                new Tier(2, 10, 4, 20),
                7, 5,
                new Tier(8, 25, 20, 40),
                new Tier(1, 30, 3, 45), 10,
                30, 80);
    }

    /**
     * {@code app_policy}의 값으로 기본값을 덮어쓴다. 없는 키는 기본값 그대로.
     * 키: {@code risk.rule.<이름>} (단일 값) 또는 {@code risk.rule.<규칙>.t1|p1|t2|p2} (단계).
     * 숫자가 아니거나 범위를 벗어나면 예외 (메시지에 키 이름만).
     */
    public static RiskPolicy fromMap(Map<String, String> p) {
        RiskPolicy d = defaults();
        if (p == null || p.isEmpty()) return d;
        return new RiskPolicy(
                l(p, "fast_interval_ms", d.fastIntervalMs), tier(p, "fast_click", d.fastClick),
                l(p, "regular_min_samples", d.regularMinSamples), dbl(p, "regular_max_cv", d.regularMaxCv),
                i(p, "regular_points", d.regularPoints), l(p, "stats_max_gap_ms", d.statsMaxGapMs),
                tier(p, "same_seat_retry", d.sameSeatRetry),
                l(p, "cancel_after_pay_min", d.cancelAfterPayMin), tier(p, "cancel_after_pay", d.cancelAfterPay),
                l(p, "multi_account_window_sec", d.multiAccountWindowSec), tier(p, "multi_account_sync", d.multiAccountSync),
                l(p, "cancel_ratio_min_purchases", d.cancelRatioMinPurchases), tier(p, "cancel_ratio", d.cancelRatio),
                l(p, "last_minute_min", d.lastMinuteMin), tier(p, "last_minute_cancel", d.lastMinuteCancel),
                tier(p, "bulk_purchase", d.bulkPurchase),
                tier(p, "linked_accounts", d.linkedAccounts),
                l(p, "new_account_days", d.newAccountDays), i(p, "new_account_points", d.newAccountPoints),
                tier(p, "linear_mouse", d.linearMouse),
                tier(p, "headless_hold", d.headlessHold), i(p, "no_search_entry_points", d.noSearchEntryPoints),
                dbl(p, "half_life_days", d.halfLifeDays), i(p, "case_threshold", d.caseThreshold));
    }

    private static final String PREFIX = "risk.rule.";

    private static String raw(Map<String, String> p, String key) {
        String v = p.get(PREFIX + key);
        return v == null ? null : v.strip();
    }

    private static long l(Map<String, String> p, String key, long def) {
        String v = raw(p, key);
        if (v == null) return def;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PREFIX + key + " 값이 정수가 아닙니다");
        }
    }

    private static int i(Map<String, String> p, String key, int def) {
        long v = l(p, key, def);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(PREFIX + key + " 값이 범위를 벗어났습니다");
        }
        return (int) v;
    }

    private static double dbl(Map<String, String> p, String key, double def) {
        String v = raw(p, key);
        if (v == null) return def;
        double d;
        try {
            d = Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PREFIX + key + " 값이 숫자가 아닙니다");
        }
        if (!Double.isFinite(d)) { // "NaN", "Infinity"도 parseDouble은 받아 준다
            throw new IllegalArgumentException(PREFIX + key + " 값이 유한한 수가 아닙니다");
        }
        return d;
    }

    private static Tier tier(Map<String, String> p, String rule, Tier def) {
        return new Tier(
                dbl(p, rule + ".t1", def.threshold()), i(p, rule + ".p1", def.points()),
                dbl(p, rule + ".t2", def.highThreshold()), i(p, rule + ".p2", def.highPoints()));
    }
}
