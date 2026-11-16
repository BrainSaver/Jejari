package com.jejari.train;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 운임 조회 (TRN-03). 열차 종류·등급·구간과 운행일로 적용 운임 하나를 고릅니다.
 * 열차 종류가 지정된 운임을 먼저, 없으면 종류 무관 운임(train_kind NULL)을 씁니다. 같은 조건이면 최근 적용분.
 * 좌석 선점(RSV-02)도 이 값을 선점 시점 금액으로 고정합니다.
 */
@Repository
public class FareReader {

	private static final String SQL = """
			SELECT amount FROM fare
			 WHERE seat_grade = ? AND from_station_id = ? AND to_station_id = ?
			   AND (train_kind = ? OR train_kind IS NULL)
			   AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)
			 ORDER BY CASE WHEN train_kind IS NULL THEN 1 ELSE 0 END, valid_from DESC
			 FETCH FIRST 1 ROWS ONLY""";

	private final JdbcTemplate jdbc;

	public FareReader(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Long> find(String trainKind, String seatGrade, String fromStationId, String toStationId, LocalDate runDate) {
		List<Long> v = jdbc.queryForList(SQL, Long.class, seatGrade, fromStationId, toStationId, trainKind, runDate, runDate);
		return v.stream().findFirst();
	}
}
