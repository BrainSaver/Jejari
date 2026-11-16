package com.jejari.train;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.seat.SeatMapService;

/**
 * 열차 조회 TRN-01~03. 로그인·대기열 없이 부를 수 있습니다 (기능설계서 4.2).
 * 남은 좌석 수는 DB가 아니라 좌석 상태 캐시({@link SeatMapService}, seatmap:{runId})에서 읽습니다.
 */
@Service
public class TrainQueryService {

	public record Station(String stationId, String stationName) {
	}

	/** 운행 검색 결과 한 줄. fares·remaining은 등급(GENERAL / FIRST)별. 판매 중이 아니면 remaining은 null */
	public record RunSummary(long runId, String trainNo, String trainKind, LocalDateTime departureAt,
			LocalDateTime arrivalAt, long durationMin, String saleStatus, boolean bookable, LocalDateTime saleOpenAt,
			Map<String, Long> fares, Map<String, Integer> remaining) {
	}

	public record Stop(String stationId, String stationName, int stopOrder, LocalDateTime arrivalAt,
			LocalDateTime departureAt) {
	}

	public record RunDetail(long runId, String trainNo, String trainKind, LocalDate runDate, String saleStatus,
			boolean bookable, LocalDateTime saleOpenAt, LocalDateTime saleCloseAt, List<Stop> stops,
			String fromStationId, String toStationId, Map<String, Long> fares, Map<String, Integer> remaining) {
	}

	private static final String SEARCH = """
			SELECT r.run_id, r.train_id, t.train_no, t.train_kind, r.status, r.sale_open_at, r.sale_close_at,
			       a.departure_time, b.arrival_time
			  FROM train_run r
			  JOIN train t ON t.train_id = r.train_id
			  JOIN train_stop a ON a.run_id = r.run_id AND a.station_id = ?
			  JOIN train_stop b ON b.run_id = r.run_id AND b.station_id = ?
			 WHERE r.run_date = ? AND a.stop_order < b.stop_order
			 ORDER BY a.departure_time, r.run_id""";

	private static final String STOPS = """
			SELECT s.station_id, st.station_name, s.stop_order, s.arrival_time, s.departure_time
			  FROM train_stop s JOIN station st ON st.station_id = s.station_id
			 WHERE s.run_id = ? ORDER BY s.stop_order""";

	private final JdbcTemplate jdbc;
	private final TrainRunReader runs;
	private final FareReader fares;
	private final TrainLayoutReader layouts;
	private final SeatMapService seatMap;
	private final Clock clock;

	public TrainQueryService(JdbcTemplate jdbc, TrainRunReader runs, FareReader fares, TrainLayoutReader layouts,
			SeatMapService seatMap, Clock clock) {
		this.jdbc = jdbc;
		this.runs = runs;
		this.fares = fares;
		this.layouts = layouts;
		this.seatMap = seatMap;
		this.clock = clock;
	}

	/** TRN-01 역 목록 (노선 순서) */
	public List<Station> stations() {
		return jdbc.query("""
				SELECT s.station_id, s.station_name FROM station s
				 ORDER BY (SELECT MIN(rs.station_order) FROM route_station rs WHERE rs.station_id = s.station_id), s.station_id""",
				(rs, i) -> new Station(rs.getString(1), rs.getString(2)));
	}

	/** TRN-02 운행 검색: 그 날짜에 출발역 → 도착역 순서로 서는 운행 */
	public List<RunSummary> search(String from, String to, LocalDate date) {
		if (from == null || to == null || date == null || from.equals(to)) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		LocalDateTime now = LocalDateTime.now(clock);
		record Row(long runId, long trainId, String trainNo, String kind, String status, LocalDateTime open,
				LocalDateTime close, LocalDateTime dep, LocalDateTime arr) {
		}
		List<Row> rows = jdbc.query(SEARCH, (rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3),
				rs.getString(4), rs.getString(5), rs.getObject(6, LocalDateTime.class), rs.getObject(7, LocalDateTime.class),
				rs.getObject(8, LocalDateTime.class), rs.getObject(9, LocalDateTime.class)), from, to, date);
		List<RunSummary> out = new ArrayList<>();
		for (Row r : rows) {
			boolean bookable = RunInfo.ON_SALE.equals(r.status()) && (r.close() == null || r.close().isAfter(now));
			long minutes = r.dep() != null && r.arr() != null ? Duration.between(r.dep(), r.arr()).toMinutes() : 0;
			out.add(new RunSummary(r.runId(), r.trainNo(), r.kind(), r.dep(), r.arr(), minutes, r.status(), bookable,
					r.open(), faresByGrade(r.trainId(), r.kind(), from, to, date),
					bookable ? seatMap.remainingByGrade(r.runId(), r.trainId()) : null));
		}
		return out;
	}

	/** TRN-03 운행 상세·운임. 구간을 안 주면 첫 정차역 → 마지막 정차역 */
	public RunDetail detail(long runId, String from, String to) {
		RunInfo run = runs.find(runId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND, "운행을 찾을 수 없습니다."));
		List<Stop> stops = jdbc.query(STOPS, (rs, i) -> new Stop(rs.getString(1), rs.getString(2), rs.getInt(3),
				rs.getObject(4, LocalDateTime.class), rs.getObject(5, LocalDateTime.class)), runId);
		String f = from != null ? from : stops.isEmpty() ? null : stops.get(0).stationId();
		String t = to != null ? to : stops.isEmpty() ? null : stops.get(stops.size() - 1).stationId();
		if (f == null || !run.validSegment(f, t)) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "승차역·하차역이 이 열차의 정차역 순서와 맞지 않습니다.");
		}
		boolean bookable = run.onSale(LocalDateTime.now(clock));
		LocalDateTime openAt = jdbc.queryForObject("SELECT sale_open_at FROM train_run WHERE run_id = ?", LocalDateTime.class, runId);
		String trainNo = jdbc.queryForObject("SELECT train_no FROM train WHERE train_id = ?", String.class, run.trainId());
		return new RunDetail(runId, trainNo, run.trainKind(), run.runDate(), run.status(), bookable, openAt,
				run.saleCloseAt(), stops, f, t, faresByGrade(run.trainId(), run.trainKind(), f, t, run.runDate()),
				bookable ? seatMap.remainingByGrade(runId, run.trainId()) : null);
	}

	/** 그 열차에 있는 등급만, 운임이 등록된 것만 */
	private Map<String, Long> faresByGrade(long trainId, String kind, String from, String to, LocalDate date) {
		Map<String, Long> out = new LinkedHashMap<>();
		layouts.layout(trainId).cars().stream().map(TrainLayoutReader.Car::grade).distinct().sorted()
				.forEach(g -> fares.find(kind, g, from, to, date).ifPresent(a -> out.put(g, a)));
		return out;
	}
}
