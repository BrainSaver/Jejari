package com.jejari.behavior.sink;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class IntervalTrackerTest {

	final Instant t0 = Instant.parse("2026-11-20T01:00:00Z");

	@Test
	void 첫_이벤트는_null_다음부터_간격_묶음_안에서도_이어진다() {
		IntervalTracker t = new IntervalTracker(10);
		var b = t.batch();
		assertThat(b.next("s1", t0)).isNull();
		assertThat(b.next("s1", t0.plusMillis(120))).isEqualTo(120);
		assertThat(b.next("s2", t0.plusMillis(200))).as("다른 세션").isNull();
		b.commit();
		var b2 = t.batch();
		assertThat(b2.next("s1", t0.plusMillis(200))).as("다음 묶음은 기억한 시각과 비교").isEqualTo(80);
	}

	@Test
	void 저장_실패로_같은_묶음을_다시_처리해도_간격이_같다() {
		IntervalTracker t = new IntervalTracker(10);
		var ok = t.batch();
		ok.next("s", t0);
		ok.commit();

		var first = t.batch();
		assertThat(first.next("s", t0.plusMillis(300))).isEqualTo(300);
		// 저장 실패 → commit 안 함 → 같은 묶음 재처리
		var retry = t.batch();
		assertThat(retry.next("s", t0.plusMillis(300))).as("0이 되면 정상 사용자가 초고속 클릭으로 찍힘").isEqualTo(300);
	}

	@Test
	void 순서가_뒤바뀐_이벤트는_null_기준_시각은_유지() {
		IntervalTracker t = new IntervalTracker(10);
		var b = t.batch();
		b.next("s", t0.plusMillis(500));
		assertThat(b.next("s", t0)).isNull();
		assertThat(b.next("s", t0.plusMillis(600))).isEqualTo(100);
	}

	@Test
	void 세션이_없으면_계산하지_않고_오래된_세션은_버린다() {
		IntervalTracker t = new IntervalTracker(2);
		var b = t.batch();
		assertThat(b.next(null, t0)).isNull();
		b.next("a", t0);
		b.next("b", t0);
		b.next("c", t0);
		b.commit(); // 용량 2 → a가 밀려남
		var b2 = t.batch();
		assertThat(b2.next("a", t0.plusMillis(10))).isNull();
		assertThat(b2.next("c", t0.plusMillis(10))).isEqualTo(10);
	}
}
