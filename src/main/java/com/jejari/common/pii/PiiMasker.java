package com.jejari.common.pii;

/**
 * 화면·로그 표시용 마스킹. 저장용이 아니다 (저장은 암호화 또는 해시).
 *
 * <ul>
 *   <li>이름: 홍길동 → 홍*동, 남궁민수 → 남**수, 홍길 → 홍*, 홍 → *</li>
 *   <li>휴대폰: 010-1234-5678 → 010-****-5678, 011-123-4567 → 011-***-4567</li>
 *   <li>이메일: abcdef@example.com → ab***@example.com, a@x.com → a***@x.com</li>
 * </ul>
 *
 * null은 null로, 빈 문자열은 빈 문자열로 돌려준다 (화면에서 "없음"과 구분하도록).
 * 형식이 예상과 다르면 원문 일부가 새지 않게 더 많이 가린다.
 */
public final class PiiMasker {

    private static final char MASK = '*';

    private PiiMasker() {
    }

    public static String name(String name) {
        if (name == null || name.isBlank()) return name;
        int[] cps = name.strip().codePoints().toArray(); // 이모지·확장 한자도 한 글자로 센다
        int n = cps.length;
        if (n == 1) return "*";
        StringBuilder sb = new StringBuilder().appendCodePoint(cps[0]);
        if (n == 2) return sb.append(MASK).toString();
        sb.append(String.valueOf(MASK).repeat(n - 2));
        return sb.appendCodePoint(cps[n - 1]).toString();
    }

    public static String phone(String phone) {
        if (phone == null || phone.isBlank()) return phone;
        String d = phone.replaceAll("[^0-9]", "");
        if (d.startsWith("82") && (d.length() == 11 || d.length() == 12)) {
            d = "0" + d.substring(2); // +82 10-1234-5678 → 01012345678
        }
        if (d.length() == 11) {               // 010-1234-5678
            return d.substring(0, 3) + "-****-" + d.substring(7);
        }
        if (d.length() == 10) {               // 011-123-4567 (서울 02 번호는 휴대폰이 아니라 고려하지 않음)
            return d.substring(0, 3) + "-***-" + d.substring(6);
        }
        // 형식을 모르면 끝 4자리만 남긴다
        if (d.length() > 4) {
            return String.valueOf(MASK).repeat(d.length() - 4) + d.substring(d.length() - 4);
        }
        return String.valueOf(MASK).repeat(Math.max(d.length(), 1));
    }

    public static String email(String email) {
        if (email == null || email.isBlank()) return email;
        String e = email.strip();
        int at = e.lastIndexOf('@');
        if (at <= 0 || at == e.length() - 1) {
            return "***"; // 이메일 형식이 아니면 전부 가린다
        }
        String local = e.substring(0, at);
        String domain = e.substring(at + 1);
        int keep = local.length() <= 2 ? 1 : 2;
        return local.substring(0, keep) + "***@" + domain;
    }
}
