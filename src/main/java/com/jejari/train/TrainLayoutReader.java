package com.jejari.train;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 열차 편성·좌석 배치 (train_car + car_type + seat). 기준 데이터라 바뀌지 않으므로 열차별로 앱 메모리에 한 번만 읽어 둡니다
 * (멀티인스턴스 코딩 규칙 2: 바뀌지 않는 기준 데이터의 메모리 캐시는 괜찮음). 좌석 상태는 여기 없고 SeatMapService가 봅니다.
 */
@Repository
public class TrainLayoutReader {

	public record SeatInfo(long seatId, int carNo, String seatNo, String grade, boolean window) {
	}

	public record Car(int carNo, String carTypeCode, String carTypeName, String grade, String seatColumns,
			List<SeatInfo> seats) {
	}

	public record Layout(long trainId, List<Car> cars) {
		public List<SeatInfo> seats() {
			return cars.stream().flatMap(c -> c.seats().stream()).toList();
		}
	}

	private static final String CARS = """
			SELECT tc.car_no, tc.car_type_code, ct.car_type_name, ct.seat_grade, ct.seat_columns
			  FROM train_car tc JOIN car_type ct ON ct.car_type_code = tc.car_type_code
			 WHERE tc.train_id = ? ORDER BY tc.car_no""";
	private static final String SEATS = """
			SELECT seat_id, car_no, seat_no, seat_grade, is_window FROM seat WHERE train_id = ? ORDER BY car_no, seat_id""";

	private final JdbcTemplate jdbc;
	private final Map<Long, Layout> cache = new ConcurrentHashMap<>();

	public TrainLayoutReader(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Layout layout(long trainId) {
		return cache.computeIfAbsent(trainId, this::load);
	}

	private Layout load(long trainId) {
		record CarRow(int carNo, String code, String name, String grade, String columns) {
		}
		List<CarRow> cars = jdbc.query(CARS, (rs, i) -> new CarRow(rs.getInt(1), rs.getString(2), rs.getString(3),
				rs.getString(4), rs.getString(5)), trainId);
		Map<Integer, List<SeatInfo>> byCar = new LinkedHashMap<>();
		jdbc.query(SEATS, rs -> {
			SeatInfo s = new SeatInfo(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), "Y".equals(rs.getString(5)));
			byCar.computeIfAbsent(s.carNo(), k -> new ArrayList<>()).add(s);
		}, trainId);
		List<Car> out = new ArrayList<>();
		for (CarRow c : cars) {
			out.add(new Car(c.carNo(), c.code(), c.name(), c.grade(), c.columns(), List.copyOf(byCar.getOrDefault(c.carNo(), List.of()))));
		}
		return new Layout(trainId, List.copyOf(out));
	}
}
