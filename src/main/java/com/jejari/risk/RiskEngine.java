package com.jejari.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 규칙 기반 위험 점수 (RISK-02).
 *
 * <pre>
 * 규칙 점수 = Σ 걸린 규칙의 배점   (최대 100으로 자름)
 * 이전 점수 = 지난 최종 점수 × 0.5^(경과 일 / 반감기)   (CLEARED면 0)
 * 최종 점수 = max(규칙 점수, 이전 점수)
 * 케이스    = 최종 점수 ≥ 80 → RISK_THRESHOLD, 아니면 marker 규칙이 하나라도 걸림 → RULE
 * </pre>
 *
 * <h2>보안 성질 (테스트로 확인)</h2>
 * <ul>
 *   <li><b>단조성</b>: 나쁜 신호(빠른 클릭 수, 실패 수, 취소 수, 계정 수 …)를 늘려서 점수가 내려가는 경우는 없다.
 *       평균이 아니라 개수로 세므로 정상 요청을 섞어 희석할 수 없다.</li>
 *   <li><b>범위</b>: 어떤 입력이든 0~100. 이전 점수가 미래 시각이어도 부풀지 않는다.</li>
 *   <li><b>조용한 실패 없음</b>: NaN·음수·모순 입력은 {@link RiskSignals}가 거부한다.</li>
 *   <li><b>CLEAR 우회 방지</b>: 관리자가 오탐 해제한 대상을 해제 이전 증거로 다시 점수 매기면 예외.</li>
 *   <li><b>점수 세탁 방지</b>: 잠깐 조용히 있어도 이전 점수가 반감기로만 줄어든다 (30일 지나야 절반).</li>
 * </ul>
 *
 * 상태가 없어 스레드 안전하다.
 */
public final class RiskEngine {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final double MS_PER_DAY = 86_400_000d;

    private final RiskPolicy policy;

    public RiskEngine(RiskPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public RiskPolicy policy() {
        return policy;
    }

    /**
     * @param s    집계 신호
     * @param prev 지난 결과 (처음이면 null)
     * @param now  계산 시각
     */
    public RiskResult score(RiskSignals s, PreviousScore prev, Instant now) {
        Objects.requireNonNull(s, "signals");
        Objects.requireNonNull(now, "now");
        boolean cleared = prev != null && prev.reviewStatus() == PreviousScore.ReviewStatus.CLEARED;
        if (cleared && s.windowFrom().isBefore(prev.clearedAt())) {
            // 해제 이전 증거로 다시 계산하면 관리자가 지운 점수가 다음 배치(1분)에 그대로 되살아난다
            throw new IllegalArgumentException("CLEAR된 대상은 clearedAt 이후 이벤트만 집계해야 합니다");
        }

        List<Reason> reasons = new ArrayList<>();
        tier(reasons, RiskRule.FAST_CLICK, policy.fastClick(), s.fastIntervalCount());
        // 한 좌석 최대 실패와, 여러 좌석에 걸친 재시도 실패 합 중 큰 쪽. 좌석마다 4번씩(문턱 5 아래)으로 나눠도
        // 두 좌석이면 재시도 실패 6이라 걸린다 (조영준 경계 측정 2026-11-18)
        tier(reasons, RiskRule.SAME_SEAT_RETRY, policy.sameSeatRetry(),
                Math.max(s.maxSameSeatFailures(), s.seatRetryFailures()));
        tier(reasons, RiskRule.CANCEL_AFTER_PAY, policy.cancelAfterPay(), s.fastCancelCount());
        tier(reasons, RiskRule.MULTI_ACCOUNT_SYNC, policy.multiAccountSync(), s.maxSyncAccounts());
        tier(reasons, RiskRule.LINEAR_MOUSE, policy.linearMouse(), s.mechanicalMouseMoves());
        tier(reasons, RiskRule.HEADLESS_HOLD, policy.headlessHold(), s.headlessHoldSessions());
        if (s.queueWithoutSearch() && policy.noSearchEntryPoints() > 0) {
            reasons.add(new Reason(RiskRule.NO_SEARCH_ENTRY, 1, 1, policy.noSearchEntryPoints(), Severity.LOW));
        }

        if (s.intervalCount() >= policy.regularMinSamples() && s.intervalCv() <= policy.regularMaxCv()
                && policy.regularPoints() > 0) {
            reasons.add(new Reason(RiskRule.REGULAR_INTERVAL, s.intervalCv(), policy.regularMaxCv(),
                    policy.regularPoints(), Severity.MID));
        }
        if (s.purchaseCount() >= policy.cancelRatioMinPurchases()) {
            tier(reasons, RiskRule.HIGH_CANCEL_RATIO, policy.cancelRatio(), s.cancelRatio());
        }
        tier(reasons, RiskRule.LAST_MINUTE_CANCEL, policy.lastMinuteCancel(), s.lastMinuteCancelCount());
        tier(reasons, RiskRule.BULK_PURCHASE, policy.bulkPurchase(), s.totalPurchaseSeats());
        tier(reasons, RiskRule.LINKED_ACCOUNTS, policy.linkedAccounts(), s.linkedAccountCount());
        if (s.accountAgeDays() != null && s.accountAgeDays() < policy.newAccountDays() && policy.newAccountPoints() > 0) {
            reasons.add(new Reason(RiskRule.NEW_ACCOUNT, s.accountAgeDays(), policy.newAccountDays(),
                    policy.newAccountPoints(), Severity.LOW));
        }
        reasons.sort(Comparator.comparingInt(Reason::points).reversed().thenComparing(r -> r.rule().ordinal()));

        long sum = 0;
        for (Reason r : reasons) sum += r.points();
        BigDecimal ruleScore = BigDecimal.valueOf(Math.min(100, sum)).setScale(2, RoundingMode.UNNECESSARY);

        BigDecimal decayed = BigDecimal.ZERO;
        if (prev != null && !cleared) {
            decayed = decay(prev.finalScore(), prev.calculatedAt(), now);
        }
        BigDecimal finalScore = ruleScore.max(decayed).min(HUNDRED).setScale(2, RoundingMode.HALF_UP);

        boolean markerHit = reasons.stream().anyMatch(r -> r.rule().marker());
        String trigger = null;
        if (finalScore.compareTo(BigDecimal.valueOf(policy.caseThreshold())) >= 0) {
            trigger = "RISK_THRESHOLD";
        } else if (markerHit) {
            trigger = "RULE";
        }
        return new RiskResult(s, ruleScore, finalScore, reasons, trigger != null, trigger, now);
    }

    private static void tier(List<Reason> out, RiskRule rule, Tier t, double observed) {
        int p = t.pointsFor(observed);
        if (p > 0) {
            double hit = observed >= t.highThreshold() ? t.highThreshold() : t.threshold();
            out.add(new Reason(rule, observed, hit, p, t.severityFor(observed)));
        }
    }

    /** 반감기 감쇠. 경과 시간이 음수(시계 오류·미래 시각)면 0으로 봐서 점수가 커지지 않게 한다. */
    BigDecimal decay(BigDecimal score, Instant from, Instant now) {
        long ms = Math.max(0, Duration.between(from, now).toMillis());
        double factor = Math.pow(0.5, (ms / MS_PER_DAY) / policy.halfLifeDays());
        double v = score.doubleValue() * factor;
        if (!Double.isFinite(v) || v < 0) v = 0;
        return BigDecimal.valueOf(Math.min(100, v)).setScale(2, RoundingMode.HALF_UP);
    }
}
