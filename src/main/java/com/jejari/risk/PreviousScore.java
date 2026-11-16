package com.jejari.risk;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 지난번 계산 결과 (risk_score 현재 행). 처음 계산하는 대상이면 null을 넘긴다.
 *
 * @param finalScore   지난 최종 점수
 * @param calculatedAt 지난 계산 시각
 * @param reviewStatus risk_score.review_status (NONE / PENDING / CONFIRMED / CLEARED)
 * @param clearedAt    관리자가 CLEAR(오탐 해제)한 시각. CLEARED가 아니면 null
 */
public record PreviousScore(BigDecimal finalScore, Instant calculatedAt, ReviewStatus reviewStatus, Instant clearedAt) {

    public enum ReviewStatus { NONE, PENDING, CONFIRMED, CLEARED }

    public PreviousScore {
        if (finalScore == null || finalScore.signum() < 0 || finalScore.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("이전 점수는 0~100이어야 합니다");
        }
        if (calculatedAt == null || reviewStatus == null) {
            throw new IllegalArgumentException("이전 계산 시각·검토 상태가 없습니다");
        }
        if ((reviewStatus == ReviewStatus.CLEARED) != (clearedAt != null)) {
            throw new IllegalArgumentException("CLEARED일 때만, 그리고 반드시 clearedAt이 있어야 합니다");
        }
    }
}
