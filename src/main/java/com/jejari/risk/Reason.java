package com.jejari.risk;

/**
 * 점수 근거 한 줄. 관리자 화면(ADM-04·05)과 risk_score.reasons에 그대로 나간다.
 * 코드와 숫자만 담는다 (입력 문자열·개인정보가 들어갈 자리가 없다).
 *
 * @param rule      규칙 코드
 * @param observed  관측값 (예: 빠른 간격 23회, 취소 비율 0.62)
 * @param threshold 걸린 단계의 문턱값
 * @param points    더한 점수
 * @param severity  case_marker.severity
 */
public record Reason(RiskRule rule, double observed, double threshold, int points, Severity severity) {
}
