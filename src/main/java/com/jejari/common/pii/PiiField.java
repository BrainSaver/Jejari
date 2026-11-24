package com.jejari.common.pii;

import java.util.regex.Pattern;

/**
 * 암호문이 저장되는 자리(테이블.컬럼). AAD에 들어가므로 다른 컬럼으로 옮긴 암호문은 복호화되지 않는다.
 * 문자열을 손으로 쓰다 오타가 나지 않게 아래 상수를 쓴다. 새 암호화 컬럼이 생기면 여기에 추가한다.
 *
 * <p>상수의 table·column 값을 바꾸면 이미 저장된 암호문이 전부 복호화되지 않는다. 이름은 고정이다.
 */
public record PiiField(String table, String column) {

    // 아래 상수보다 먼저 선언해야 한다 (static 초기화는 위에서부터)
    private static final Pattern IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,29}$");

    /** 회원 휴대폰 번호. rowId = member_id */
    public static final PiiField MEMBER_PHONE = new PiiField("member", "phone_enc");
    /** 관리자 TOTP 비밀키. rowId = admin_id */
    public static final PiiField ADMIN_MFA_SECRET = new PiiField("admin_user", "mfa_secret_enc");
    /** 단체 탑승자 이름·휴대폰 (ERD v36). rowId = "{group_id}-{passenger_no}" */
    public static final PiiField GROUP_PASSENGER_NAME = new PiiField("group_passenger", "name_enc");
    public static final PiiField GROUP_PASSENGER_PHONE = new PiiField("group_passenger", "phone_enc");

    public PiiField {
        if (table == null || !IDENT.matcher(table).matches()
                || column == null || !IDENT.matcher(column).matches()) {
            throw new IllegalArgumentException("테이블·컬럼 이름은 소문자·숫자·_ 30자 이내여야 합니다");
        }
    }

    @Override
    public String toString() {
        return table + "." + column;
    }
}
