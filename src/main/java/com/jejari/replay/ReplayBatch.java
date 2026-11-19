package com.jejari.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;

/**
 * 브라우저 녹화기가 묶어 보내는 이벤트 (ADM-11). 클라이언트 값이므로 <b>전부 의심하고</b> 걸러 낸다.
 *
 * <ul>
 *   <li>입력값은 받지 않는다. 입력 이벤트는 길이만 ({@code inputLen}). 화면 ID·요소 ID는 짧은 코드 형식만
 *       (이름·이메일 같은 문장이 섞여 들어오지 않게)</li>
 *   <li>좌표는 화면 비율 0~1000으로 자르고, 마우스 궤적은 정책({@code replay.max_path_points}) 개수까지만</li>
 *   <li>시각이 서버 시각과 하루 넘게 다르면 버린다 (시계가 틀린 기기·위조)</li>
 *   <li>버튼 문구({@code label})는 <b>숫자 없는 짧은 글자</b>만 ("결제하기", "열차 찾기"). 전화번호·카드번호·이메일이 들어올 수 없다</li>
 *   <li>LAYOUT = 그 순간 화면의 뼈대(버튼·입력 칸·좌석 상자의 위치와 종류). 화면 글자는 담지 않는다 → 재생 배경</li>
 * </ul>
 */
public final class ReplayBatch {

