package com.jejari.risk;

/**
 * 점수 근거 코드.
 *
 * <p>{@code marker = true}인 6개는 ERD {@code case_marker.rule_code} CHECK 제약에 있는 값이라
 * (REGULAR_INTERVAL은 v24에서 추가, 일정 간격 매크로가 이것에만 걸려 케이스가 안 열리던 것을 막음)
 * 타임라인 마커로 남고, 하나라도 걸리면 의심 케이스를 연다 (RISK-04 "규칙 적중").
 * 나머지는 점수에만 더해지는 보조 신호다. 이름을 바꾸면 저장된 reasons JSON과 맞지 않게 되므로 추가만 한다.
 */
public enum RiskRule {
    /** 짧은 간격(기본 200ms 미만) 요청이 여러 번 */
    FAST_CLICK(true),
    /** 같은 좌석 선점 실패(409)를 여러 번 반복. 한 좌석 최대 실패 또는 여러 좌석의 재시도 실패 합 */
    SAME_SEAT_RETRY(true),
    /** 구매 후 단시간(기본 30분) 안에 취소 */
    CANCEL_AFTER_PAY(true),
    /** 같은 CI·기기·결제 수단의 서로 다른 계정이 짧은 시간(기본 60초) 안에 동시에 선점 */
    MULTI_ACCOUNT_SYNC(true),

    /** 요청 간격이 기계처럼 일정함 (변동계수가 아주 작음). 고정 sleep 매크로. 마커 규칙 (ERD v24) */
    REGULAR_INTERVAL(true),
    /** 취소 비율이 높음 */
    HIGH_CANCEL_RATIO(false),
    /** 출발 직전(기본 3시간 이내) 취소가 많음 */
    LAST_MINUTE_CANCEL(false),
    /** 누적 구매 매수가 많음 */
    BULK_PURCHASE(false),
    /** 같은 대상에 연결된 계정이 많음 */
    LINKED_ACCOUNTS(false),
    /** 가입한 지 얼마 안 된 계정 (기본 점수를 조금 높게 시작) */
    NEW_ACCOUNT(false),

    /**
     * 화면 녹화의 마우스가 기계처럼 움직임: 클릭 자리로 순간이동, 직선·등속 이동 ({@link MouseMotion}).
     * 사람 흉내 브라우저 매크로용 마커 규칙 (ERD case_marker.rule_code CHECK에 처음부터 있던 값, 2026-11-23 구현)
     */
    LINEAR_MOUSE(true),

    /**
     * 화면 없이 좌석 선점: 선점한 로그인 세션에 화면 녹화가 하나도 없음 = 브라우저 없이 API만 부른 클라이언트.
     * 녹화가 있어도 사람 손이 움직인 흔적이 없으면(클릭 0건, 또는 마우스 클릭뿐이고 궤적이 클릭 자리에만) 같게 본다 — 녹화를 꾸민 봇
     * ({@code MouseMotion.handsOff}, 2026-11-24).
     * 같은 시간대에 다른 세션은 녹화되고 있을 때만 센다 (녹화 장애 때 전원 오탐 방지).
     * 마커 규칙. ERD case_marker.rule_code CHECK에 값이 들어오기 전에는 {@code MarkerCodes}가 마커를 빼서 점수만 남긴다
     */
    HEADLESS_HOLD(true),

    /**
     * 집계 기간 안에 열차 조회(사람이 직접 한 VIEW_TRAIN)가 0건인데 대기열 진입이 있음 = 운행 번호를 알고 대기열 주소로 바로.
     * 취소표 대기·다시 줄 서기·새로고침·북마크로도 조회 없이 올 수 있어 <b>낮은 점수(LOW)만</b> (팀 의견 2026-11-23).
     * 검색 API를 한 번만 불러도 피하므로 단독으로는 약한 신호
     */
    NO_SEARCH_ENTRY(false),

    /**
     * 사람 손으로 불가능한 단계 속도: 같은 세션에서 "좌석표 → 선점" 또는 "선점 → 결제"가 기본 0.5초 미만인 일이 여러 번.
     * 화면에서는 좌석을 눈으로 고르고 누르는 단계, 결제 화면이 열리고 결제를 누르는 단계가 있어 이렇게 빠를 수 없다.
     * 요청을 몇 번만 하는 매크로(연타·일정 간격 규칙의 최소 횟수에 안 닿음)를 잡는다 (강동균 연습 봇 30종 중 25종, 2026-11-23).
     * 마커 규칙 — ERD 패치 전에는 {@code MarkerCodes}가 마커를 빼서 점수만
     */
    INHUMAN_STEP(true);

    private final boolean marker;

    RiskRule(boolean marker) {
        this.marker = marker;
    }

    /** case_marker로 남고 케이스를 여는 규칙인가 */
    public boolean marker() {
        return marker;
    }
}
