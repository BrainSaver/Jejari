package com.jejari.risk;

import java.util.List;

/**
 * 화면 녹화(ADM-11)의 마우스 궤적에서 "기계가 움직인 흔적"을 센다 → {@link RiskRule#LINEAR_MOUSE}.
 *
 * <h2>무엇을 세나</h2>
 * <ol>
 *   <li><b>순간이동</b>: 녹화된 연속한 두 점이 모두 (서로 다른) 마우스 클릭 자리에 정확히 있고, 그 사이가 멀다.
 *       = 클릭에서 다음 클릭으로 중간 점 없이 건너뜀. 브라우저 자동화의 기본 클릭(요소 가운데로 한 번에 이동)이 이렇다.
 *       사람은 손이 지나가는 길에 점이 찍히고, 멈춘 자리가 클릭 좌표와 ±{@value #TOL} 안에 두 번 연달아 맞는 일이 드물다.</li>
 *   <li><b>직선·등속 이동</b>: 한 묶음 안 연속한 점 {@value #LINE_POINTS}개가 거의 일직선(현/호 ≥ {@value #STRAIGHT})이고
 *       구간 속도의 변동계수가 {@value #SPEED_CV} 이하. 자동화 도구의 "몇 단계로 나눠 이동"이 이렇다. 사람은 휘고 가감속한다.
 *       클릭의 입력 장치와 상관없이 센다 — 진짜 터치는 마우스 궤적이 거의 없고, 봇이 클릭을 터치(t)라고 적어도 궤적은 남는다.</li>
 * </ol>
 *
 * <h2>시각을 쓰지 않는 이유</h2>
 * 녹화기는 클릭은 바로, 궤적은 묶어서(몇 초마다) 보낸다. 궤적 점의 절대 시각을 정확히 알 수 없어 클릭과 시간으로 맞추지 않고
 * <b>점의 순서와 자리</b>로만 판정한다. 직선 판정의 속도는 같은 묶음 안의 상대 시각(dt)이라 정확하다.
 *
 * <h2>오탐을 줄이는 장치</h2>
 * <ul>
 *   <li>클릭 자리는 <b>마우스 클릭(pointer = m)</b>만. 휴대폰은 탭한 자리에 마우스 이동을 흉내 내서 순간이동처럼 보이고,
 *       키보드 Enter는 좌표가 0이다. 입력 장치를 모르는 예전 녹화는 세지 않는다.</li>
 *   <li>같은 자리를 다시 누르는 것(같은 버튼 연타)은 이동이 아니다 → 두 점 사이 {@value #MIN_JUMP} 이상만.</li>
 *   <li>개수로 센다(비율이 아님). 정상 이동을 섞어도 줄지 않는다 ({@link RiskEngine} 단조성).</li>
 * </ul>
 * 상태가 없어 스레드 안전하다. 좌표는 화면 비율 0~1000.
 */
public final class MouseMotion {

	/** 점이 클릭 자리와 "같다"고 보는 거리 (화면 비율 0~1000에서 ±3 ≈ 1280px 화면의 4px) */
	static final int TOL = 3;
	/** 순간이동으로 볼 최소 이동 거리 (화면의 15%) */
	static final int MIN_JUMP = 150;
	/** 직선 판정에 쓰는 연속한 점 수 */
	static final int LINE_POINTS = 5;
	/** 직선 판정의 최소 이동 거리 */
	static final int LINE_MIN_CHORD = 100;
	/** 현(처음~끝 직선 거리) / 호(지나온 길이). 1이면 완전한 직선 */
	static final double STRAIGHT = 0.98;
	/** 구간 속도 변동계수. 0이면 완전한 등속 */
	static final double SPEED_CV = 0.15;

	private MouseMotion() {
	}

	/** 마우스 클릭 자리 */
	public record Click(int x, int y) {
	}

	/**
	 * 한 세션의 결과.
	 *
	 * @param teleports 순간이동 수
	 * @param linear    직선·등속 이동 수
	 */
	public record Result(int teleports, int linear) {
		public int total() {
			return teleports + linear;
		}
	}

	/**
	 * @param paths  MOUSE_PATH 묶음들 (녹화 순서). 묶음 = 점 [dt, x, y]의 목록
	 * @param clicks 마우스 클릭 자리 (pointer = m인 CLICK만)
	 */
	public static Result analyze(List<List<int[]>> paths, List<Click> clicks) {
		int teleports = 0;
		int linear = 0;
		int[] prev = null;
		Click prevAt = null;
		for (List<int[]> path : paths) {
			if (path == null) {
				continue;
			}
			for (int[] p : path) {
				if (p == null || p.length < 3) {
					continue;
				}
				Click at = clickAt(p, clicks);
				if (prev != null && at != null && prevAt != null && at != prevAt
						&& dist(prev[1], prev[2], p[1], p[2]) >= MIN_JUMP) {
					teleports++;
				}
				prev = p;
				prevAt = at;
			}
			linear += linear(path);
		}
		return new Result(teleports, linear);
	}

	private static Click clickAt(int[] p, List<Click> clicks) {
		for (Click c : clicks) {
			if (Math.abs(c.x() - p[1]) <= TOL && Math.abs(c.y() - p[2]) <= TOL) {
				return c;
			}
		}
		return null;
	}

