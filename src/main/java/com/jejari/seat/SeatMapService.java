package com.jejari.seat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jejari.common.config.AppPolicy;
import com.jejari.train.TrainLayoutReader;
import com.jejari.train.TrainLayoutReader.SeatInfo;

/**
 * 좌석 상태 스냅숏 (RSV-01 배치도, TRN-02 남은 좌석 수). Redis {@code seatmap:{runId}} 해시에 짧게 캐시합니다 (ERD v17).
 *
 * <ul>
 *   <li>원천: Redis {@code seat:hold:*} 키 (R·REOFFER:R = 선점·대기자 보류, SOLD:R = 판매)와 DB {@code seat_inventory} SOLD 행.
 *       DB의 HOLD 행은 보지 않는다 — 진 요청이 DB에 쓰지 않는 구조라 실시간 상태는 Redis 키가 더 정확하다.</li>
 *   <li>캐시 값: 빈 좌석은 필드가 없고, 선점은 H, 판매는 S. 필드 {@code _}는 "캐시가 있다"는 표시 (전부 빈 운행도 캐시되게).</li>
 *   <li>유지 시간 {@code app_policy seatmap.cache_ms}(1초). 그 사이 화면이 조금 늦을 수 있지만 <b>최종 판정은 선점 때</b>라
 *       늦은 화면 때문에 같은 좌석이 두 번 팔리지는 않는다 (concurrency.md §2).</li>
 * </ul>
 */
@Service
public class SeatMapService {

	public enum State {
		EMPTY, HELD, SOLD
	}

	private static final String MARK = "_";

	private final TrainLayoutReader layouts;
	private final StringRedisTemplate redis;
	private final JdbcTemplate jdbc;
	private final AppPolicy policy;

	public SeatMapService(TrainLayoutReader layouts, StringRedisTemplate redis, JdbcTemplate jdbc, AppPolicy policy) {
		this.layouts = layouts;
		this.redis = redis;
		this.jdbc = jdbc;
		this.policy = policy;
	}

	/** 그 운행의 모든 좌석 상태. 빈 좌석도 EMPTY로 들어 있다 */
	public Map<Long, State> states(long runId, long trainId) {
		Map<Object, Object> cached = redis.opsForHash().entries(key(runId));
		Map<Long, State> out = new HashMap<>();
		List<SeatInfo> seats = layouts.layout(trainId).seats();
		if (cached.containsKey(MARK)) {
			for (SeatInfo s : seats) {
				Object v = cached.get(Long.toString(s.seatId()));
				out.put(s.seatId(), "S".equals(v) ? State.SOLD : "H".equals(v) ? State.HELD : State.EMPTY);
			}
			return out;
		}
		return rebuild(runId, seats);
	}

	/** 등급별 남은 좌석 수 (TRN-02) */
	public Map<String, Integer> remainingByGrade(long runId, long trainId) {
		Map<Long, State> st = states(runId, trainId);
		Map<String, Integer> out = new HashMap<>();
		for (SeatInfo s : layouts.layout(trainId).seats()) {
			out.merge(s.grade(), st.get(s.seatId()) == State.EMPTY ? 1 : 0, Integer::sum);
		}
		return out;
	}

	private Map<Long, State> rebuild(long runId, List<SeatInfo> seats) {
		List<String> keys = seats.stream().map(s -> SeatKeys.hold(runId, s.seatId(), HoldPlan.SEGMENT_NO)).toList();
		List<String> values = keys.isEmpty() ? List.of() : redis.opsForValue().multiGet(keys);
		Set<Long> soldInDb = new HashSet<>(jdbc.queryForList(
				"SELECT seat_id FROM seat_inventory WHERE run_id = ? AND status = 'SOLD'", Long.class, runId));

		Map<Long, State> out = new HashMap<>();
		Map<String, String> cache = new HashMap<>();
		cache.put(MARK, "1");
		for (int i = 0; i < seats.size(); i++) {
			long seatId = seats.get(i).seatId();
			String v = values == null ? null : values.get(i);
			State s = soldInDb.contains(seatId) || (v != null && v.startsWith("SOLD:")) ? State.SOLD
					: v != null ? State.HELD : State.EMPTY;
			out.put(seatId, s);
			if (s != State.EMPTY) {
				cache.put(Long.toString(seatId), s == State.SOLD ? "S" : "H");
			}
		}
		String key = key(runId);
		redis.executePipelined((RedisCallback<Object>) c -> {
			byte[] k = key.getBytes(StandardCharsets.UTF_8);
			c.keyCommands().del(k);
			Map<byte[], byte[]> fields = new HashMap<>();
			cache.forEach((f, v) -> fields.put(f.getBytes(StandardCharsets.UTF_8),
					v.getBytes(StandardCharsets.UTF_8)));
			c.hashCommands().hMSet(k, fields);
			c.keyCommands().pExpire(k, policy.getLong(AppPolicy.SEATMAP_CACHE_MS));
			return null;
		});
		return out;
	}

	/** 선점·해제 직후 내 화면이 바로 바뀌게 캐시를 지운다 (다른 사람 화면은 유지 시간 안에 따라옴) */
	public void evict(long runId) {
		redis.delete(key(runId));
	}

	static String key(long runId) {
		return "seatmap:" + runId;
	}
}
