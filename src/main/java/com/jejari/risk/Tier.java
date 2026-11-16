package com.jejari.risk;

/**
 * 2단계 배점: 관측값이 {@code threshold} 이상이면 {@code points}(MID), {@code highThreshold} 이상이면 {@code highPoints}(HIGH).
 * 높은 단계가 낮은 단계보다 문턱도 높고 점수도 높아야 한다 (그래야 나쁜 행동을 더 해서 점수가 내려가는 일이 없다).
 */
public record Tier(double threshold, int points, double highThreshold, int highPoints) {

    public Tier {
        if (!Double.isFinite(threshold) || !Double.isFinite(highThreshold) || threshold <= 0) {
            throw new IllegalArgumentException("문턱값은 0보다 큰 유한한 수여야 합니다");
        }
        if (highThreshold < threshold) {
            throw new IllegalArgumentException("높은 단계 문턱값이 낮은 단계보다 작습니다");
        }
        if (points < 0 || points > 100 || highPoints < points || highPoints > 100) {
            throw new IllegalArgumentException("배점은 0~100이고 높은 단계가 낮은 단계 이상이어야 합니다");
        }
    }

    /** 관측값에 해당하는 점수. 0이면 해당 없음. */
    int pointsFor(double observed) {
        if (observed >= highThreshold) return highPoints;
        if (observed >= threshold) return points;
        return 0;
    }

    Severity severityFor(double observed) {
        return observed >= highThreshold ? Severity.HIGH : Severity.MID;
    }
}
