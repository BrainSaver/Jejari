package com.jejari.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jejari.common.error.BusinessException;

class ReplayBatchTest {

	static final Instant NOW = Instant.parse("2026-11-18T05:00:00Z");
	static final long T = NOW.toEpochMilli();

	static ReplayBatch.In in(long seq, String type, String screen, String target) {
		return new ReplayBatch.In(seq, T, type, screen, target, 10, 20, null, null, null);
	}

	@Test
	void 요소_ID와_화면_ID는_짧은_코드만_받는다() {
		List<ReplayBatch.Event> out = ReplayBatch.sanitize(List.of(
				in(1, "CLICK", "runs/:id/seats", "seat-105007"),
				in(2, "CLICK", "home", "홍길동 010-1234-5678"),
				in(3, "CLICK", "<script>", "x")), "FULL", 50, NOW);
		assertThat(out).hasSize(2); // 화면 ID가 이상하면 재생할 수 없어 버림
		assertThat(out.get(0).target()).isEqualTo("seat-105007");
		assertThat(out.get(1).target()).isNull(); // 이름·번호 같은 문장은 요소 ID로 받지 않음
	}

	@Test
	void 마우스_궤적은_FULL은_정책값까지_SAMPLED는_그_3분의_1까지() {
		List<List<Number>> path = new ArrayList<>();
		for (int i = 0; i < 80; i++) {
			path.add(List.of(i * 10, i * 100, -5));
		}
		ReplayBatch.In mouse = new ReplayBatch.In(1L, T, "MOUSE_PATH", "home", null, null, null, path, null, null);
		assertThat(ReplayBatch.sanitize(List.of(mouse), "SAMPLED", 50, NOW).get(0).path()).hasSize(16); // 성기게
		ReplayBatch.Event e = ReplayBatch.sanitize(List.of(mouse), "FULL", 50, NOW).get(0);
		assertThat(e.path()).hasSize(50);
		assertThat(e.path().get(20)).containsExactly(200, 1000, 0); // 좌표 0~1000으로
	}

	@Test
	void 입력은_길이만_시각이_하루_넘게_어긋나면_버림() {
		ReplayBatch.In input = new ReplayBatch.In(1L, T, "INPUT", "login", "em", null, null, null, 99_999, null);
		ReplayBatch.In old = new ReplayBatch.In(2L, T - 2L * 86_400_000, "CLICK", "home", null, 1, 1, null, null, null);
		List<ReplayBatch.Event> out = ReplayBatch.sanitize(List.of(input, old), "FULL", 50, NOW);
		assertThat(out).hasSize(1);
		assertThat(out.get(0).inputLen()).isEqualTo(1000);
	}

	@Test
	void 버튼_문구는_숫자_없는_짧은_글자만() {
		ReplayBatch.In ok = new ReplayBatch.In(1L, T, "CLICK", "pay/:id", null, 1, 1, null, null, null, "  결제하기 ", null);
		ReplayBatch.In phone = new ReplayBatch.In(2L, T, "CLICK", "me", null, 1, 1, null, null, null, "010-1234-5678", null);
		ReplayBatch.In mail = new ReplayBatch.In(3L, T, "CLICK", "me", null, 1, 1, null, null, null, "a@b.com", null);
		ReplayBatch.In card = new ReplayBatch.In(4L, T, "CLICK", "pay/:id", null, 1, 1, null, null, null, "카드 4111", null);
		List<ReplayBatch.Event> out = ReplayBatch.sanitize(List.of(ok, phone, mail, card), "SAMPLED", 50, NOW);
		assertThat(out).extracting(ReplayBatch.Event::label).containsExactly("결제하기", null, null, null);
	}

	@Test
	void 화면_뼈대는_정해진_종류만_좌표를_자르고_SAMPLED에서도_남긴다() {
		List<ReplayBatch.BoxIn> boxes = new ArrayList<>(List.of(
				new ReplayBatch.BoxIn("seat-taken", 100, 200, 30, 40, "seat-105007", null, true),
				new ReplayBatch.BoxIn("button", -5, 900, 2000, 50, null, "결제하기", null),
				new ReplayBatch.BoxIn("script", 1, 1, 1, 1, null, null, null), // 모르는 종류 → 버림
				new ReplayBatch.BoxIn("input", 1, 1, 1, 1, "<b>", "홍길동 010", null))); // 이상한 ID·문구 → 뺌
		for (int i = 0; i < 500; i++) {
			boxes.add(new ReplayBatch.BoxIn("seat-free", i, i, 10, 10, null, null, null));
		}
		ReplayBatch.In layout = new ReplayBatch.In(1L, T, "LAYOUT", "runs/:id/seats", null, null, null, null, null, 9001L,
				null, boxes);
		ReplayBatch.Event e = ReplayBatch.sanitize(List.of(layout), "SAMPLED", 50, NOW).get(0);
		assertThat(e.boxes()).hasSize(400); // 상한
		assertThat(e.boxes().get(0)).isEqualTo(new ReplayBatch.Box("seat-taken", 100, 200, 30, 40, "seat-105007", null, true));
		assertThat(e.boxes().get(1)).isEqualTo(new ReplayBatch.Box("button", 0, 900, 1000, 50, null, "결제하기", false));
		assertThat(e.boxes().get(2)).isEqualTo(new ReplayBatch.Box("input", 1, 1, 1, 1, null, null, false));
	}

	@Test
	void 한_번에_너무_많이_보내면_거절() {
		List<ReplayBatch.In> many = Collections.nCopies(501, in(1, "CLICK", "home", null));
		assertThatThrownBy(() -> ReplayBatch.sanitize(many, "FULL", 50, NOW)).isInstanceOf(BusinessException.class);
	}

	@Test
	void 표본은_세션마다_고정이고_비율에_가깝다() {
		int full = 0;
		for (int i = 0; i < 20_000; i++) {
			String sid = UUID.nameUUIDFromBytes(("s" + i).getBytes()).toString();
			boolean a = ReplayService.sampledIn(sid, 0.10);
			assertThat(ReplayService.sampledIn(sid, 0.10)).isEqualTo(a); // 같은 세션은 같은 답
			if (a) {
				full++;
			}
		}
		assertThat(full).isBetween(1_700, 2_300); // 10% ± 1.5%p
		assertThat(ReplayService.sampledIn("any", 0)).isFalse();
	}
}
