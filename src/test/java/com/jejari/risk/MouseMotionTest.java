package com.jejari.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jejari.risk.MouseMotion.Click;
import com.jejari.risk.MouseMotion.Result;

/**
 * LINEAR_MOUSE의 재료. 모양은 2026-11-23 로컬 녹화에서 그대로 옮겼다 (botb 회원 5022, botc 4984, 사람 4644).
 */
class MouseMotionTest {

	static int[] p(int dt, int x, int y) {
		return new int[] {dt, x, y};
	}

	static List<List<int[]>> paths(List<int[]>... ps) {
		return List.of(ps);
	}

	@Test
	void 브라우저_자동화_기본_클릭은_클릭_자리끼리_순간이동() {
		// botb: 궤적 점이 전부 클릭 자리. 클릭 → 다음 클릭으로 중간 점 없이
		List<Click> clicks = List.of(new Click(758, 819), new Click(206, 162), new Click(334, 693));
		Result r = MouseMotion.analyze(paths(List.of(p(0, 758, 819), p(3698, 206, 162), p(4278, 334, 693)),
				List.of(p(0, 758, 819))), clicks);
		assertThat(r.teleports()).isEqualTo(3); // 758→206, 206→334, (다음 묶음) 334→758
		assertThat(r.linear()).isZero();
	}

	@Test
	void 사람은_지나가는_길에_점이_찍혀_순간이동이_아니다() {
		// 사람 4644 (SAMPLED 0.15초): 클릭 자리에 멈춘 점은 있어도 그 앞 점은 길 위
		List<Click> clicks = List.of(new Click(821, 530), new Click(263, 119));
		Result r = MouseMotion.analyze(paths(
				List.of(p(0, 155, 632), p(1844, 257, 404), p(2359, 265, 406), p(2518, 453, 549), p(3229, 467, 554),
						p(3387, 835, 495), p(3590, 821, 530)),
				List.of(p(0, 813, 532), p(1433, 575, 697), p(1586, 368, 720), p(1758, 413, 761), p(3408, 415, 690),
						p(3569, 243, 201), p(3733, 263, 119))), clicks);
		assertThat(r.total()).isZero();
	}

	@Test
	void 곡선으로_다가가는_흉내_봇은_이_규칙으로_못_잡는다() {
		// botc: 베지어 곡선 + 가감속. 한계로 문서에 남김 (모델 보조 의견이 맡음)
		List<Click> clicks = List.of(new Click(240, 542), new Click(187, 583));
		Result r = MouseMotion.analyze(paths(List.of(p(0, 659, 549), p(167, 603, 492), p(336, 569, 450),
				p(497, 553, 427), p(2352, 552, 427), p(2508, 482, 476), p(2679, 349, 510), p(2852, 258, 533),
				p(3718, 240, 542), p(3876, 225, 546), p(4034, 211, 556), p(4185, 203, 570), p(4367, 195, 582),
				p(4517, 187, 583))), clicks);
		assertThat(r.total()).isZero();
	}

	@Test
	void 같은_자리_연타나_가까운_이동은_세지_않는다() {
		List<Click> clicks = List.of(new Click(500, 500), new Click(600, 500));
		// 같은 버튼을 다시 누름(이동 0) · 100 떨어진 옆 버튼(문턱 150 아래)
		Result r = MouseMotion.analyze(paths(List.of(p(0, 500, 500), p(900, 500, 500), p(1800, 600, 500))), clicks);
		assertThat(r.teleports()).isZero();
	}

	@Test
	void 마우스_클릭_자리가_없으면_순간이동도_없다() {
		// 휴대폰 탭·키보드 클릭은 호출하는 쪽에서 빼므로 클릭 목록이 비어 있다 → 탭 자리로의 "이동"은 세지 않음
		Result r = MouseMotion.analyze(paths(List.of(p(0, 758, 819), p(3698, 206, 162), p(4278, 334, 693))),
				List.of());
		assertThat(r.total()).isZero();
	}

	@Test
	void 클릭_자리에서_길_위의_점으로_간_것은_순간이동이_아니다() {
		List<Click> clicks = List.of(new Click(100, 100), new Click(900, 900));
		Result r = MouseMotion.analyze(paths(List.of(p(0, 100, 100), p(150, 500, 520), p(300, 900, 900))), clicks);
		assertThat(r.teleports()).isZero();
	}

