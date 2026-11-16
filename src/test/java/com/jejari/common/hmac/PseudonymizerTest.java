package com.jejari.common.hmac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class PseudonymizerTest {

    /** 0x00~0x1f 32바이트. 테스트 전용 키 */
    private static final byte[] KEY = new byte[32];
    static {
        for (int i = 0; i < 32; i++) KEY[i] = (byte) i;
    }
    private final Pseudonymizer p = new Pseudonymizer(KEY);

    // ---------- 알고리즘이 표준 그대로인지 ----------

    @Test
    void RFC4231_시험값과_HMAC_SHA256_결과가_같다() {
        // RFC 4231 Test Case 6: 키가 블록 크기(64바이트)보다 긴 경우
        byte[] key = new byte[131];
        Arrays.fill(key, (byte) 0xaa);
        String hex = Pseudonymizer.hmacHex(new SecretKeySpec(key, "HmacSHA256"),
                "Test Using Larger Than Block-Size Key - Hash Key First".getBytes(StandardCharsets.US_ASCII));
        assertThat(hex).isEqualTo("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54");
    }

    /**
     * 저장된 해시와의 호환을 지키는 고정값. 이 테스트가 깨지면 정규화나 메시지 형식이 바뀐 것이고,
     * 그대로 배포하면 DB의 모든 해시와 새 해시가 이어지지 않아 위험 점수·매수 제한 묶음이 끊긴다.
     * (기대값은 Python hmac으로 따로 계산했다.)
     */
    @Test
    void 저장된_해시와_호환되는_고정값() {
        assertThat(p.ci("ci-sample-1")).isEqualTo("565c306773452036dc779e04056b012b80e9049a81b72d384d4ac44aae0f9aaf");
        assertThat(p.phone("010-1234-5678")).isEqualTo("7c61b24d344feaa1e38651b97d2541e1039e77a1365fc20c4db0e25b35b05f11");
        assertThat(p.email("User@Example.com")).isEqualTo("bfcdbe281a9e36d13c32f1f4fa1c9b22bff43f7e0185360c00a97234b12ba2db");
        assertThat(p.paymentMethod("4111-1111-1111-1111")).isEqualTo("4b3ecfe8cf9d7ed68ed19020cd285d88aa26cb4eef043b95e8be1916ef7e682d");
    }

    // ---------- 출력 형식 ----------

    @Test
    void 해시는_소문자_16진수_64자라_VARCHAR2_64에_들어간다() {
        for (Purpose purpose : Purpose.values()) {
            String h = p.hash(purpose, sample(purpose));
            assertThat(h).hasSize(64).matches("[0-9a-f]{64}");
        }
    }

    @Test
    void 같은_입력은_항상_같은_해시다() {
        assertThat(p.ci("abc")).isEqualTo(p.ci("abc"));
        assertThat(p.ci("abc")).isNotEqualTo(p.ci("abd"));
    }

    @Test
    void 키가_다르면_해시가_다르다() {
        byte[] other = KEY.clone();
        other[0] ^= 1;
        assertThat(new Pseudonymizer(other).ci("abc")).isNotEqualTo(p.ci("abc"));
    }

    @Test
    void 같은_문자열도_종류가_다르면_해시가_다르다() {
        // CI·기기 지문은 형식 제한이 없어서 같은 문자열을 넣을 수 있다
        assertThat(p.hash(Purpose.CI, "same-value")).isNotEqualTo(p.hash(Purpose.DEVICE, "same-value"));
        assertThat(p.hash(Purpose.DEVICE, "same-value")).isNotEqualTo(p.hash(Purpose.IP, "same-value"));
    }

    // ---------- 정규화: 표기만 다른 같은 값은 같은 해시 ----------

    @Test
    void 휴대폰_번호_표기가_달라도_같은_해시다() {
        String base = p.phone("01012345678");
        assertThat(p.phone("010-1234-5678")).isEqualTo(base);
        assertThat(p.phone(" 010 1234 5678 ")).isEqualTo(base);
        assertThat(p.phone("+82 10-1234-5678")).isEqualTo(base);
        assertThat(p.phone("82-10-1234-5678")).isEqualTo(base);
        assertThat(p.phone("010-1234-5679")).isNotEqualTo(base);
    }

    @Test
    void 이메일은_대소문자와_앞뒤_공백을_무시한다() {
        assertThat(p.email("  User@Example.COM ")).isEqualTo(p.email("user@example.com"));
    }

    @Test
    void 카드번호는_공백과_하이픈을_무시한다() {
        assertThat(p.paymentMethod("4111 1111 1111 1111")).isEqualTo(p.paymentMethod("4111111111111111"));
        assertThat(p.paymentMethod("4111-1111-1111-1111")).isEqualTo(p.paymentMethod("4111111111111111"));
    }

    @Test
    void 모의_CI는_같은_사람이면_표기가_달라도_같다() {
        String a = p.mockCiHash("김성원", "010-1234-5678");
        assertThat(p.mockCiHash(" 김 성원 ", "+82 10 1234 5678")).isEqualTo(a);
        assertThat(p.mockCiHash("김성원", "010-1234-5679")).isNotEqualTo(a);
        assertThat(p.mockCiHash("김성운", "010-1234-5678")).isNotEqualTo(a);
    }

    @Test
    void 이름의_유니코드_표기_차이도_같은_사람으로_본다() {
        // 'ㄱㅣㅁ' 자모 분리(NFD)와 완성형(NFC)은 같은 글자
        String nfc = "김성원";
        String nfd = java.text.Normalizer.normalize(nfc, java.text.Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo(nfc);
        assertThat(p.mockCiHash(nfd, "01012345678")).isEqualTo(p.mockCiHash(nfc, "01012345678"));
    }

    // ---------- 잘못된 입력 ----------

    @Test
    void 비었거나_형식이_틀린_입력은_거부한다() {
        for (Purpose purpose : Purpose.values()) {
            assertThatThrownBy(() -> p.hash(purpose, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> p.hash(purpose, "  ")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> p.phone("12345")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.phone("abc010-1234-5678")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.email("not-an-email")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.paymentMethod("1234")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.paymentMethod("4111-1111-1111-abcd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.mockCiHash("", "01012345678")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 오류_메시지에_원문이_들어가지_않는다() {
        String secret = "010-9999-8888x";
        assertThatThrownBy(() -> p.phone(secret))
                .hasMessageNotContaining("9999").hasMessageNotContaining(secret);
        assertThatThrownBy(() -> p.email("leak@@x"))
                .hasMessageNotContaining("leak");
    }

    // ---------- 키 관리 ----------

    @Test
    void 짧거나_없는_키는_거부한다() {
        assertThatThrownBy(() -> new Pseudonymizer(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pseudonymizer(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pseudonymizer.fromBase64(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pseudonymizer.fromBase64(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pseudonymizer.fromBase64(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 잘못된_Base64_오류에_키_원문을_싣지_않는다() {
        String bad = "this-is-not-base64-SECRETSECRETSECRET!!";
        assertThatThrownBy(() -> Pseudonymizer.fromBase64(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("SECRET");
    }

    @Test
    void Base64로_만든_객체는_같은_키의_객체와_결과가_같다() {
        Pseudonymizer fromB64 = Pseudonymizer.fromBase64(Base64.getEncoder().encodeToString(KEY));
        assertThat(fromB64.ci("abc")).isEqualTo(p.ci("abc"));
    }

    @Test
    void 넘겨준_키_배열을_나중에_바꿔도_영향이_없다() {
        byte[] k = KEY.clone();
        Pseudonymizer local = new Pseudonymizer(k);
        String before = local.ci("abc");
        Arrays.fill(k, (byte) 0);
        assertThat(local.ci("abc")).isEqualTo(before);
    }

    @Test
    void toString에_키가_나오지_않는다() {
        assertThat(p.toString()).doesNotContain("0102").contains("****");
    }

    // ---------- 동시성 ----------

    @Test
    void 여러_스레드에서_동시에_써도_결과가_같다() throws Exception {
        var pool = Executors.newFixedThreadPool(16);
        String expected = p.ci("shared");
        List<Callable<String>> jobs = java.util.stream.IntStream.range(0, 2000)
                .<Callable<String>>mapToObj(i -> () -> p.ci("shared")).toList();
        Set<String> results = new HashSet<>();
        for (var f : pool.invokeAll(jobs)) results.add(f.get());
        pool.shutdown();
        assertThat(results).containsExactly(expected);
    }

    private static String sample(Purpose purpose) {
        return switch (purpose) {
            case CI -> "ci-1";
            case PHONE -> "01012345678";
            case EMAIL -> "a@b.com";
            case PAYMENT -> "4111111111111111";
            case DEVICE -> "fp-1";
            case IP -> "203.0.113.7";
        };
    }
}
