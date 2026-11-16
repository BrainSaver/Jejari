package com.jejari.risk.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.jejari.risk.SubjectType;

/** 캐시 값·키 형식. 대기열(A)이 Double.parseDouble로 읽으므로 숫자 문자열만, 키는 QueueKeys.risk와 같아야 한다. */
class RiskScoreCacheFormatTest {

	static final String H = "a".repeat(64);

	@Test
	void 값은_소수_둘째자리_숫자_문자열이고_대기열이_그대로_읽는다() {
		String v = RiskScoreCache.format(new BigDecimal("80.5"));
		assertThat(v).isEqualTo("80.50");
		assertThat(Double.parseDouble(v)).isEqualTo(80.5);
	}

	@Test
	void 지수_표기가_나오지_않는다() {
		assertThat(RiskScoreCache.format(new BigDecimal("1E+2"))).isEqualTo("100.00");
		assertThat(RiskScoreCache.format(new BigDecimal("1E-1"))).isEqualTo("0.10");
	}

	@Test
	void 영점이면_null_키_삭제() {
		assertThat(RiskScoreCache.format(BigDecimal.ZERO)).isNull();
		assertThat(RiskScoreCache.format(new BigDecimal("0.004"))).isNull(); // 반올림하면 0.00
	}

	@ParameterizedTest
	@ValueSource(strings = {"-0.01", "100.01", "1000"})
	void 범위_밖_점수는_거부(String s) {
		assertThatThrownBy(() -> RiskScoreCache.format(new BigDecimal(s))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 키는_대기열과_같은_형식() {
		assertThat(RiskScoreCache.key(SubjectType.PAYMENT, H)).isEqualTo("risk:PAYMENT:" + H);
	}

	@ParameterizedTest
	@ValueSource(strings = {"01012345678", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "*"})
	void 해시가_아니면_키를_만들지_않는다(String notHash) {
		// 원문 전화번호, 대문자, 와일드카드(SCAN 패턴 주입) 모두 거부. 메시지에 값을 싣지 않는다
		assertThatThrownBy(() -> RiskScoreCache.key(SubjectType.CI, notHash))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageNotContaining(notHash);
	}
}
