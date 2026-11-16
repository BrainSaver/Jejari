package com.jejari.common.pii;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 개인정보 암호화 (AES-256-GCM). 원문이 다시 필요한 값(휴대폰 번호, 관리자 TOTP 비밀키)에만 쓴다.
 * 묶기만 하면 되는 값(CI, IP, 기기, 카드)은 암호화하지 않고 A의 {@code Pseudonymizer}(HMAC)로 해시한다.
 *
 * <h2>저장 형식</h2>
 * <pre>
 * v1.k1.&lt;base64url( IV 12바이트 ‖ AES-GCM(패딩된 평문) ‖ 태그 16바이트 )&gt;
 * AAD = "jejari-pii|v1|k1|member.phone_enc|12345"   (형식 버전 | 키 ID | 테이블.컬럼 | 행 ID)
 * </pre>
 *
 * <h2>무엇을 막나</h2>
 * <ul>
 *   <li><b>변조</b>: 한 비트라도 바뀌면 인증 태그가 맞지 않아 실패한다.</li>
 *   <li><b>행 바꿔치기</b>: AAD에 행 ID가 들어가므로 A 회원의 암호문을 B 회원 행에 복사하면 실패한다
 *       (DB 쓰기 권한을 얻은 공격자가 남의 알림을 자기 번호로 돌리는 공격).</li>
 *   <li><b>컬럼 바꿔치기</b>: AAD에 테이블.컬럼이 들어간다.</li>
 *   <li><b>키 ID·형식 버전 바꿔치기</b>: 둘 다 AAD에 들어가 인증된다.</li>
 *   <li><b>길이 노출</b>: 평문을 16바이트 단위로 채워(ISO/IEC 7816-4) 10자리·11자리 번호가 같은 길이가 된다.</li>
 *   <li><b>IV 재사용</b>: IV를 매번 SecureRandom으로 새로 뽑는다. 키 하나당 2^32건까지 안전하다
 *       (이 프로젝트 규모의 수십만 배).</li>
 *   <li><b>약한 키·키 재사용</b>: 32바이트가 아니거나, 모든 바이트가 같거나, 버전끼리 같은 키, HMAC 키와 같은 키는 거부한다.</li>
 * </ul>
 *
 * <h2>막지 못하는 것 (모듈 밖에서 막아야 함)</h2>
 * 앱 서버 자체가 털려 키와 메모리가 노출되는 경우, 복호화한 평문을 호출한 쪽이 로그에 찍는 경우.
 * Java String은 메모리에서 지울 수 없으므로 평문은 필요한 곳에서만 짧게 쓴다.
 *
 * 스레드 안전하다. 한 개를 만들어 앱 전체에서 공유한다.
 */
public final class PiiCipher {

    /** 키 목록 환경변수. 형식: {@code k1:<base64 32바이트>,k2:<base64 32바이트>} */
    public static final String KEYS_ENV = "JEJARI_ENC_KEYS";
    /** 키 목록을 파일로 줄 때 그 경로 (Docker secret 등). 설정되어 있으면 {@link #KEYS_ENV}보다 우선한다. */
    public static final String KEYS_FILE_ENV = "JEJARI_ENC_KEYS_FILE";
    /** 새 값을 암호화할 키 버전 환경변수. 예: {@code k1} */
    public static final String ACTIVE_ENV = "JEJARI_ENC_ACTIVE";
    /** A 모듈의 HMAC 키 환경변수. 암호화 키와 같으면 거부한다 (키 분리). */
    public static final String HMAC_KEY_ENV = "JEJARI_HMAC_KEY";

    /** 평문 최대 길이(UTF-8 바이트). 패딩 후 80바이트 → 저장 형식 최대 약 135자로 VARCHAR2(200)에 들어간다. */
    public static final int MAX_PLAIN_BYTES = 64;

    static final String VERSION = "v1";
    static final int KEY_BYTES = 32;   // AES-256
    static final int IV_BYTES = 12;    // GCM 권장 96비트
    static final int TAG_BITS = 128;
    static final int PAD_BLOCK = 16;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String AAD_PREFIX = "jejari-pii";
    /** 키 ID는 enc_key_id 컬럼(VARCHAR2(20))과 저장 형식의 구분자 '.'와 겹치지 않게 제한한다. */
    private static final Pattern KEY_ID = Pattern.compile("^[a-z0-9]{1,20}$");
    /** 행 ID: NUMBER PK나 UUID. AAD 구분자 '|'가 들어갈 수 없게 제한한다. */
    private static final Pattern ROW_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    private final Map<String, SecretKeySpec> keys;
    private final String activeKeyId;

