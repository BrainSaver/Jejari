package com.jejari.train;

import java.sql.Date;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 운행 조회 (B 열차 도메인). 기준 데이터는 db/seed가 넣고 앱은 읽기만 합니다.
 * 판매 중 여부는 {@code train_run.status}가 원본입니다 (판매 오픈 스케줄러가 ON_SALE로 바꿈).
 */
@Repository
public class TrainRunReader {

	private static final String RUN = """
			SELECT r.run_id, r.train_id, t.train_kind, r.status, r.sale_event_id, r.sale_close_at, r.run_date,
			       e.holiday_to,
			       (SELECT MIN(s.departure_time) FROM train_stop s WHERE s.run_id = r.run_id) AS departure_at
			  FROM train_run r
			  JOIN train t ON t.train_id = r.train_id
			  LEFT JOIN sale_event e ON e.sale_event_id = r.sale_event_id
			 WHERE r.run_id = ?""";

	private static final String STOPS = "SELECT station_id, stop_order FROM train_stop WHERE run_id = ? ORDER BY stop_order";

	private static final String ON_SALE_IDS = """
			SELECT run_id FROM train_run
			 WHERE status = 'ON_SALE' AND (sale_close_at IS NULL OR sale_close_at > SYSTIMESTAMP)
			 ORDER BY run_id""";

	private final JdbcTemplate jdbc;

	public TrainRunReader(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<RunInfo> find(long runId) {
		List<RunInfo> rows = jdbc.query(RUN, (rs, i) -> {
			long saleEvent = rs.getLong("sale_event_id");
			Long saleEventId = rs.wasNull() ? null : saleEvent;
			Date holidayTo = rs.getDate("holiday_to");
			return new RunInfo(
					rs.getLong("run_id"),
					rs.getLong("train_id"),
					rs.getString("train_kind"),
					rs.getString("status"),
					saleEventId,
					rs.getObject("sale_close_at", LocalDateTime.class),
					rs.getDate("run_date").toLocalDate(),
					rs.getObject("departure_at", LocalDateTime.class),
					holidayTo == null ? null : holidayTo.toLocalDate(),
					Map.of());
		}, runId);
		// 정차역은 첫 조회가 끝난 뒤 따로 읽는다. RowMapper 안에서 다시 조회하면 커넥션 하나를 쥔 채 하나를 더 빌려,
		// 동시 요청이 몰릴 때 풀이 서로를 기다리며 멈춘다 (T1에서 실제로 발생)
		return rows.stream().findFirst().map(r -> new RunInfo(r.runId(), r.trainId(), r.trainKind(), r.status(),
				r.saleEventId(), r.saleCloseAt(), r.runDate(), r.departureAt(), r.eventEndDate(), stops(runId)));
	}

	public List<Long> onSaleRunIds() {
		return jdbc.queryForList(ON_SALE_IDS, Long.class);
	}

	private Map<String, Integer> stops(long runId) {
		Map<String, Integer> order = new LinkedHashMap<>();
		jdbc.query(STOPS, rs -> {
			order.put(rs.getString("station_id"), rs.getInt("stop_order"));
		}, runId);
		return order;
	}
}
