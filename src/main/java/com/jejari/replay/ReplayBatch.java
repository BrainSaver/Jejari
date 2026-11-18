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
 * </ul>
 */
public final class ReplayBatch {

	public static final Set<String> TYPES = Set.of("PAGE_VIEW", "CLICK", "INPUT", "SCROLL", "MOUSE_PATH", "VISIBILITY");
	/** SAMPLED 녹화에서도 남기는 것 (마우스 궤적만 뺀다 → 저장량의 대부분) */
	public static final Set<String> SAMPLED_TYPES = Set.of("PAGE_VIEW", "CLICK", "INPUT", "SCROLL", "VISIBILITY");
	static final int MAX_EVENTS_PER_REQUEST = 500;
	static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_:\\-./]{1,64}$");
	private static final Duration MAX_SKEW = Duration.ofDays(1);

	/** 클라이언트가 보내는 모양 */
	public record In(Long seq, Long ts, String type, String screen, String target, Integer x, Integer y,
			List<List<Number>> path, Integer inputLen, Long runId) {
	}

	/** 검사를 통과한 이벤트 (Kafka로 보내는 모양) */
	public record Event(long seq, long ts, String type, String screen, String target, Integer x, Integer y,
			List<int[]> path, Integer inputLen, Long runId) {
	}

	private ReplayBatch() {
	}

	/**
	 * @param mode          FULL / SAMPLED (SAMPLED면 마우스 궤적은 버림)
	 * @param maxPathPoints 궤적 점 최대 개수
	 * @return 남길 이벤트. 형식이 틀린 이벤트는 하나씩 버린다 (한 건 때문에 묶음 전체를 잃지 않게)
	 */
	public static List<Event> sanitize(List<In> in, String mode, int maxPathPoints, Instant now) {
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
			Long runId = e.runId() != null && e.runId() > 0 ? e.runId() : null;
			out.add(new Event(e.seq(), e.ts(), e.type(), screen, code(e.target()), coord(e.x()), coord(e.y()), path,
					inputLen, runId));
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
