package com.jejari.common.pii;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class PiiMaskerTest {

    @ParameterizedTest
    @CsvSource({
            "홍길동, 홍*동",
            "남궁민수, 남**수",
            "홍길, 홍*",
            "홍, *",
            "' 홍길동 ', 홍*동",
            "John Smith, J********h",
    })
    void 이름(String in, String expected) {
        assertThat(PiiMasker.name(in)).isEqualTo(expected);
    }

    @Test
    void 이름_서로게이트_문자도_한_글자로_센다() {
        assertThat(PiiMasker.name("𠀀길동")).isEqualTo("𠀀*동");
    }

    @ParameterizedTest
    @CsvSource({
            "010-1234-5678, 010-****-5678",
            "01012345678, 010-****-5678",
            "+82 10-1234-5678, 010-****-5678",
            "011-123-4567, 011-***-4567",
            "12345, *2345",
            "123, ***",
    })
    void 휴대폰(String in, String expected) {
        assertThat(PiiMasker.phone(in)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "abcdef@example.com, ab***@example.com",
            "ab@example.com, a***@example.com",
            "a@x.com, a***@x.com",
            "' Hong.GD@jejari.kr ', Ho***@jejari.kr",
            "not-an-email, ***",
            "@example.com, ***",
            "abc@, ***",
    })
    void 이메일(String in, String expected) {
        assertThat(PiiMasker.email(in)).isEqualTo(expected);
    }

    @Test
    void null과_빈_값은_그대로() {
        assertThat(PiiMasker.name(null)).isNull();
        assertThat(PiiMasker.phone(null)).isNull();
        assertThat(PiiMasker.email(null)).isNull();
        assertThat(PiiMasker.name("")).isEmpty();
        assertThat(PiiMasker.phone("")).isEmpty();
        assertThat(PiiMasker.email("")).isEmpty();
    }
}
