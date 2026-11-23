package com.jejari.risk;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 한 대상(CI·결제 수단·기기)의 집계 신호. 직접 만들거나 {@link SignalCollector}로 이벤트를 흘려 만든다.
 *
 * <p>모든 값을 생성 시 검사한다. 음수·NaN·서로 모순인 값은 예외다.
 * NaN은 어떤 비교도 false라서 그대로 두면 규칙이 <b>조용히 꺼진다</b>. 집계 버그가 "탐지 0건"으로 숨지 않게 바로 터뜨린다.
 *
 * @param subjectType         대상 종류
 * @param subjectHash         대상 해시 (HMAC 소문자 16진수 64자)
 * @param windowFrom          이 시각 이후 이벤트만 집계했다 (관리자 CLEAR 이후 증거만 쓰는지 엔진이 확인)
 * @param intervalCount       규칙성 통계에 넣은 요청 간격 수 (긴 공백 제외)
 * @param fastIntervalCount   그중 빠른 간격 수
 * @param intervalMeanMs      간격 평균 (intervalCount가 0이면 0)
 * @param intervalStdDevMs    간격 표준편차 (intervalCount가 0이면 0)
 * @param maxSameSeatFailures 한 좌석에 대한 선점 실패(409) 최대 횟수
 * @param purchaseCount       구매(결제 확정) 건수
 * @param totalPurchaseSeats  누적 구매 매수
 * @param maxSinglePurchase   1회 최대 매수
 * @param userCancelCount     사용자 취소(USER) 건수. 관리자·결제 실패 취소는 넣지 않는다
 * @param fastCancelCount     그중 구매 후 단시간 취소 수
 * @param lastMinuteCancelCount 그중 출발 직전 취소 수
 * @param avgMinutesToCancel  구매→취소 평균 분 (취소 0건이면 null). risk_score 저장용, 점수에는 안 씀
 * @param linkedAccountCount  이 대상에 연결된 계정 수 (자기 포함)
 * @param maxSyncAccounts     짧은 창 안에서 선점한 서로 다른 계정 수의 최댓값
 * @param accountAgeDays      연결 계정 중 가장 오래된 계정의 나이(일). 계정이 없는 대상이면 null
 * @param seatRetryFailures   같은 좌석을 다시 시도해 또 실패한 횟수를 모든 좌석에 걸쳐 합한 값 (좌석마다 실패 수 − 1의 합).
 *                            좌석을 바꿔 가며 문턱 아래로 반복하는 봇(좌석마다 4번씩)을 잡는다. 사람은 이미 잡힌 좌석을
 *                            다시 누르기보다 다른 좌석을 고르므로 정상 회원은 대개 0~1
 * @param headlessHoldSessions 좌석을 선점했는데 화면 녹화가 0건인 로그인 세션 수 (같은 시간대 다른 세션은 녹화 중일 때만)
 * @param queueWithoutSearch  집계 기간 안에 대기열 진입은 있는데 사람이 직접 한 열차 조회가 0건이면 true
 * @param inhumanSteps        같은 세션의 "좌석표 → 선점", "선점 → 결제"가 사람 손으로 불가능하게 빠른 횟수
 * @param mechanicalMouseMoves 화면 녹화에서 센 기계적 마우스 이동 수 (클릭 자리로 순간이동 + 직선·등속 이동, {@link MouseMotion}).
 *                            마우스 클릭만 보므로 휴대폰·키보드 사용자는 0
 */
