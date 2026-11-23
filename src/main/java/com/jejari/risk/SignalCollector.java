package com.jejari.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 이벤트를 하나씩 흘려 넣어 {@link RiskSignals}를 만든다. MongoDB 커서·Oracle 결과를 그대로 넣으면 된다.
 *
 * <ul>
 *   <li>메모리는 대상 하나당 거의 일정하다. 간격은 Welford 방식으로 평균·분산만 들고, 목록을 쌓지 않는다.
 *       그래서 "샘플 N개만 본다" 같은 자르기가 없고, 느린 요청을 수천 건 섞어 빠른 클릭을 창 밖으로 밀어내는 회피가 안 된다.</li>
 *   <li>좌석별 실패 수와 동시 선점 창에는 상한이 있다 (공격자가 메모리를 키우는 것 방지).
 *       상한에 닿아도 이미 센 최댓값은 줄지 않는다.</li>
 *   <li>스레드 안전하지 않다. 대상 하나에 하나씩 만들어 쓰고 버린다.</li>
 * </ul>
 */
public final class SignalCollector {

    /** 좌석별 실패 수를 세는 서로 다른 좌석 수 상한 */
    static final int MAX_TRACKED_SEATS = 50_000;
    /** 동시 선점 창에 들고 있는 선점 이벤트 수 상한 */
    static final int MAX_WINDOW_EVENTS = 100_000;

    private final RiskPolicy policy;
    private final SubjectType subjectType;
    private final String subjectHash;
    private final Instant windowFrom;

    // 요청 간격 (Welford)
    private long intervalCount;
    private long fastIntervalCount;
    private double mean;
    private double m2;

    // 같은 좌석 반복
    private final Map<Long, Long> seatFailures = new HashMap<>();
    private long maxSameSeatFailures;
    private long seatRetryFailures;

    // 구매·취소
    private long purchaseCount;
    private long totalSeats;
    private long maxSingle;
    private long userCancels;
    private long fastCancels;
    private long lastMinuteCancels;
    private double cancelMinutesSum;

    // 다계정 동시 선점 (시간순 슬라이딩 창)
    private final ArrayDeque<Hold> window = new ArrayDeque<>();
    private final Map<Long, Integer> windowMembers = new HashMap<>();
    private Instant lastHoldTs;
    private long maxSync;

    private long linkedAccounts;
    private Long accountAgeDays;
    private long mechanicalMouse;
    private long headlessHolds;
    private long queueEnters;
    private long searches;
    /** 선점한 로그인 세션 → 첫 선점 시각 (화면 녹화 확인용). 상한 있음 */
    private final Map<String, Instant> holdSessions = new java.util.LinkedHashMap<>();
    static final int MAX_HOLD_SESSIONS = 200;
    /** 단계 속도를 재려고 세션마다 들고 있는 마지막 좌석표·선점 시각. 상한 있음 */
    static final int MAX_STEP_SESSIONS = 5_000;
    private final Map<String, Instant> lastSeatView = new HashMap<>();
    private final Map<String, Instant> lastHold = new HashMap<>();
    private long inhumanSteps;

    private record Hold(Instant ts, long memberId) {
    }