	@Test
	void 직선_등속_이동을_센다() {
		// 자동화 도구의 "n단계로 나눠 이동": 같은 간격·같은 거리로 일직선
		List<int[]> line = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			line.add(p(i * 50, 100 + i * 60, 200 + i * 30));
		}
		assertThat(MouseMotion.analyze(paths(line), List.of()).linear()).isEqualTo(1);
	}

	@Test
	void 일직선이라도_가감속하면_사람_쪽() {
		int[] xs = {100, 110, 160, 280, 330}; // 천천히 → 빠르게 → 천천히
		List<int[]> line = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			line.add(p(i * 50, xs[i], 400));
		}
		assertThat(MouseMotion.analyze(paths(line), List.of()).linear()).isZero();
	}

	@Test
	void 휘면_직선이_아니다() {
		List<int[]> arc = List.of(p(0, 100, 500), p(50, 160, 440), p(100, 240, 410), p(150, 320, 440), p(200, 380, 500));
		assertThat(MouseMotion.analyze(paths(arc), List.of()).linear()).isZero();
	}

	@Test
	void 짧은_이동은_직선으로_세지_않는다() {
		List<int[]> tiny = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			tiny.add(p(i * 50, 500 + i * 10, 500)); // 40 이동 (문턱 100 아래) — 손을 살짝 민 것
		}
		assertThat(MouseMotion.analyze(paths(tiny), List.of()).linear()).isZero();
	}

	@Test
	void 이상한_점은_건너뛴다() {
		List<int[]> bad = new ArrayList<>();
		bad.add(null);
		bad.add(new int[] {1});
		bad.add(p(0, 758, 819));
		Result r = MouseMotion.analyze(java.util.Arrays.asList(bad, null), List.of(new Click(758, 819)));
		assertThat(r.total()).isZero();
	}

	// ------------------------------------------------------------ 3단계 연습 봇 보강 (2026-11-24)

	/** 연습 봇 botreclinear의 이동 한 번: 점 15개, 같은 간격·같은 거리 */
	static List<int[]> straightMove(int x0, int y0, int x1, int y1, int pts) {
		List<int[]> line = new ArrayList<>();
		for (int k = 1; k <= pts; k++) {
			line.add(p((k - 1) * 150, x0 + (x1 - x0) * k / pts, y0 + (y1 - y0) * k / pts));
		}
		return line;
	}

	@Test
	void 짧은_구간으로_나뉜_직선_이동도_전체가_100_넘으면_센다() {
		// 300 이동을 점 15개로: 구간(점 5개) 하나는 약 86이라 예전엔 0 → 이어 붙인 직선 전체로 보면 3구간
		List<int[]> move = straightMove(200, 300, 500, 300, 15);
		assertThat(MouseMotion.analyze(paths(move), List.of()).linear()).isEqualTo(3);
	}

	@Test
	void 꺾인_두_직선은_따로_보고_각각_짧으면_세지_않는다() {
		List<int[]> l = new ArrayList<>(straightMove(200, 300, 260, 300, 5)); // 60 오른쪽
		int[] corner = l.get(l.size() - 1);
		List<int[]> down = straightMove(corner[1], corner[2], corner[1], corner[2] + 60, 4); // 60 아래
		for (int[] q : down) l.add(p(l.size() * 150, q[1], q[2]));
		assertThat(MouseMotion.analyze(paths(l), List.of()).linear()).isZero(); // 이어 붙이면 꺾여서 직선 아님
	}

	@Test
	void 마우스_클릭이_없어도_궤적의_직선은_센다() {
		// 클릭을 터치(t)라고 적은 봇: 마우스 클릭 자리는 없지만 궤적은 직선·등속
		assertThat(MouseMotion.analyze(paths(straightMove(100, 100, 700, 400, 15)), List.of()).linear()).isEqualTo(3);
	}

	@Test
	void 클릭이_하나도_없으면_손이_움직이지_않은_것() {
		assertThat(MouseMotion.handsOff(List.of(), List.of(), 0)).isTrue(); // 녹화 이벤트 하나만 보낸 봇
	}

	@Test
	void 마우스_클릭뿐이고_궤적이_없거나_클릭_자리에만_있으면_손이_움직이지_않은_것() {
		List<Click> clicks = List.of(new Click(200, 300), new Click(700, 600));
		assertThat(MouseMotion.handsOff(List.of(), clicks, 2)).isTrue(); // 클릭만
		assertThat(MouseMotion.handsOff(paths(List.of(p(0, 200, 300)), List.of(p(0, 701, 599))), clicks, 2)).isTrue(); // 순간이동
	}

	@Test
	void 클릭_자리가_아닌_곳을_지나가면_사람() {
		List<Click> clicks = List.of(new Click(200, 300), new Click(700, 600));
		assertThat(MouseMotion.handsOff(paths(List.of(p(0, 200, 300), p(150, 420, 380), p(300, 700, 600))), clicks, 2))
				.isFalse();
	}

	@Test
	void 터치나_키보드만_쓰면_궤적이_없어도_사람() {
		assertThat(MouseMotion.handsOff(List.of(), List.of(), 3)).isFalse(); // 클릭 3번, 마우스 클릭은 0
	}
}