public record RiskSignals(
        SubjectType subjectType, String subjectHash, Instant windowFrom,
        long intervalCount, long fastIntervalCount, double intervalMeanMs, double intervalStdDevMs,
        long maxSameSeatFailures,
        long purchaseCount, long totalPurchaseSeats, long maxSinglePurchase,
        long userCancelCount, long fastCancelCount, long lastMinuteCancelCount, Double avgMinutesToCancel,
        long linkedAccountCount, long maxSyncAccounts,
        Long accountAgeDays, long seatRetryFailures, long mechanicalMouseMoves,
        long headlessHoldSessions, boolean queueWithoutSearch, long inhumanSteps) {

    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");

    /** 단계 속도 신호 없이 만드는 모양 */
    public RiskSignals(SubjectType subjectType, String subjectHash, Instant windowFrom,
            long intervalCount, long fastIntervalCount, double intervalMeanMs, double intervalStdDevMs,
            long maxSameSeatFailures,
            long purchaseCount, long totalPurchaseSeats, long maxSinglePurchase,
            long userCancelCount, long fastCancelCount, long lastMinuteCancelCount, Double avgMinutesToCancel,
            long linkedAccountCount, long maxSyncAccounts,
            Long accountAgeDays, long seatRetryFailures, long mechanicalMouseMoves,
            long headlessHoldSessions, boolean queueWithoutSearch) {
        this(subjectType, subjectHash, windowFrom, intervalCount, fastIntervalCount, intervalMeanMs, intervalStdDevMs,
                maxSameSeatFailures, purchaseCount, totalPurchaseSeats, maxSinglePurchase, userCancelCount,
                fastCancelCount, lastMinuteCancelCount, avgMinutesToCancel, linkedAccountCount, maxSyncAccounts,
                accountAgeDays, seatRetryFailures, mechanicalMouseMoves, headlessHoldSessions, queueWithoutSearch, 0);
    }

    /** 화면·조회 신호 없이 만드는 모양 */
    public RiskSignals(SubjectType subjectType, String subjectHash, Instant windowFrom,
            long intervalCount, long fastIntervalCount, double intervalMeanMs, double intervalStdDevMs,
            long maxSameSeatFailures,
            long purchaseCount, long totalPurchaseSeats, long maxSinglePurchase,
            long userCancelCount, long fastCancelCount, long lastMinuteCancelCount, Double avgMinutesToCancel,
            long linkedAccountCount, long maxSyncAccounts,
            Long accountAgeDays, long seatRetryFailures, long mechanicalMouseMoves) {
        this(subjectType, subjectHash, windowFrom, intervalCount, fastIntervalCount, intervalMeanMs, intervalStdDevMs,
                maxSameSeatFailures, purchaseCount, totalPurchaseSeats, maxSinglePurchase, userCancelCount,
                fastCancelCount, lastMinuteCancelCount, avgMinutesToCancel, linkedAccountCount, maxSyncAccounts,
                accountAgeDays, seatRetryFailures, mechanicalMouseMoves, 0, false, 0);
    }

    /** 마우스 신호 없이 만드는 기존 모양. mechanicalMouseMoves = 0 */
    public RiskSignals(SubjectType subjectType, String subjectHash, Instant windowFrom,
            long intervalCount, long fastIntervalCount, double intervalMeanMs, double intervalStdDevMs,
            long maxSameSeatFailures,
            long purchaseCount, long totalPurchaseSeats, long maxSinglePurchase,
            long userCancelCount, long fastCancelCount, long lastMinuteCancelCount, Double avgMinutesToCancel,
            long linkedAccountCount, long maxSyncAccounts,
            Long accountAgeDays, long seatRetryFailures) {
        this(subjectType, subjectHash, windowFrom, intervalCount, fastIntervalCount, intervalMeanMs, intervalStdDevMs,
                maxSameSeatFailures, purchaseCount, totalPurchaseSeats, maxSinglePurchase, userCancelCount,
                fastCancelCount, lastMinuteCancelCount, avgMinutesToCancel, linkedAccountCount, maxSyncAccounts,
                accountAgeDays, seatRetryFailures, 0);
    }

    /** 좌석 합산 신호 없이 만드는 기존 모양 (테스트·빈 신호용). seatRetryFailures = 0 */
    public RiskSignals(SubjectType subjectType, String subjectHash, Instant windowFrom,
            long intervalCount, long fastIntervalCount, double intervalMeanMs, double intervalStdDevMs,
            long maxSameSeatFailures,
            long purchaseCount, long totalPurchaseSeats, long maxSinglePurchase,
            long userCancelCount, long fastCancelCount, long lastMinuteCancelCount, Double avgMinutesToCancel,
            long linkedAccountCount, long maxSyncAccounts,
            Long accountAgeDays) {
        this(subjectType, subjectHash, windowFrom, intervalCount, fastIntervalCount, intervalMeanMs, intervalStdDevMs,
                maxSameSeatFailures, purchaseCount, totalPurchaseSeats, maxSinglePurchase, userCancelCount,
                fastCancelCount, lastMinuteCancelCount, avgMinutesToCancel, linkedAccountCount, maxSyncAccounts,
                accountAgeDays, 0, 0);
    }

    public RiskSignals {
        if (subjectType == null) throw new IllegalArgumentException("subjectType이 없습니다");
        if (subjectHash == null || !HASH.matcher(subjectHash).matches()) {
            // 원문 개인정보가 실수로 들어오는 것도 여기서 막힌다 (메시지에 값을 싣지 않음)
            throw new IllegalArgumentException("subjectHash는 소문자 16진수 64자(HMAC)여야 합니다");
        }
        if (windowFrom == null) throw new IllegalArgumentException("windowFrom이 없습니다");
        nonNegative(intervalCount, "intervalCount");
        nonNegative(fastIntervalCount, "fastIntervalCount");
        nonNegative(maxSameSeatFailures, "maxSameSeatFailures");
        nonNegative(seatRetryFailures, "seatRetryFailures");
        nonNegative(mechanicalMouseMoves, "mechanicalMouseMoves");
        nonNegative(headlessHoldSessions, "headlessHoldSessions");
        nonNegative(inhumanSteps, "inhumanSteps");
        nonNegative(purchaseCount, "purchaseCount");
        nonNegative(totalPurchaseSeats, "totalPurchaseSeats");
        nonNegative(maxSinglePurchase, "maxSinglePurchase");
        nonNegative(userCancelCount, "userCancelCount");
        nonNegative(fastCancelCount, "fastCancelCount");
        nonNegative(lastMinuteCancelCount, "lastMinuteCancelCount");
        nonNegative(linkedAccountCount, "linkedAccountCount");
        nonNegative(maxSyncAccounts, "maxSyncAccounts");
        finiteNonNegative(intervalMeanMs, "intervalMeanMs");
        finiteNonNegative(intervalStdDevMs, "intervalStdDevMs");
        if (avgMinutesToCancel != null) finiteNonNegative(avgMinutesToCancel, "avgMinutesToCancel");
        if (accountAgeDays != null) nonNegative(accountAgeDays, "accountAgeDays");

        if (fastIntervalCount > intervalCount) {
            throw new IllegalArgumentException("fastIntervalCount가 intervalCount보다 큽니다");
        }
        if (intervalCount == 0 && (intervalMeanMs != 0 || intervalStdDevMs != 0)) {
            throw new IllegalArgumentException("간격이 0건인데 평균·표준편차가 있습니다");
        }
        if (fastCancelCount > userCancelCount || lastMinuteCancelCount > userCancelCount) {
            throw new IllegalArgumentException("단시간·출발 직전 취소 수가 전체 취소 수보다 큽니다");
        }
        if (maxSinglePurchase > totalPurchaseSeats) {
            throw new IllegalArgumentException("1회 최대 매수가 누적 매수보다 큽니다");
        }
        if (purchaseCount > totalPurchaseSeats) {
            throw new IllegalArgumentException("구매 건수가 누적 매수보다 큽니다 (1건당 최소 1매)");
        }
    }

    private static void nonNegative(long v, String name) {
        if (v < 0) throw new IllegalArgumentException(name + "가 음수입니다");
    }

    private static void finiteNonNegative(double v, String name) {
        if (!Double.isFinite(v) || v < 0) throw new IllegalArgumentException(name + "가 음수이거나 유한한 수가 아닙니다");
    }

    /** 취소 비율 (0~1). 기간 밖 구매의 취소가 섞여 1을 넘을 수 있어 1로 자른다. 구매가 없으면 0. */
    public double cancelRatio() {
        if (purchaseCount == 0) return 0;
        return Math.min(1.0, (double) userCancelCount / purchaseCount);
    }

    /** 변동계수 = 표준편차 / 평균. 평균이 0이면(모든 간격이 0ms) 완전히 일정한 것이므로 0. */
    public double intervalCv() {
        return intervalMeanMs == 0 ? 0 : intervalStdDevMs / intervalMeanMs;
    }

    /** 신호가 하나도 없는 빈 대상. 테스트·신규 대상용. */
    public static RiskSignals empty(SubjectType type, String hash, Instant windowFrom) {
        return new RiskSignals(type, hash, windowFrom, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, null);
    }
}