    public SignalCollector(RiskPolicy policy, SubjectType subjectType, String subjectHash, Instant windowFrom) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.subjectType = subjectType;
        this.subjectHash = subjectHash;
        this.windowFrom = windowFrom;
    }

    /**
     * 같은 세션 안 연속 요청의 간격 (behavior_log.interval_ms). null(세션 첫 요청·리밸런스 직후)은 넣지 않는다.
     * 음수(서버 간 시계 차이)는 0으로 본다: 버리면 "시계를 흔들어 간격을 숨기는" 여지가 생기므로 빠른 쪽으로 센다.
     */
    public SignalCollector interval(long intervalMs) {
        long v = Math.max(0, intervalMs);
        if (v > policy.statsMaxGapMs()) {
            return this; // 쉬었다 온 공백. 규칙성 통계에 넣으면 긴 공백 몇 번으로 규칙성을 흐릴 수 있다
        }
        intervalCount++;
        if (v < policy.fastIntervalMs()) fastIntervalCount++;
        double delta = v - mean;
        mean += delta / intervalCount;
        m2 += delta * (v - mean);
        return this;
    }

    /** 좌석 선점 실패(409, 이미 선점된 좌석). 좌석 ID별로 센다. */
    public SignalCollector seatHoldFailure(long seatId) {
        Long cur = seatFailures.get(seatId);
        if (cur == null) {
            if (seatFailures.size() >= MAX_TRACKED_SEATS) {
                return this; // 새 좌석은 더 세지 않지만 이미 센 최댓값은 유지
            }
            cur = 0L;
        }
        long next = cur + 1;
        seatFailures.put(seatId, next);
        maxSameSeatFailures = Math.max(maxSameSeatFailures, next);
        if (next >= 2) {
            seatRetryFailures++; // 같은 좌석을 다시 눌러 또 실패 (첫 실패는 "이미 잡힌 좌석"이라 사람도 흔함)
        }
        return this;
    }

    /** 결제 확정된 구매 한 건 */
    public SignalCollector purchase(int seatCount) {
        if (seatCount <= 0) throw new IllegalArgumentException("구매 매수는 1 이상이어야 합니다");
        purchaseCount++;
        totalSeats = Math.addExact(totalSeats, seatCount);
        maxSingle = Math.max(maxSingle, seatCount);
        return this;
    }

    /**
     * 사용자 취소(USER) 한 건. 관리자 취소·결제 실패 환불은 넣지 않는다 (CNL-02 "USER만 위험 신호").
     *
     * @param minutesAfterPurchase   구매 후 경과 분 (cancellation.minutes_after_purchase)
     * @param minutesBeforeDeparture 출발 전 남은 분. 출발 후 취소면 음수
     */
    public SignalCollector userCancel(long minutesAfterPurchase, long minutesBeforeDeparture) {
        if (minutesAfterPurchase < 0) throw new IllegalArgumentException("구매 후 경과 분이 음수입니다");
        userCancels++;
        cancelMinutesSum += minutesAfterPurchase;
        if (minutesAfterPurchase <= policy.cancelAfterPayMin()) fastCancels++;
        if (minutesBeforeDeparture <= policy.lastMinuteMin()) lastMinuteCancels++; // 출발 후(음수)도 직전으로 센다
        return this;
    }

    /**
     * 좌석 선점 시도 한 건 (성공·실패 모두). <b>시각 오름차순으로</b> 넣는다.
     * 앞선 시각이 들어오면 직전 시각으로 본다 (같은 ms의 서버 간 순서 차이 흡수, 창이 줄어드는 쪽이 아님).
     */
    public SignalCollector seatHold(Instant ts, long memberId) {
        Objects.requireNonNull(ts, "ts");
        Instant t = (lastHoldTs != null && ts.isBefore(lastHoldTs)) ? lastHoldTs : ts;
        lastHoldTs = t;
        Instant cutoff = t.minus(Duration.ofSeconds(policy.multiAccountWindowSec()));
        while (!window.isEmpty() && (window.peekFirst().ts().isBefore(cutoff) || window.size() >= MAX_WINDOW_EVENTS)) {
            Hold old = window.pollFirst();
            windowMembers.computeIfPresent(old.memberId(), (k, c) -> c == 1 ? null : c - 1);
        }
        window.addLast(new Hold(t, memberId));
        windowMembers.merge(memberId, 1, Integer::sum);
        maxSync = Math.max(maxSync, windowMembers.size());
        return this;
    }

    /** 화면 녹화 한 세션의 마우스 분석 결과를 더한다 ({@link MouseMotion}). 세션마다 한 번 */
    public SignalCollector mouse(MouseMotion.Result r) {
        mechanicalMouse = Math.addExact(mechanicalMouse, r.total());
        return this;
    }

    /** 좌석 선점이 있었던 로그인 세션 (첫 선점 시각만 남김). 화면 녹화 확인은 읽는 쪽이 {@link #headlessHolds} */
    public SignalCollector holdSession(String sessionId, Instant ts) {
        if (sessionId != null && ts != null && (holdSessions.containsKey(sessionId) || holdSessions.size() < MAX_HOLD_SESSIONS)) {
            holdSessions.merge(sessionId, ts, (a, b) -> a.isBefore(b) ? a : b);
        }
        return this;
    }

    public Map<String, Instant> holdSessions() {
        return java.util.Collections.unmodifiableMap(holdSessions);
    }

    /** 화면 녹화가 0건인 선점 세션 수 */
    public SignalCollector headlessHolds(long n) {
        if (n < 0) throw new IllegalArgumentException("화면 없는 선점 세션 수가 음수입니다");
        headlessHolds = n;
        return this;
    }

    /**
     * 사람이 본 좌석표 (자동 새로고침 meta.auto는 넣지 않는다 — 좌석을 놓친 직후 자동 새로고침과 사람의 선점 클릭이
     * 우연히 붙어도 "사람 손으로 불가능한 속도"가 되지 않게). 시각 오름차순으로 넣는다
     */
    public SignalCollector seatView(String sessionId, Instant ts) {
        put(lastSeatView, sessionId, ts);
        return this;
    }

    /** 선점 시도 (처리 시간을 모를 때) */
    public SignalCollector holdStep(String sessionId, Instant ts) {
        return holdStep(sessionId, ts, ts);
    }

    /**
     * 선점 시도: 같은 세션의 마지막 좌석표 응답에서 문턱보다 빨랐으면 센다.
     *
     * @param ts      선점 요청을 받은 시각
     * @param readyAt 선점 응답을 다 보낸 시각 (다음 결제 단계의 출발점)
     */
    public SignalCollector holdStep(String sessionId, Instant ts, Instant readyAt) {
        if (fast(lastSeatView.get(sessionId), ts)) {
            inhumanSteps++;
            lastSeatView.remove(sessionId); // 같은 좌석표 한 번으로 선점 연타를 여러 번 세지 않음 (연타는 FAST_CLICK 몫)
        }
        put(lastHold, sessionId, readyAt);
        return this;
    }

    /** 결제 시작: 같은 세션의 마지막 선점에서 문턱보다 빨랐으면 센다 */
    public SignalCollector payStep(String sessionId, Instant ts) {
        if (fast(lastHold.get(sessionId), ts)) {
            inhumanSteps++;
            lastHold.remove(sessionId);
        }
        return this;
    }

    private boolean fast(Instant from, Instant to) {
        if (from == null || to == null) return false;
        long ms = Duration.between(from, to).toMillis();
        return ms >= 0 && ms < policy.inhumanStepMs();
    }

    private static void put(Map<String, Instant> m, String sessionId, Instant ts) {
        if (sessionId != null && ts != null && (m.containsKey(sessionId) || m.size() < MAX_STEP_SESSIONS)) {
            m.put(sessionId, ts);
        }
    }

    /** 사람이 직접 한 열차 조회 (자동 조회 meta.auto 제외) */
    public SignalCollector search() {
        searches++;
        return this;
    }

    public SignalCollector queueEnter() {
        queueEnters++;
        return this;
    }

    /** 이 대상에 연결된 계정 수 (자기 포함) */
    public SignalCollector linkedAccounts(long count) {
        if (count < 0) throw new IllegalArgumentException("연결 계정 수가 음수입니다");
        linkedAccounts = count;
        return this;
    }

    /** 연결 계정 중 가장 오래된 계정의 가입 시각 */
    public SignalCollector oldestAccountCreatedAt(Instant createdAt, Instant now) {
        long days = Duration.between(createdAt, now).toDays();
        accountAgeDays = Math.max(0, days); // 미래 가입 시각(시계 오류)은 0일 = 신규로 본다
        return this;
    }

    public RiskSignals build() {
        double sd = intervalCount > 1 ? Math.sqrt(Math.max(0, m2 / intervalCount)) : 0;
        Double avgCancel = userCancels == 0 ? null : cancelMinutesSum / userCancels;
        return new RiskSignals(subjectType, subjectHash, windowFrom,
                intervalCount, fastIntervalCount, intervalCount == 0 ? 0 : mean, sd,
                maxSameSeatFailures,
                purchaseCount, totalSeats, maxSingle,
                userCancels, fastCancels, lastMinuteCancels, avgCancel,
                linkedAccounts, maxSync, accountAgeDays, seatRetryFailures, mechanicalMouse,
                headlessHolds, queueEnters > 0 && searches == 0, inhumanSteps);
    }
}
