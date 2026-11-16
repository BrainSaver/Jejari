package com.jejari.behavior.sink;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 세션별 직전 이벤트 시각을 기억해 클릭 간격(interval_ms)을 계산한다 (FAST_CLICK 규칙의 재료).
 *
 * <p>같은 회원의 이벤트는 같은 파티션 → 같은 컨슈머로 순서대로 오므로 앱 서버끼리 공유할 필요가 없다.
 * 메모리는 최근 {@code capacity}개 세션만 (가장 오래 안 쓴 세션부터 버림). 처음 보는 세션·버려진 세션의 첫 이벤트는 null.
 *
 * <p><b>묶음 단위로 계산하고, 저장에 성공한 뒤에만 반영한다</b> ({@link Batch#commit()}). 저장이 실패해 같은 묶음을
 * 다시 처리할 때 이미 기억한 시각과 비교하면 간격이 전부 0이 되어, 정상 사용자가 "초고속 클릭"으로 잘못 찍히기 때문이다.
 */
public class IntervalTracker {

	private final Map<String, Instant> last;

	public IntervalTracker(int capacity) {
		this.last = new LinkedHashMap<>(1024, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
				return size() > capacity;
			}
		};
	}

	public Batch batch() {
		return new Batch();
	}

	/** 한 번의 poll 묶음. 계산만 하고 commit 전에는 기억을 바꾸지 않는다 */
	public final class Batch {
		private final Map<String, Instant> latest = new HashMap<>();

		private Batch() {
		}

		/** @param sessionKey 세션 ID (비로그인이면 기기 해시). null이면 계산하지 않음 */
		public Integer next(String sessionKey, Instant ts) {
			if (sessionKey == null) {
				return null;
			}
			Instant prev = latest.containsKey(sessionKey) ? latest.get(sessionKey) : remembered(sessionKey);
			if (prev == null || ts.isAfter(prev)) {
				latest.put(sessionKey, ts);
			}
			if (prev == null || ts.isBefore(prev)) {
				return null; // 처음이거나 순서가 뒤바뀐 이벤트 (리밸런스 직후 등)
			}
			return (int) Math.min(ts.toEpochMilli() - prev.toEpochMilli(), Integer.MAX_VALUE);
		}

		/** 저장에 성공한 뒤 부른다 */
		public void commit() {
			synchronized (last) {
				latest.forEach((k, ts) -> {
					Instant prev = last.get(k);
					if (prev == null || ts.isAfter(prev)) {
						last.put(k, ts);
					}
				});
			}
		}
	}

	private Instant remembered(String key) {
		synchronized (last) {
			return last.get(key);
		}
	}
}
