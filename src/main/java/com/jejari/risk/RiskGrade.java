package com.jejari.risk;

import java.math.BigDecimal;

/**
 * 위험 점수 등급 (기준표: docs/강다인/features/위험점수-규칙.md "등급 기준표").
 *
 * <pre>
 * 정상 NORMAL   경고 시작 미만                                   신규 계정 기본 점수 등. 대기열 순번만 점수만큼(초) 뒤로
 * 경고 WARNING  risk.rule.warning_threshold(30) 이상, 위험 미만  규칙 하나 이상이 걸림. 관리자 목록에서 눈여겨봄
 * 위험 DANGER   risk.rule.case_threshold(80) 이상               케이스 자동 생성 · 결제 단계 차단(PaymentRiskGuard)
 * </pre>
 * 위험의 시작점은 케이스·결제 차단과 같은 값(case_threshold)을 쓴다. 따로 두면 "위험인데 결제는 됨"처럼 어긋난다.
 */
public enum RiskGrade {
    NORMAL, WARNING, DANGER;

    public static RiskGrade of(BigDecimal score, RiskPolicy policy) {
        if (score == null) return NORMAL;
        if (score.compareTo(BigDecimal.valueOf(policy.caseThreshold())) >= 0) return DANGER;
        if (score.compareTo(BigDecimal.valueOf(policy.warningThreshold())) >= 0) return WARNING;
        return NORMAL;
    }
}
