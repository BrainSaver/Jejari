package com.jejari.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * 계산 결과. risk_score 한 행과 (필요하면) 의심 케이스 생성에 쓴다.
 *
 * @param signals      입력 신호 (risk_score의 신호 컬럼에 그대로 저장)
 * @param ruleScore    이번 규칙 점수 합 (0~100, NUMBER(5,2))
 * @param finalScore   최종 점수 = max(규칙 점수, 감쇠한 이전 점수), 0~100
 * @param reasons      점수 근거 (점수 큰 순)
 * @param openCase     의심 케이스를 열어야 하나 (RISK-04)
 * @param triggerType  케이스를 연다면 suspicious_case.trigger_type (RISK_THRESHOLD / RULE), 아니면 null
 * @param calculatedAt 계산 시각
 */
public record RiskResult(
        RiskSignals signals, BigDecimal ruleScore, BigDecimal finalScore, List<Reason> reasons,
        boolean openCase, String triggerType, Instant calculatedAt) {

    public RiskResult {
        reasons = List.copyOf(reasons);
    }

    /** risk_score.cancel_ratio (NUMBER(4,3)) */
    public BigDecimal cancelRatioForDb() {
        return BigDecimal.valueOf(signals.cancelRatio()).setScale(3, RoundingMode.HALF_UP);
    }

    /** case_marker로 남길 근거 (marker 규칙만) */
    public List<Reason> markerReasons() {
        return reasons.stream().filter(r -> r.rule().marker()).toList();
    }

    /**
     * risk_score.reasons에 넣을 JSON. 직접 만든다: 값이 enum 이름과 숫자뿐이라 이스케이프할 문자열이 없고,
     * 외부 입력이 JSON에 들어갈 길이 구조적으로 없다.
     * 예: {@code [{"rule":"FAST_CLICK","observed":23,"threshold":20,"points":25,"severity":"MID"}]}
     */
    public String reasonsJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < reasons.size(); i++) {
            Reason r = reasons.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"rule\":\"").append(r.rule().name())
                    .append("\",\"observed\":").append(num(r.observed()))
                    .append(",\"threshold\":").append(num(r.threshold()))
                    .append(",\"points\":").append(r.points())
                    .append(",\"severity\":\"").append(r.severity().name()).append("\"}");
        }
        return sb.append(']').toString();
    }

    /** 유한한 수만 들어온다 (RiskSignals·Tier가 보장). 지수 표기 없이 소수 3자리까지. */
    private static String num(double d) {
        if (!Double.isFinite(d)) {
            throw new IllegalStateException("유한하지 않은 값은 JSON에 넣을 수 없습니다");
        }
        return BigDecimal.valueOf(d).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
