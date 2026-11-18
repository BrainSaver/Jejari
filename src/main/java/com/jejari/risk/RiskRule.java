package com.jejari.risk;

/**
 * 점수 근거 코드.
 *
 * <p>{@code marker = true}인 5개는 ERD {@code case_marker.rule_code} CHECK 제약에 있는 값이라
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
    NEW_ACCOUNT(false);

    private final boolean marker;

    RiskRule(boolean marker) {
        this.marker = marker;
    }

    /** case_marker로 남고 케이스를 여는 규칙인가 */
    public boolean marker() {
        return marker;
    }
}
