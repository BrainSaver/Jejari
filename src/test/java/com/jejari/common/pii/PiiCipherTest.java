package com.jejari.common.pii;

import static com.jejari.common.pii.PiiField.ADMIN_MFA_SECRET;
import static com.jejari.common.pii.PiiField.MEMBER_PHONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PiiCipherTest {

    private static final byte[] K1 = key(1);
    private static final byte[] K2 = key(2);

    private static byte[] key(int seed) {
        byte[] k = new byte[32];
        for (int i = 0; i < 32; i++) k[i] = (byte) (i * 7 + seed);
        return k;
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static Map<String, byte[]> keys(Object... idAndKey) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < idAndKey.length; i += 2) m.put((String) idAndKey[i], (byte[]) idAndKey[i + 1]);
        return m;
    }

    private final PiiCipher cipher = new PiiCipher(keys("k1", K1), "k1");

    // ==================== 기본 동작 ====================

    @Nested
    @DisplayName("기본")
    class Basics {

        @Test
        void 암호화한_값을_같은_컬럼_같은_행으로_복호화하면_원문() {
            Encrypted e = cipher.encrypt("010-1234-5678", MEMBER_PHONE, "1001");
            assertThat(e.keyId()).isEqualTo("k1");
            assertThat(e.cipherText()).startsWith("v1.k1.").doesNotContain("1234");
            assertThat(cipher.decrypt(e, MEMBER_PHONE, "1001")).isEqualTo("010-1234-5678");
            assertThat(cipher.decrypt(e.cipherText(), MEMBER_PHONE, "1001")).isEqualTo("010-1234-5678");
        }

        @Test
        void 한글_이모지_최대길이도_그대로_복원된다() {
            for (String plain : List.of("홍길동 🚄", "JBSWY3DPEHPK3PXP", "a", "x".repeat(PiiCipher.MAX_PLAIN_BYTES),
                    "끝이0x80처럼보이는\u0080값", "\u0000앞뒤널\u0000")) {
                assertThat(cipher.decrypt(cipher.encrypt(plain, MEMBER_PHONE, "1"), MEMBER_PHONE, "1")).isEqualTo(plain);
            }
        }

        @Test
        void 같은_값을_1000번_암호화하면_암호문이_모두_다르다() {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                seen.add(cipher.encrypt("01012345678", MEMBER_PHONE, "1").cipherText());
            }
            assertThat(seen).hasSize(1000); // IV가 매번 다르다
        }

        @Test
        void 최대_길이_암호문도_VARCHAR2_200에_들어간다() {
            String longest = cipher.encrypt("x".repeat(PiiCipher.MAX_PLAIN_BYTES), MEMBER_PHONE, "1").cipherText();
            assertThat(longest.length()).isLessThanOrEqualTo(200);
            assertThat(cipher.encrypt("01012345678", MEMBER_PHONE, "1").cipherText()).hasSize(65);
        }

        @Test
        void 평문_길이가_16바이트_단위로_가려진다() {
            // 10자리·11자리 번호, 1자짜리 값이 모두 같은 길이의 암호문이 된다
            int a = cipher.encrypt("0111234567", MEMBER_PHONE, "1").cipherText().length();
            int b = cipher.encrypt("01012345678", MEMBER_PHONE, "1").cipherText().length();
            int c = cipher.encrypt("x", MEMBER_PHONE, "1").cipherText().length();
            assertThat(a).isEqualTo(b).isEqualTo(c);
        }

        @Test
        void 숫자_PK_오버로드는_문자열_PK와_같은_행으로_취급된다() {
            Encrypted e = cipher.encrypt("01012345678", MEMBER_PHONE, 1001L);
            assertThat(cipher.decrypt(e, MEMBER_PHONE, 1001L)).isEqualTo("01012345678");
            assertThat(cipher.decrypt(e, MEMBER_PHONE, "1001")).isEqualTo("01012345678");
            assertThat(cipher.decrypt(e.cipherText(), MEMBER_PHONE, 1001L)).isEqualTo("01012345678");
            assertThatThrownBy(() -> cipher.decrypt(e, MEMBER_PHONE, 1002L)).isInstanceOf(PiiDecryptException.class);
        }

        @Test
        void 키_ID_컬럼이_없는_곳은_암호문만으로_복호화한다() {
            String stored = cipher.encrypt("JBSWY3DPEHPK3PXP", ADMIN_MFA_SECRET, "7").cipherText();
            assertThat(PiiCipher.keyIdOf(stored)).isEqualTo("k1");
            assertThat(cipher.decrypt(stored, ADMIN_MFA_SECRET, "7")).isEqualTo("JBSWY3DPEHPK3PXP");
        }
    }

    // ==================== 공격: 모두 실패해야 한다 ====================

    @Nested
    @DisplayName("공격")
    class Attacks {

        private final Encrypted victim = cipher.encrypt("01012345678", MEMBER_PHONE, "1001");

        @Test
        void 행_바꿔치기_다른_회원_행에_복사한_암호문은_복호화되지_않는다() {
            assertThatThrownBy(() -> cipher.decrypt(victim, MEMBER_PHONE, "1002"))
                    .isInstanceOf(PiiDecryptException.class);
        }

        @Test
        void 컬럼_바꿔치기_다른_컬럼으로_옮긴_암호문은_복호화되지_않는다() {
            assertThatThrownBy(() -> cipher.decrypt(victim.cipherText(), ADMIN_MFA_SECRET, "1001"))
                    .isInstanceOf(PiiDecryptException.class);
        }

        @Test
        void 암호문_본문의_어느_비트를_바꿔도_실패한다() {
            String[] p = victim.cipherText().split("\\.");
            byte[] body = Base64.getUrlDecoder().decode(p[2]);
            for (int i = 0; i < body.length; i++) {
                for (int bit = 0; bit < 8; bit++) {
                    byte[] t = body.clone();
                    t[i] ^= (byte) (1 << bit);
                    String tampered = p[0] + "." + p[1] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(t);
                    assertThatThrownBy(() -> cipher.decrypt(tampered, MEMBER_PHONE, "1001"))
                            .isInstanceOf(PiiDecryptException.class);
                }
            }
        }

        @Test
        void 키_ID를_바꿔치기하면_실패한다() {
            // 두 키를 가진 서버에서 v1.k1.… 을 v1.k2.… 로 바꿈
            PiiCipher two = new PiiCipher(keys("k1", K1, "k2", K2), "k1");
            Encrypted e = two.encrypt("01012345678", MEMBER_PHONE, "1");
            String swapped = e.cipherText().replaceFirst("^v1\\.k1\\.", "v1.k2.");
            assertThatThrownBy(() -> two.decrypt(swapped, MEMBER_PHONE, "1")).isInstanceOf(PiiDecryptException.class);
            // enc_key_id 컬럼만 바꾼 경우도
            assertThatThrownBy(() -> two.decrypt(new Encrypted(e.cipherText(), "k2"), MEMBER_PHONE, "1"))
                    .isInstanceOf(PiiDecryptException.class);
        }

        @Test
        void 형식_버전을_바꾸면_실패한다() {
            String v2 = victim.cipherText().replaceFirst("^v1", "v2");
            assertThatThrownBy(() -> cipher.decrypt(v2, MEMBER_PHONE, "1001")).isInstanceOf(PiiDecryptException.class);
        }

        @Test
        void 같은_키_ID라도_키가_다르면_실패한다() {
            PiiCipher other = new PiiCipher(keys("k1", K2), "k1");
            assertThatThrownBy(() -> other.decrypt(victim, MEMBER_PHONE, "1001")).isInstanceOf(PiiDecryptException.class);
        }

        @Test
        void 깨진_형식은_모두_PiiDecryptException() {
            String body = victim.cipherText().substring("v1.k1.".length());
            for (String bad : List.of("", "v1", "v1.k1", "v1.k1.", "v1.k9." + body, "v1.K1." + body,
                    "v1.k1." + body + ".x", "v1.k1.%%%", "v1.k1." + body.substring(0, 20), "v1.k1." + body + "AA")) {
                assertThatThrownBy(() -> cipher.decrypt(bad, MEMBER_PHONE, "1001"))
                        .as(bad).isInstanceOf(PiiDecryptException.class);
            }
            assertThatThrownBy(() -> cipher.decrypt((String) null, MEMBER_PHONE, "1")).isInstanceOf(PiiDecryptException.class);
            assertThatThrownBy(() -> cipher.decrypt((Encrypted) null, MEMBER_PHONE, "1")).isInstanceOf(PiiDecryptException.class);
        }

        @Test
        void 실패_메시지에_평문_암호문이_없다() {
            assertThatThrownBy(() -> cipher.decrypt(victim, MEMBER_PHONE, "9"))
                    .message().doesNotContain("01012345678").doesNotContain(victim.cipherText());
        }

        @Test
        void 행_ID에_AAD_구분자를_넣어_경계를_흐리는_입력은_거부한다() {
            for (String rowId : List.of("1|x", "1.2", "", " ", "a".repeat(65), "한글")) {
                assertThatThrownBy(() -> cipher.encrypt("x", MEMBER_PHONE, rowId))
                        .as(rowId).isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> cipher.encrypt("x", MEMBER_PHONE, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> cipher.encrypt("x", null, "1")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PiiField("member|x", "phone_enc")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 빈_값이나_너무_긴_값은_거부한다() {
            assertThatThrownBy(() -> cipher.encrypt("", MEMBER_PHONE, "1")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> cipher.encrypt(null, MEMBER_PHONE, "1")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> cipher.encrypt("가".repeat(22), MEMBER_PHONE, "1")) // 66바이트
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================== 키 교체 ====================

    @Test
    void 활성_키를_k2로_바꿔도_k1_암호문은_복호화된다() {
        Encrypted old = cipher.encrypt("01012345678", MEMBER_PHONE, "1");

        PiiCipher rotated = new PiiCipher(keys("k1", K1, "k2", K2), "k2");
        Encrypted fresh = rotated.encrypt("01098765432", MEMBER_PHONE, "2");

        assertThat(fresh.keyId()).isEqualTo("k2");
        assertThat(fresh.cipherText()).startsWith("v1.k2.");
        assertThat(rotated.decrypt(old, MEMBER_PHONE, "1")).isEqualTo("01012345678");
        assertThat(rotated.decrypt(fresh, MEMBER_PHONE, "2")).isEqualTo("01098765432");
    }

    // ==================== 키 검증: 잘못되면 기동 실패 ====================

    @Nested
    @DisplayName("키 검증")
    class KeyValidation {

        @Test
        void 환경변수_형식을_읽는다() {
            PiiCipher c = PiiCipher.fromConfig(" k1:" + b64(K1) + " , k2:" + b64(K2) + " ", "k2");
            assertThat(c.activeKeyId()).isEqualTo("k2");
            assertThat(c.decrypt(cipher.encrypt("x", MEMBER_PHONE, "1"), MEMBER_PHONE, "1")).isEqualTo("x");
        }

        @Test
        void 키_파일_내용도_같은_형식으로_읽는다(@TempDir Path dir) throws Exception {
            Path f = dir.resolve("enc_keys");
            Files.writeString(f, "k1:" + b64(K1) + "\n");
            PiiCipher c = PiiCipher.fromConfig(Files.readString(f).strip(), "k1");
            assertThat(c.decrypt(cipher.encrypt("x", MEMBER_PHONE, "1"), MEMBER_PHONE, "1")).isEqualTo("x");
        }

        @Test
        void 키가_없으면_예외() {
            assertThatThrownBy(() -> PiiCipher.fromConfig(null, "k1")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> PiiCipher.fromConfig(" ", "k1")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> PiiCipher.fromConfig("k1:" + b64(K1), "")).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void 길이가_틀린_키() {
            assertThatThrownBy(() -> PiiCipher.fromConfig("k1:" + b64(keyOfLen(1, 16)), "k1"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32바이트");
            assertThatThrownBy(() -> PiiCipher.fromConfig("k1:" + b64(keyOfLen(1, 64)), "k1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 모든_바이트가_같은_키는_거부한다() {
            assertThatThrownBy(() -> PiiCipher.fromConfig("k1:" + b64(new byte[32]), "k1"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("모든 바이트");
            byte[] ff = new byte[32];
            java.util.Arrays.fill(ff, (byte) 0xff);
            assertThatThrownBy(() -> new PiiCipher(keys("k1", ff), "k1")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 버전끼리_같은_키는_거부한다() {
            assertThatThrownBy(() -> PiiCipher.fromConfig("k1:" + b64(K1) + ",k2:" + b64(K1), "k2"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("다른 버전");
        }

        @Test
        void HMAC_키와_같은_키는_거부한다() {
            assertThatThrownBy(() -> PiiCipher.fromConfig("k1:" + b64(K1), "k1", b64(K1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HMAC");
            assertThat(PiiCipher.fromConfig("k1:" + b64(K1), "k1", b64(K2)).activeKeyId()).isEqualTo("k1");
        }

        @Test
        void 키_ID_규칙과_목록_형식() {
            assertThatThrownBy(() -> PiiCipher.fromConfig("k1:" + b64(K1), "k2")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PiiCipher.fromConfig("K1:" + b64(K1), "K1")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PiiCipher.fromConfig("k.1:" + b64(K1), "k.1")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PiiCipher.fromConfig("k1:!!!", "k1")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PiiCipher.fromConfig(b64(K1), "k1")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PiiCipher.fromConfig("k1:" + b64(K1) + ",k1:" + b64(K2), "k1"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("중복");
        }

        @Test
        void 오류_메시지와_toString에_키_값이_없다() {
            String bad = b64(keyOfLen(3, 16));
            assertThatThrownBy(() -> PiiCipher.fromConfig("k1:" + bad, "k1")).message().doesNotContain(bad);
            assertThatThrownBy(() -> PiiCipher.fromConfig("k1:" + b64(K1), "k1", b64(K1)))
                    .message().doesNotContain(b64(K1));
            assertThat(cipher.toString()).doesNotContain(b64(K1)).contains("k1");
            assertThat(cipher.encrypt("01012345678", MEMBER_PHONE, "1").toString()).doesNotContain("v1.k1.");
        }

        @Test
        void 넘긴_키_배열을_나중에_바꿔도_영향이_없다() {
            byte[] k = PiiCipherTest.key(5);
            PiiCipher c = new PiiCipher(keys("k1", k), "k1");
            Encrypted e = c.encrypt("01012345678", MEMBER_PHONE, "1");
            k[0] ^= 0x7f;
            assertThat(c.decrypt(e, MEMBER_PHONE, "1")).isEqualTo("01012345678");
        }

        private static byte[] keyOfLen(int seed, int len) {
            byte[] k = new byte[len];
            for (int i = 0; i < len; i++) k[i] = (byte) (i * 7 + seed);
            return k;
        }
    }

    // ==================== 패딩·동시성 ====================

    @Test
    void 패딩은_항상_16의_배수이고_되돌리면_원래_바이트() {
        for (int n = 0; n <= 70; n++) {
            byte[] in = new byte[n];
            for (int i = 0; i < n; i++) in[i] = (byte) (i % 3 == 0 ? 0x80 : 0); // 일부러 0x80·0x00 섞음
            byte[] padded = PiiCipher.pad(in);
            assertThat(padded.length % 16).isZero();
            assertThat(padded.length).isGreaterThan(n);
            assertThat(PiiCipher.unpad(padded)).isEqualTo(in);
        }
    }

    @Test
    void 여러_스레드에서_동시에_써도_안전하다() throws Exception {
        try (var pool = Executors.newFixedThreadPool(16)) {
            List<Callable<Boolean>> jobs = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                String plain = "010" + String.format("%08d", i);
                String row = String.valueOf(i);
                jobs.add(() -> plain.equals(cipher.decrypt(cipher.encrypt(plain, MEMBER_PHONE, row), MEMBER_PHONE, row)));
            }
            for (var f : pool.invokeAll(jobs)) {
                assertThat(f.get()).isTrue();
            }
        }
    }
}