	/**
	 * 사람 손이 움직인 흔적이 없나 → {@link RiskRule#HEADLESS_HOLD}와 같게 본다 (2026-11-24, 3단계 연습 봇).
	 * <ul>
	 *   <li>클릭이 하나도 없다 (녹화 이벤트만 아무거나 보냄). 화면에서 선점하려면 좌석·선점 버튼을 반드시 누른다
	 *       (키보드 Enter도 CLICK으로 남는다)</li>
	 *   <li>마우스 클릭이 있는데 궤적 점이 하나도 없거나 모두 클릭 자리에만 있다 (클릭만 보내거나 클릭 자리로 순간이동).
	 *       녹화기는 마우스가 움직이면 어느 모드에서나 점을 찍으므로, 마우스로 누르는 사람은 지나간 길에 점이 남는다</li>
	 * </ul>
	 * 터치·키보드만 쓰는 세션은 궤적이 없는 게 정상이라 두 번째 조건에서 뺀다.
	 *
	 * @param clicks      모든 CLICK 수 (입력 장치 상관없이)
	 * @param mouseClicks 마우스 클릭 자리 (pointer = m)
	 */
	public static boolean handsOff(List<List<int[]>> paths, List<Click> mouseClicks, int clicks) {
		if (clicks == 0) {
			return true;
		}
		if (mouseClicks.isEmpty()) {
			return false;
		}
		for (List<int[]> path : paths) {
			if (path == null) {
				continue;
			}
			for (int[] p : path) {
				if (p != null && p.length >= 3 && clickAt(p, mouseClicks) == null) {
					return false; // 클릭 자리가 아닌 곳을 지나감 = 손이 움직임
				}
			}
		}
		return true;
	}

	/**
	 * 묶음 하나에서 겹치지 않는 직선·등속 구간 수.
	 * 최소 거리({@value #LINE_MIN_CHORD})는 구간마다가 아니라 <b>이어진 직선 전체</b>로 본다 (2026-11-24):
	 * 짧은 예매 흐름의 이동 한 번(화면의 20~35%)은 점 5개 구간으로 자르면 구간마다 100이 안 돼 놓쳤다 (botreclinear 1/3).
	 * 이어 붙인 직선도 전체가 곧아야 해서(현/호 ≥ {@value #STRAIGHT}) 꺾인 두 직선은 따로 센다.
	 */
	static int linear(List<int[]> path) {
		int n = 0;
		int i = 0;
		int run = 0;
		int[] runStart = null;
		int[] runEnd = null;
		double runArc = 0;
		while (i + LINE_POINTS <= path.size()) {
			if (isLinear(path, i)) {
				int[] a = path.get(i);
				int[] b = path.get(i + LINE_POINTS - 1);
				double arc = arc(path, i);
				if (run > 0 && runEnd == a && straight(runStart, b, runArc + arc)) {
					run++;
					runEnd = b;
					runArc += arc;
				} else {
					n += closeRun(run, runStart, runEnd);
					run = 1;
					runStart = a;
					runEnd = b;
					runArc = arc;
				}
				i += LINE_POINTS - 1; // 끝점을 다음 구간의 시작으로 (같은 직선을 두 번 세지 않음)
			} else {
				n += closeRun(run, runStart, runEnd);
				run = 0;
				i++;
			}
		}
		return n + closeRun(run, runStart, runEnd);
	}

	private static int closeRun(int run, int[] start, int[] end) {
		return run > 0 && dist(start[1], start[2], end[1], end[2]) >= LINE_MIN_CHORD ? run : 0;
	}

	private static boolean straight(int[] start, int[] end, double arc) {
		return arc > 0 && dist(start[1], start[2], end[1], end[2]) / arc >= STRAIGHT;
	}

	private static double arc(List<int[]> path, int from) {
		double arc = 0;
		for (int k = from + 1; k < from + LINE_POINTS; k++) {
			arc += dist(path.get(k - 1)[1], path.get(k - 1)[2], path.get(k)[1], path.get(k)[2]);
		}
		return arc;
	}

	private static boolean isLinear(List<int[]> path, int from) {
		double arc = 0;
		double[] speed = new double[LINE_POINTS - 1];
		for (int k = 1; k < LINE_POINTS; k++) {
			int[] a = path.get(from + k - 1);
			int[] b = path.get(from + k);
			if (a == null || b == null || a.length < 3 || b.length < 3) {
				return false;
			}
			double d = dist(a[1], a[2], b[1], b[2]);
			arc += d;
			speed[k - 1] = d / Math.max(1, b[0] - a[0]);
		}
		int[] first = path.get(from);
		int[] last = path.get(from + LINE_POINTS - 1);
		double chord = dist(first[1], first[2], last[1], last[2]);
		if (arc <= 0 || chord / arc < STRAIGHT) {
			return false;
		}
		double mean = 0;
		for (double v : speed) mean += v;
		mean /= speed.length;
		if (mean <= 0) {
			return false;
		}
		double var = 0;
		for (double v : speed) var += (v - mean) * (v - mean);
		return Math.sqrt(var / speed.length) / mean <= SPEED_CV;
	}

	private static double dist(int x1, int y1, int x2, int y2) {
		return Math.hypot(x2 - x1, y2 - y1);
	}
}
