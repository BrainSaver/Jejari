package com.jejari.common.pii;

/**
 * 암호화 결과.
 *
 * @param cipherText 저장 형식 {@code v1.<keyId>.<base64url(IV 12 ‖ 암호문 ‖ 태그 16)>} → 예: member.phone_enc.
 *                   형식 버전과 키 ID가 앞에 붙어 있어 이 값 하나만으로 복호화할 수 있다
 * @param keyId      암호화에 쓴 키 버전 (cipherText 안의 값과 같음) → member.enc_key_id.
 *                   "아직 옛 키로 암호화된 행" 조회(키 교체 배치)용이다
 */
public record Encrypted(String cipherText, String keyId) {

    /** 암호문이 로그에 그대로 찍히지 않게 길이만 보여 준다. */
    @Override
    public String toString() {
        return "Encrypted[cipherText=(" + (cipherText == null ? 0 : cipherText.length()) + "자), keyId=" + keyId + "]";
    }
}