	public static final Set<String> TYPES = Set.of("PAGE_VIEW", "CLICK", "INPUT", "SCROLL", "MOUSE_PATH", "VISIBILITY",
			"LAYOUT");
	/**
	 * SAMPLED(평소)도 마우스 궤적을 남긴다 — 궤적이 없으면 사람인지 매크로인지 재생으로 알 수 없다 (다인 님, 11-19).
	 * 대신 저장량의 대부분이 궤적이라 성기게: 0.15초마다({@link #pathSampleMs}), 묶음당 1/3 ({@link #pathPoints}).
	 */
	public static final Set<String> SAMPLED_TYPES = TYPES;
	static final int MAX_EVENTS_PER_REQUEST = 500;
	static final int MAX_BOXES = 400;
	/** 버튼 문구: 한글·영문·공백·가운뎃점만 20자. 숫자·@가 없어 전화·카드·이메일·좌석 번호가 들어오지 못한다 */
	static final Pattern LABEL = Pattern.compile("^[가-힣A-Za-z ·]{1,20}$");
	/** 화면 뼈대 상자 종류 (재생기가 그리는 모양) */
	public static final Set<String> BOX_KINDS = Set.of("button", "link", "input", "select", "heading", "tab", "seat-free",
			"seat-hold", "seat-taken", "seat-mine", "seat-lost");
	static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_:\\-./]{1,64}$");
	private static final Duration MAX_SKEW = Duration.ofDays(1);

	/** 클라이언트가 보내는 모양 */
	public record In(Long seq, Long ts, String type, String screen, String target, Integer x, Integer y,
			List<List<Number>> path, Integer inputLen, Long runId, String label, List<BoxIn> boxes) {

		/** 문구·뼈대 없는 모양 */
		public In(Long seq, Long ts, String type, String screen, String target, Integer x, Integer y,
				List<List<Number>> path, Integer inputLen, Long runId) {
			this(seq, ts, type, screen, target, x, y, path, inputLen, runId, null, null);
		}
	}

	/** 화면 뼈대 상자: 종류, 위치·크기(화면 비율 0~1000), 요소 ID, 버튼 문구, 누를 수 없음 */
	public record BoxIn(String k, Integer x, Integer y, Integer w, Integer h, String id, String label, Boolean off) {
	}

	public record Box(String k, int x, int y, int w, int h, String id, String label, boolean off) {
	}

	/** 검사를 통과한 이벤트 (Kafka로 보내는 모양) */
	public record Event(long seq, long ts, String type, String screen, String target, Integer x, Integer y,
			List<int[]> path, Integer inputLen, Long runId, String label, List<Box> boxes) {
	}

	private ReplayBatch() {
	}

	/**
	 * @param mode          FULL / SAMPLED (SAMPLED면 마우스 궤적은 버림)
	 * @param maxPathPoints 궤적 점 최대 개수
	 * @return 남길 이벤트. 형식이 틀린 이벤트는 하나씩 버린다 (한 건 때문에 묶음 전체를 잃지 않게)
	 */
	public static List<Event> sanitize(List<In> in, String mode, int maxPathPoints, Instant now) {
		maxPathPoints = pathPoints(mode, maxPathPoints);
		if (in == null) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		if (in.size() > MAX_EVENTS_PER_REQUEST) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "한 번에 보낼 수 있는 녹화 이벤트는 "
					+ MAX_EVENTS_PER_REQUEST + "개까지입니다.");
		}
		Set<String> allowed = "FULL".equals(mode) ? TYPES : SAMPLED_TYPES;
		List<Event> out = new ArrayList<>();
		for (In e : in) {
			if (e == null || e.seq() == null || e.seq() < 0 || e.ts() == null || e.type() == null
					|| !allowed.contains(e.type())) {
				continue;
			}
			if (Math.abs(e.ts() - now.toEpochMilli()) > MAX_SKEW.toMillis()) {
				continue;
			}
			String screen = code(e.screen());
			if (screen == null) {
				continue; // 어느 화면인지 모르면 재생할 수 없다
			}
			List<int[]> path = null;
			if ("MOUSE_PATH".equals(e.type())) {
				path = path(e.path(), maxPathPoints);
				if (path.isEmpty()) {
					continue;
				}
			}
			Integer inputLen = "INPUT".equals(e.type()) && e.inputLen() != null
					? Math.max(0, Math.min(1000, e.inputLen())) : null;
			List<Box> boxes = null;
			if ("LAYOUT".equals(e.type())) {
				boxes = boxes(e.boxes());
				if (boxes.isEmpty()) {
					continue;
				}
			}
			Long runId = e.runId() != null && e.runId() > 0 ? e.runId() : null;
			out.add(new Event(e.seq(), e.ts(), e.type(), screen, code(e.target()), coord(e.x()), coord(e.y()), path,
					inputLen, runId, label(e.label()), boxes));
		}
		return out;
	}

	/** 모드별 궤적 점 상한: FULL은 정책값(replay.max_path_points), SAMPLED는 그 1/3 (최소 5) */
	public static int pathPoints(String mode, int fullMax) {
		return "FULL".equals(mode) ? fullMax : Math.max(5, fullMax / 3);
	}

	/** 모드별 마우스 위치를 재는 간격(ms). 클라이언트가 이 간격으로 점을 찍는다 */
	public static int pathSampleMs(String mode) {
		return "FULL".equals(mode) ? 50 : 150;
	}

	static String label(String s) {
		if (s == null) {
			return null;
		}
		String t = s.strip().replaceAll("\\s+", " ");
		return LABEL.matcher(t).matches() ? t : null;
	}

	static List<Box> boxes(List<BoxIn> raw) {
		List<Box> out = new ArrayList<>();
		if (raw == null) {
			return out;
		}
		for (BoxIn b : raw) {
			if (out.size() >= MAX_BOXES) {
				break;
			}
			if (b == null || b.k() == null || !BOX_KINDS.contains(b.k()) || b.x() == null || b.y() == null
					|| b.w() == null || b.h() == null) {
				continue;
			}
			out.add(new Box(b.k(), coord(b.x()), coord(b.y()), coord(b.w()), coord(b.h()), code(b.id()),
					label(b.label()), Boolean.TRUE.equals(b.off())));
		}
		return out;
	}

	static String code(String s) {
		return s != null && CODE.matcher(s).matches() ? s : null;
	}

	static Integer coord(Integer v) {
		return v == null ? null : Math.max(0, Math.min(1000, v));
	}

	/** [[dt, x, y], …] → dt 0~60초, 좌표 0~1000, 최대 n개 */
	static List<int[]> path(List<List<Number>> raw, int max) {
		List<int[]> out = new ArrayList<>();
		if (raw == null) {
			return out;
		}
		for (List<Number> p : raw) {
			if (out.size() >= max) {
				break;
			}
			if (p == null || p.size() != 3 || p.get(0) == null || p.get(1) == null || p.get(2) == null) {
				continue;
			}
			int dt = Math.max(0, Math.min(60_000, p.get(0).intValue()));
			out.add(new int[] { dt, coord(p.get(1).intValue()), coord(p.get(2).intValue()) });
		}
		return out;
	}
}
