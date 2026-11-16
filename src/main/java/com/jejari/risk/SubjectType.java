package com.jejari.risk;

/** 점수를 매기는 단위. 계정이 아니라 사람·결제 수단·기기에 점수를 붙인다 (risk_score.subject_type). */
public enum SubjectType {
    /** 모의 본인인증 CI 해시. 사람 하나당 하나라 계정을 여러 개 만들어도 바꿀 수 없다 → 가장 강한 묶음 */
    CI,
    /** 결제 수단 해시 */
    PAYMENT,
    /** 기기 지문 해시. 클라이언트가 만든 값이라 바꿔 가며 피할 수 있다 → CI 점수와 MAX로 결합해 보완 */
    DEVICE
}
