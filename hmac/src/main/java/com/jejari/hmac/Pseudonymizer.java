package com.jejari.hmac;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 식별정보 가명처리. 원문 대신 HMAC-SHA256 값(소문자 16진수 64자 = VARCHAR2(64))만 저장·조회·로그에 쓴다.
 *
 * <ul>
 *   <li>같은 값은 항상 같은 해시가 나오므로 원문 없이 "같은 CI·기기·카드"끼리 묶을 수 있다.</li>
 *   <li>키(비밀값)가 없으면 해시에서 원문을 거꾸로 맞춰 볼 수 없다. 전화번호처럼 경우의 수가 작은 값에는
 *       키 없는 SHA-256이 아니라 HMAC이어야 하는 이유다.</li>
 *   <li>키는 프로젝트 기간 동안 바꾸지 않는다 (바꾸면 지금까지의 해시와 새 해시가 이어지지 않는다).</li>
 * </ul>
 *
 * 스레드 안전하다. 한 개를 만들어 앱 전체에서 공유한다.
 */
public final class Pseudonymizer {

    /** 키를 읽어 올 환경변수 이름. 값은 Base64 인코딩된 32바이트 이상. 코드·저장소에 키를 두지 않는다. */
    public static final String KEY_ENV = "JEJARI_HMAC_KEY";
    /** 이보다 짧은 키는 거부한다 (HMAC-SHA256의 출력 크기). */
    public static final int MIN_KEY_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");
    private static final Pattern PHONE_CHARS = Pattern.compile("^[+0-9\\s().-]+$");
    private static final Pattern KR_PHONE = Pattern.compile("^0\\d{9,10}$");
    private static final Pattern DIGITS_12_19 = Pattern.compile("^\\d{12,19}$");

    private final SecretKeySpec key;

    public Pseudonymizer(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("HMAC 키는 " + MIN_KEY_BYTES + "바이트 이상이어야 합니다");
        }
        this.key = new SecretKeySpec(keyBytes.clone(), ALGORITHM);
    }

    public static Pseudonymizer fromBase64(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("HMAC 키가 비어 있습니다");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            // 메시지에 입력값을 싣지 않는다 (키가 로그에 남지 않게)
            throw new IllegalArgumentException("HMAC 키가 올바른 Base64가 아닙니다");
        }
        return new Pseudonymizer(bytes);
    }

    /** 환경변수 {@value #KEY_ENV}에서 키를 읽는다. 없으면 앱이 뜨지 않게 예외를 던진다. */
    public static Pseudonymizer fromEnv() {
        String v = System.getenv(KEY_ENV);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("환경변수 " + KEY_ENV + "가 설정되지 않았습니다");
        }
        return fromBase64(v);
    }

    // ---------- 종류별 편의 메서드 (입력 형식을 맞춘 뒤 해시) ----------

    public String ci(String ci) { return hash(Purpose.CI, ci); }

    public String phone(String phone) { return hash(Purpose.PHONE, phone); }

    public String email(String email) { return hash(Purpose.EMAIL, email); }

    public String paymentMethod(String cardOrAccountNumber) { return hash(Purpose.PAYMENT, cardOrAccountNumber); }

    public String device(String fingerprint) { return hash(Purpose.DEVICE, fingerprint); }

    public String ip(String ip) { return hash(Purpose.IP, ip); }

    /**
     * 모의 본인인증의 가짜 CI를 해시한다 (AUTH-02). 같은 사람(이름+휴대폰)은 항상 같은 값이 나와야
     * 다계정이 하나로 묶인다. 이름의 공백·전각/반각 차이와 전화번호 표기(하이픈, +82)는 무시한다.
     */
    public String mockCiHash(String name, String phone) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("이름이 비어 있습니다");
        }
        String n = Normalizer.normalize(name, Normalizer.Form.NFKC).replaceAll("\\s+", "");
        return ci("MOCK-CI|" + n + "|" + normalize(Purpose.PHONE, phone));
    }

    // ---------- 핵심 ----------

    /** 입력을 종류에 맞게 정규화한 뒤 HMAC-SHA256을 구해 소문자 16진수 64자로 돌려준다. */
    public String hash(Purpose purpose, String raw) {
        String normalized = normalize(purpose, raw);
        // 메시지 = 종류 이름 + 0x00 + 값. 종류가 다르면 같은 값도 다른 해시 (도메인 분리)
        byte[] message = concat(purpose.name().getBytes(StandardCharsets.US_ASCII),
                normalized.getBytes(StandardCharsets.UTF_8));
        return hmacHex(key, message);
    }

    static String hmacHex(SecretKeySpec key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM); // Mac은 스레드 안전하지 않아 호출마다 만든다
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(message));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 계산 실패", e);
        }
    }

    static String normalize(Purpose purpose, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(purpose + " 값이 비어 있습니다");
        }
        String v = raw.trim();
        return switch (purpose) {
            case CI, DEVICE -> v;
            case EMAIL -> {
                v = v.toLowerCase(Locale.ROOT);
                if (!EMAIL.matcher(v).matches()) {
                    throw new IllegalArgumentException("이메일 형식이 아닙니다");
                }
                yield v;
            }
            case PHONE -> normalizePhone(v);
            case PAYMENT -> {
                String digits = v.replaceAll("[\\s-]", "");
                if (!DIGITS_12_19.matcher(digits).matches()) {
                    throw new IllegalArgumentException("카드·계좌 번호는 숫자 12~19자리여야 합니다");
                }
                yield digits;
            }
            case IP -> {
                if (v.length() > 45) {
                    throw new IllegalArgumentException("IP 주소가 너무 깁니다");
                }
                yield v.toLowerCase(Locale.ROOT);
            }
        };
    }

    /** 010-1234-5678, 01012345678, +82 10-1234-5678, 82-10-1234-5678 → 01012345678 */
    private static String normalizePhone(String v) {
        // 숫자·공백·하이픈·괄호·+ 외의 글자가 섞이면 오타로 보고 거부한다 (글자를 몰래 버리면 다른 번호가 같은 해시가 된다)
        if (!PHONE_CHARS.matcher(v).matches()) {
            throw new IllegalArgumentException("휴대폰 번호 형식이 아닙니다");
        }
        String digits = v.replaceAll("[^0-9]", "");
        if (digits.startsWith("82") && digits.length() >= 11 && digits.length() <= 12) {
            digits = "0" + digits.substring(2);
        }
        if (!KR_PHONE.matcher(digits).matches()) {
            throw new IllegalArgumentException("휴대폰 번호 형식이 아닙니다");
        }
        return digits;
    }

    private static byte[] concat(byte[] purpose, byte[] value) {
        byte[] out = new byte[purpose.length + 1 + value.length];
        System.arraycopy(purpose, 0, out, 0, purpose.length);
        out[purpose.length] = 0;
        System.arraycopy(value, 0, out, purpose.length + 1, value.length);
        return out;
    }

    /** 키가 로그·예외에 섞여 나가지 않게 내용을 숨긴다. */
    @Override
    public String toString() {
        return "Pseudonymizer[key=****]";
    }
}
