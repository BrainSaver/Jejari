package com.jejari.behavior.sink;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SinkPacerTest {
	private static final long SEC = 1_000_000_000L;

	@Test
	void 상한만큼은_바로_그_다음부터는_기다린다() {
		SinkPacer p = new SinkPacer(1000); // 초당 1000건
		long now = 10 * SEC;
		assertThat(p.reserve(1000, now)).isZero();           // 한가했으니 첫 1초치는 바로 (1초 전부터 모아 둔 몫)
		assertThat(p.reserve(1000, now)).isZero();           // 그 1초치가 끝나는 시각 = 지금
		assertThat(p.reserve(1000, now)).isEqualTo(SEC);     // 그다음 묶음은 1초 뒤
		assertThat(p.reserve(500, now)).isEqualTo(2 * SEC);
	}

	@Test
	void 오래_쉬어도_1초치만_몰아_넣는다() {
		SinkPacer p = new SinkPacer(1000);
		long later = 3600 * SEC;
		assertThat(p.reserve(1000, later)).isZero();
		assertThat(p.reserve(1000, later)).isZero();
		assertThat(p.reserve(1000, later)).isEqualTo(SEC);
	}

	@Test
	void 꺼져_있으면_기다리지_않는다() {
		SinkPacer p = new SinkPacer(0);
		assertThat(p.reserve(1_000_000, 0)).isZero();
	}
}
