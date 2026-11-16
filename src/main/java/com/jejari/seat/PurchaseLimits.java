package com.jejari.seat;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jejari.train.RunInfo;

/**
 * 매수 한도 고르기 (concurrency.md §10.6).
 * <ol>
 *   <li>지금 시각이 valid_from ~ valid_to 안인 정책만 (NULL은 제한 없음)</li>
 *   <li>그 운행의 명절 행사 정책이 있으면 그것, 없으면 상시 정책(sale_event_id IS NULL)</li>
 *   <li>같은 조건이 여럿이면 가장 작은 max_seats</li>
 * </ol>
 * PER_HOLIDAY는 운행에 행사가 있고 그 행사 정책이 있을 때만 적용합니다.
 */
@Component
public class PurchaseLimits {

	/** 정책이 없는 scope의 상한. purchase_counter.seat_count가 NUMBER(3)이라 999 */
	static final int UNLIMITED = 999;

	private static final String SQL = """
			SELECT scope, sale_event_id, max_seats FROM purchase_limit_policy
			 WHERE (valid_from IS NULL OR valid_from <= ?) AND (valid_to IS NULL OR valid_to >= ?)
			   AND (sale_event_id IS NULL OR sale_event_id = ?)""";

	/**
	 * @param perReservation 1회 한도. 없으면 null (제한 없음)
	 * @param max            카운터로 세는 scope별 한도. 정책이 없으면 {@link #UNLIMITED}
	 * @param limited        실제 정책이 있는 scope (Redis 1차 판정 대상)
	 */
	public record Limits(Integer perReservation, Map<LimitScope, Integer> max, Map<LimitScope, Boolean> limited) {
		int max(LimitScope s) {
			return max.get(s);
		}

		boolean limited(LimitScope s) {
			return limited.getOrDefault(s, false);
		}
	}

	private final JdbcTemplate jdbc;

	public PurchaseLimits(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Limits read(RunInfo run, LocalDateTime now) {
		record Row(String scope, Long saleEventId, int maxSeats) {
		}
		List<Row> rows = jdbc.query(SQL, (rs, i) -> {
			long se = rs.getLong("sale_event_id");
			return new Row(rs.getString("scope"), rs.wasNull() ? null : se, rs.getInt("max_seats"));
		}, now, now, run.saleEventId());

		Integer perReservation = pick(rows.stream().filter(r -> r.scope.equals("PER_RESERVATION"))
				.map(r -> new int[] {r.saleEventId == null ? 0 : 1, r.maxSeats}).toList(), true);

		Map<LimitScope, Integer> max = new EnumMap<>(LimitScope.class);
		Map<LimitScope, Boolean> limited = new EnumMap<>(LimitScope.class);
		for (LimitScope s : LimitScope.values()) {
			if (s == LimitScope.PER_HOLIDAY && run.saleEventId() == null) {
				continue; // 행사 없는 운행은 명절 카운터 자체를 세지 않음 (§10.1)
			}
			Integer m = pick(rows.stream().filter(r -> r.scope.equals(s.name()))
					.map(r -> new int[] {r.saleEventId == null ? 0 : 1, r.maxSeats}).toList(), s != LimitScope.PER_HOLIDAY);
			max.put(s, m == null ? UNLIMITED : m);
			limited.put(s, m != null);
		}
		return new Limits(perReservation, max, limited);
	}

	/** {행사 정책이면 1, maxSeats} 목록에서 행사 정책 우선, 같은 조건이면 최솟값. allowStanding=false면 상시 정책은 보지 않음 */
	private static Integer pick(List<int[]> candidates, boolean allowStanding) {
		Integer event = candidates.stream().filter(c -> c[0] == 1).map(c -> c[1]).min(Integer::compare).orElse(null);
		if (event != null || !allowStanding) {
			return event;
		}
		return candidates.stream().filter(c -> c[0] == 0).map(c -> c[1]).min(Integer::compare).orElse(null);
	}
}
