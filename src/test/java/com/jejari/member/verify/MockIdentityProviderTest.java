package com.jejari.member.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.jejari.common.error.BusinessException;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.member.verify.MockIdentityProvider.Provider;

class MockIdentityProviderTest {

	static Pseudonymizer pseudonymizer() {
		byte[] k = new byte[32];
		new SecureRandom().nextBytes(k);
		return Pseudonymizer.fromBase64(Base64.getEncoder().encodeToString(k));
	}

	final MockIdentityProvider p = new MockIdentityProvider(pseudonymizer(), Clock.systemUTC());

	@Test
	void 표기만_다른_같은_사람은_같은_CI() {
		VerifiedIdentity a = p.verify("홍길동", "010-1234-5678", Provider.MOCK_PASS);
		VerifiedIdentity b = p.verify("  홍길동 ", "+82 10 1234 5678", Provider.MOCK_PHONE);
		assertThat(a.ciHash()).isEqualTo(b.ciHash()).matches("[0-9a-f]{64}");
		assertThat(a.phone()).isEqualTo("01012345678");
		assertThat(a.toString()).doesNotContain("홍길동", "01012345678");
	}

	@Test
	void 다른_사람은_다른_CI() {
		assertThat(p.verify("홍길동", "01012345678", Provider.MOCK_PASS).ciHash())
				.isNotEqualTo(p.verify("홍길순", "01012345678", Provider.MOCK_PASS).ciHash());
	}

	@Test
	void 번호가_0000으로_끝나면_실패() {
		assertThatThrownBy(() -> p.verify("홍길동", "010-1234-0000", Provider.MOCK_PASS))
				.isInstanceOf(BusinessException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"홍", "홍길동1", "<script>", "홍\u0000길동", "Robert'); DROP TABLE member;--", ""})
	void 이름_형식_오류(String name) {
		assertThatThrownBy(() -> p.verify(name, "01012345678", Provider.MOCK_PASS)).isInstanceOf(BusinessException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"02-123-4567", "010-1234-567", "0101234567890", "010.1234.5678", "phone"})
	void 휴대폰_형식_오류(String phone) {
		assertThatThrownBy(() -> p.verify("홍길동", phone, Provider.MOCK_PASS)).isInstanceOf(BusinessException.class);
	}

	@Test
	void 인증_수단이_없으면_실패() {
		assertThatThrownBy(() -> p.verify("홍길동", "01012345678", null)).isInstanceOf(BusinessException.class);
	}
}