    /**
     * @param keys        키 버전 → 32바이트 키
     * @param activeKeyId 새 값을 암호화할 키 버전. keys 안에 있어야 한다
     */
    public PiiCipher(Map<String, byte[]> keys, String activeKeyId) {
        this(keys, activeKeyId, null);
    }

    /**
     * @param hmacKey A 모듈의 HMAC 키. 주면 암호화 키와 겹치지 않는지 확인한다 (null이면 생략)
     */
    public PiiCipher(Map<String, byte[]> keys, String activeKeyId, byte[] hmacKey) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("암호화 키가 하나도 없습니다");
        }
        Map<String, SecretKeySpec> copy = new LinkedHashMap<>();
        List<byte[]> seen = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : keys.entrySet()) {
            String id = e.getKey();
            byte[] bytes = e.getValue();
            if (id == null || !KEY_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("키 ID는 소문자·숫자 1~20자여야 합니다");
            }
            // 아래 모든 메시지에 키 값을 싣지 않는다
            if (bytes == null || bytes.length != KEY_BYTES) {
                throw new IllegalArgumentException("암호화 키 " + id + "는 정확히 " + KEY_BYTES + "바이트여야 합니다");
            }
            if (isTrivial(bytes)) {
                throw new IllegalArgumentException("암호화 키 " + id + "는 모든 바이트가 같습니다. openssl rand -base64 32로 만드세요");
            }
            for (byte[] other : seen) {
                if (MessageDigest.isEqual(other, bytes)) {
                    throw new IllegalArgumentException("암호화 키 " + id + "가 다른 버전의 키와 같습니다");
                }
            }
            if (hmacKey != null && MessageDigest.isEqual(hmacKey, bytes)) {
                throw new IllegalArgumentException("암호화 키 " + id + "가 HMAC 키와 같습니다. 용도마다 다른 키를 쓰세요");
            }
            seen.add(bytes);
            copy.put(id, new SecretKeySpec(bytes, "AES")); // SecretKeySpec이 배열을 복사해 둔다
        }
        if (activeKeyId == null || !copy.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("활성 키 " + activeKeyId + "가 키 목록에 없습니다");
        }
        this.keys = Collections.unmodifiableMap(copy);
        this.activeKeyId = activeKeyId;
    }

    private static boolean isTrivial(byte[] k) {
        for (byte b : k) {
            if (b != k[0]) return false;
        }
        return true;
    }

    // ---------- 설정 읽기 (잘못되면 앱이 뜨지 않게 예외) ----------

    /** 문자열 형식({@code k1:<base64>,k2:<base64>})을 읽는다. */
    public static PiiCipher fromConfig(String keysSpec, String activeKeyId) {
        return fromConfig(keysSpec, activeKeyId, null);
    }

    /** @param hmacKeyBase64 A 모듈의 HMAC 키(Base64). 주면 키 분리를 확인한다 */
    public static PiiCipher fromConfig(String keysSpec, String activeKeyId, String hmacKeyBase64) {
        if (keysSpec == null || keysSpec.isBlank()) {
            throw new IllegalStateException("암호화 키(" + KEYS_FILE_ENV + " 또는 " + KEYS_ENV + ")가 설정되지 않았습니다");
        }
        if (activeKeyId == null || activeKeyId.isBlank()) {
            throw new IllegalStateException("환경변수 " + ACTIVE_ENV + "가 설정되지 않았습니다");
        }
        Map<String, byte[]> keys = new LinkedHashMap<>();
        for (String entry : keysSpec.split(",")) {
            String e = entry.strip();
            if (e.isEmpty()) continue;
            int colon = e.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("암호화 키 목록 형식은 k1:<base64>,k2:<base64> 입니다");
            }
            String id = e.substring(0, colon).strip();
            if (keys.put(id, decodeKey(e.substring(colon + 1), "암호화 키 " + id)) != null) {
                throw new IllegalArgumentException("암호화 키 ID " + id + "가 중복됩니다");
            }
        }
        byte[] hmac = (hmacKeyBase64 == null || hmacKeyBase64.isBlank()) ? null : decodeKey(hmacKeyBase64, "HMAC 키");
        try {
            return new PiiCipher(keys, activeKeyId.strip(), hmac);
        } finally {
            keys.values().forEach(b -> Arrays.fill(b, (byte) 0)); // 임시 배열에 남은 키를 지운다
            if (hmac != null) Arrays.fill(hmac, (byte) 0);
        }
    }

    /**
     * 환경변수에서 읽는다. {@value #KEYS_FILE_ENV}(파일 경로)가 있으면 그 파일을, 없으면 {@value #KEYS_ENV}를 쓴다.
     * 운영(시연 서버)에서는 파일 쪽을 권장한다: 환경변수는 {@code docker inspect}, {@code /proc/<pid>/environ}으로 보인다.
     */
    public static PiiCipher fromEnv() {
        String file = System.getenv(KEYS_FILE_ENV);
        String spec;
        if (file != null && !file.isBlank()) {
            try {
                spec = Files.readString(Path.of(file.strip()), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new IllegalStateException(KEYS_FILE_ENV + " 파일을 읽지 못했습니다");
            }
        } else {
            spec = System.getenv(KEYS_ENV);
        }
        return fromConfig(spec, System.getenv(ACTIVE_ENV), System.getenv(HMAC_KEY_ENV));
    }

    private static byte[] decodeKey(String base64, String what) {
        try {
            return Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(what + "가 올바른 Base64가 아닙니다"); // 입력값을 싣지 않는다
        }
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    // ---------- 암호화 ----------

    /**
     * @param plain 평문 (1~{@value #MAX_PLAIN_BYTES}바이트)
     * @param field 저장할 컬럼 (예: {@link PiiField#MEMBER_PHONE})
     * @param rowId 그 행의 PK (예: member_id). INSERT 전에 시퀀스로 먼저 받아 둔다
     */
    public Encrypted encrypt(String plain, PiiField field, String rowId) {
        if (plain == null || plain.isEmpty()) {
            throw new IllegalArgumentException("암호화할 값이 비어 있습니다");
        }
        byte[] plainBytes = plain.getBytes(StandardCharsets.UTF_8);
        if (plainBytes.length > MAX_PLAIN_BYTES) {
            throw new IllegalArgumentException("암호화할 값이 " + MAX_PLAIN_BYTES + "바이트를 넘습니다");
        }
        byte[] aad = aad(activeKeyId, field, rowId);
        byte[] padded = pad(plainBytes);
        Arrays.fill(plainBytes, (byte) 0);
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION); // Cipher는 스레드 안전하지 않아 호출마다 만든다
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            byte[] ctAndTag = cipher.doFinal(padded);
            byte[] body = ByteBuffer.allocate(IV_BYTES + ctAndTag.length).put(iv).put(ctAndTag).array();
            return new Encrypted(VERSION + "." + activeKeyId + "." + B64URL.encodeToString(body), activeKeyId);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("암호화 실패", e);
        } finally {
            Arrays.fill(padded, (byte) 0);
        }
    }

    /** 숫자 PK용. "1001"과 "01001"처럼 같은 행이 다른 AAD가 되는 실수를 막으므로 이쪽을 권장한다. */
    public Encrypted encrypt(String plain, PiiField field, long rowId) {
        return encrypt(plain, field, Long.toString(rowId));
    }

    // ---------- 복호화 ----------

    /** 숫자 PK용 ({@link #encrypt(String, PiiField, long)}와 짝). */
    public String decrypt(Encrypted encrypted, PiiField field, long rowId) {
        return decrypt(encrypted, field, Long.toString(rowId));
    }

    /** 숫자 PK용, 키 ID 컬럼이 없는 곳. */
    public String decrypt(String cipherText, PiiField field, long rowId) {
        return decrypt(cipherText, field, Long.toString(rowId));
    }

    /**
     * 저장된 두 컬럼을 그대로 넘긴다. 컬럼의 keyId와 암호문 안의 키 ID가 다르면 누군가 손댄 것이므로 실패한다.
     */
    public String decrypt(Encrypted encrypted, PiiField field, String rowId) {
        if (encrypted == null) {
            throw new PiiDecryptException("암호문이 없습니다");
        }
        String embedded = parse(encrypted.cipherText()).keyId;
        if (encrypted.keyId() != null && !encrypted.keyId().equals(embedded)) {
            throw new PiiDecryptException("키 ID 컬럼과 암호문 안의 키 ID가 다릅니다");
        }
        return decrypt(encrypted.cipherText(), field, rowId);
    }

    /** 키 ID 컬럼이 없는 곳(admin_user.mfa_secret_enc)용. 키 ID는 암호문 안에서 읽는다. */
    public String decrypt(String cipherText, PiiField field, String rowId) {
        Parsed p = parse(cipherText);
        SecretKeySpec key = keys.get(p.keyId);
        if (key == null) {
            throw new PiiDecryptException("알 수 없는 키 ID: " + p.keyId);
        }
        byte[] aad = aad(p.keyId, field, rowId);
        byte[] padded = null;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, p.body, 0, IV_BYTES));
            cipher.updateAAD(aad);
            padded = cipher.doFinal(p.body, IV_BYTES, p.body.length - IV_BYTES);
            byte[] plain = unpad(padded);
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(plain)).toString();
            } catch (CharacterCodingException e) {
                throw new PiiDecryptException("복호화 결과가 UTF-8이 아닙니다");
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } catch (AEADBadTagException e) {
            // 변조·키 불일치·컬럼/행 불일치를 구분하지 않는다 (공격자에게 힌트를 주지 않음)
            throw new PiiDecryptException("복호화 실패: 암호문이 변조되었거나 다른 키·컬럼·행의 값입니다");
        } catch (GeneralSecurityException e) {
            throw new PiiDecryptException("복호화 실패", e);
        } finally {
            if (padded != null) Arrays.fill(padded, (byte) 0);
        }
    }

    /** 키 교체 배치용: 이 암호문이 몇 번 키로 암호화됐는지 (복호화하지 않음). */
    public static String keyIdOf(String cipherText) {
        return parse(cipherText).keyId;
    }

    // ---------- 내부 ----------

    private record Parsed(String keyId, byte[] body) {
    }

    private static Parsed parse(String cipherText) {
        if (cipherText == null) {
            throw new PiiDecryptException("암호문이 없습니다");
        }
        String[] parts = cipherText.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new PiiDecryptException("지원하지 않는 암호문 형식입니다");
        }
        if (!KEY_ID.matcher(parts[1]).matches()) {
            throw new PiiDecryptException("암호문의 키 ID 형식이 잘못되었습니다");
        }
        byte[] body;
        try {
            body = B64URL_DEC.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new PiiDecryptException("암호문이 올바른 Base64가 아닙니다");
        }
        if (body.length < IV_BYTES + PAD_BLOCK + TAG_BITS / 8 || (body.length - IV_BYTES - TAG_BITS / 8) % PAD_BLOCK != 0) {
            throw new PiiDecryptException("암호문 길이가 잘못되었습니다");
        }
        return new Parsed(parts[1], body);
    }

    private static byte[] aad(String keyId, PiiField field, String rowId) {
        if (field == null) {
            throw new IllegalArgumentException("암호화 컬럼(PiiField)이 없습니다");
        }
        if (rowId == null || !ROW_ID.matcher(rowId).matches()) {
            throw new IllegalArgumentException("행 ID는 영문·숫자·_·- 1~64자여야 합니다");
        }
        return String.join("|", AAD_PREFIX, VERSION, keyId, field.toString(), rowId)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** ISO/IEC 7816-4: 0x80 한 바이트 뒤에 0x00을 채워 16의 배수로 만든다 (항상 1바이트 이상 붙음). */
    static byte[] pad(byte[] in) {
        int len = (in.length / PAD_BLOCK + 1) * PAD_BLOCK;
        byte[] out = Arrays.copyOf(in, len);
        out[in.length] = (byte) 0x80;
        return out;
    }

    static byte[] unpad(byte[] in) {
        int i = in.length - 1;
        while (i >= 0 && in[i] == 0) i--;
        if (i < 0 || in[i] != (byte) 0x80) {
            // 태그 검증을 통과한 뒤라 정상이면 일어날 수 없다 (키를 가진 쪽이 만든 잘못된 값)
            throw new PiiDecryptException("패딩이 잘못되었습니다");
        }
        return Arrays.copyOf(in, i);
    }

    /** 키가 로그·예외에 섞여 나가지 않게 내용을 숨긴다. */
    @Override
    public String toString() {
        return "PiiCipher[keys=" + keys.keySet() + ", active=" + activeKeyId + "]";
    }
}
