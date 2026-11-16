package com.jejari.member.password;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.jejari.auth.AuthErrorCode;
import com.jejari.common.error.BusinessException;

class PasswordPolicyTest {

	static final String EMAIL = "hong.gildong@example.com";

	@ParameterizedTest
	@ValueSource(strings = {"Blue-Whale-42", "기차표예매는공정하게", "correct horse battery", "x9#Kd!2mQ0"})
	void 통과(String pw) {
		assertThatCode(() -> PasswordPolicy.validate(pw, EMAIL)).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"short1!", // 10자 미만
			"aaaaaaaaaaaa", "1212121212", // 문자 종류 4개 미만
			"1234567890", "Password123", "qwertyuiop", "Jejari1234", // 흔한·연속
			"abcdefghijk", "zxcvbnm123!", // 연속 패턴
			" leading-space-pw", "trailing-space-pw ", // 앞뒤 공백
			"tab\tinside-pw", // 제어 문자
			"hong.gildong-2026", "HONG.GILDONG@example.com"}) // 이메일 아이디 포함
	void 거절(String pw) {
		assertThatThrownBy(() -> PasswordPolicy.validate(pw, EMAIL))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> {
					BusinessException b = (BusinessException) e;
					org.assertj.core.api.Assertions.assertThat(b.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_PASSWORD_POLICY);
					// 메시지에 입력한 비밀번호가 들어가지 않는다
					org.assertj.core.api.Assertions.assertThat(b.getMessage()).doesNotContain(pw.strip());
				});
	}

	@Test
	void 너무_길면_거절_해시_폭탄_방지() {
		assertThatThrownBy(() -> PasswordPolicy.validate("Ab1!".repeat(17), EMAIL)).isInstanceOf(BusinessException.class);
		// 64자여도 바이트가 256을 넘으면 거절 (4바이트 이모지 65개 아님, 64자 × 4바이트 = 256은 허용 경계)
		assertThatThrownBy(() -> PasswordPolicy.validate("😀".repeat(65), EMAIL)).isInstanceOf(BusinessException.class);
	}

	@Test
	void null은_거절() {
		assertThatThrownBy(() -> PasswordPolicy.validate(null, EMAIL)).isInstanceOf(BusinessException.class);
	}
}
