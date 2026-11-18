package com.jejari.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 정상 회원 유형 비율 (조영준 가상데이터 계획 3장, 출장자 근거는 DATA-04 6절) */
class SyntheticProfileTest {

	private static Map<String, Long> profiles(int count) {
		return IntStream.range(0, count).mapToObj(SyntheticDataService::normalProfile)
				.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
	}

	@Test
	void 직전_취소가_잦은_출장자는_출장자의_2_5퍼센트() {
		Map<String, Long> p = profiles(2000); // 출장자 80명
		assertThat(p.get("biz") + p.get("biz_lm")).isEqualTo(80);
		assertThat(p.get("biz_lm")).isEqualTo(2);
	}

	@Test
	void 인원이_적어도_직전_취소_출장자가_한_명은_생긴다() {
		assertThat(profiles(50).get("biz_lm")).isEqualTo(1);
	}

	@Test
	void 다른_유형_비율은_그대로() {
		Map<String, Long> p = profiles(1000);
		assertThat(p.get("family")).isEqualTo(60);
		assertThat(p.get("replan")).isEqualTo(40);
		assertThat(p.get("plain")).isEqualTo(860);
	}
}
