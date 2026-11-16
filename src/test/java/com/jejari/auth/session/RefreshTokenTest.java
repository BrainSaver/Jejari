package com.jejari.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RefreshTokenTest {

	static final String SID = "3f2b8c1e-1111-4222-8333-444455556666";

	@Test
	void 만든_값을_다시_해석하면_같다() {
		RefreshToken token = RefreshToken.generate(SID);
		assertThat(RefreshToken.parse(token.value())).contains(token);
		assertThat(token.hash()).hasSize(64).matches("[0-9a-f]{64}");
	}

	@Test
	void 매번_다른_비밀값() {
		assertThat(RefreshToken.generate(SID).secret()).isNotEqualTo(RefreshToken.generate(SID).secret());
	}

	@Test
	void 형식이_틀리면_빈_값() {
		assertThat(RefreshToken.parse(null)).isEmpty();
		assertThat(RefreshToken.parse("")).isEmpty();
		assertThat(RefreshToken.parse(SID)).isEmpty();
		assertThat(RefreshToken.parse("not-uuid." + "a".repeat(43))).isEmpty();
		assertThat(RefreshToken.parse(SID + ".short")).isEmpty();
		assertThat(RefreshToken.parse(SID + "." + "a".repeat(42) + "'")).isEmpty();
	}

	@Test
	void toString에_비밀값이_나오지_않는다() {
		RefreshToken token = RefreshToken.generate(SID);
		assertThat(token.toString()).doesNotContain(token.secret());
	}
}
