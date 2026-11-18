package com.jejari.train.admin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.train.TrainLayoutReader;

/**
 * ADM-01 기준 데이터 관리 (화면 A-02): 역 · 노선 · 차량 유형 · 열차.
 *
 * <ul>
 *   <li><b>지우지 않는다.</b> 역·노선·차량 유형은 {@code refOnly} 참조 테이블이라 행을 지우지 않는다(ERD 설계 규칙).
 *       삭제 API를 만들지 않은 이유이고, 그래서 FK 인덱스를 생략한 것과도 맞물린다.</li>
 *   <li><b>쓰이는 동안 구조는 못 바꾼다.</b> 열차가 붙은 노선의 역 구성, 열차가 쓰는 차량 유형의 좌석 수,
 *       운행이 붙은 열차의 편성은 바꾸면 이미 판 좌석·운임·정차 시각과 어긋난다. 이름만 바꿀 수 있다.</li>
 *   <li><b>좌석은 열차를 만들 때 자동으로 생긴다.</b> 계산식·좌석 번호 규칙은 시드
 *       ({@code db/seed/02_car_type_train_seat.sql})와 같다: {@code seat_id = train_id × 100000 + car_no × 1000 + 순번}.</li>
 *   <li><b>ID는 시드 대역 뒤에서 매긴다.</b> 이 표들에는 시퀀스가 없다. 시드가 작은 번호를 직접 쓰므로
 *       화면에서 만든 행은 {@link #ROUTE_ID_BASE}·{@link #TRAIN_ID_BASE} 위에서 {@code MAX + 1}로 매기고,
 *       동시에 두 명이 저장해 PK가 겹치면 한 번 더 시도한다(관리자 화면이라 경합이 드물다).</li>
 * </ul>
 */
@Service
public class MasterDataService {

	/** 화면에서 만든 노선 ID 시작값 (시드는 1~) */
	static final long ROUTE_ID_BASE = 100;
	/** 화면에서 만든 열차 ID 시작값 (시드는 1~). seat_id 계산식 때문에 100000 미만이어야 한다 */
	static final long TRAIN_ID_BASE = 1000;
	static final long TRAIN_ID_MAX = 99_999;
	/** seat_id 계산식이 호차당 999석까지만 담는다 */
	static final int SEATS_PER_CAR_MAX = 999;
	static final int CARS_MAX = 20;

	private static final Pattern STATION_ID = Pattern.compile("[0-9A-Za-z]{1,10}");
	private static final Pattern CODE = Pattern.compile("[0-9A-Za-z_-]{1,10}");
	private static final Pattern COLUMNS = Pattern.compile("[A-Z]{2,6}");
	private static final Set<String> GRADES = Set.of("GENERAL", "FIRST");
	private static final Set<String> DIRECTIONS = Set.of("DOWN", "UP");

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final TrainLayoutReader layouts;

	public MasterDataService(JdbcTemplate jdbc, TransactionTemplate tx, TrainLayoutReader layouts) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.layouts = layouts;
	}

	// ------------------------------------------------------------ 역

	public record Station(String stationId, String stationName, BigDecimal latitude, BigDecimal longitude) {
	}

	public List<Station> stations(String q) {
		String like = q == null || q.isBlank() ? "%" : "%" + q.trim() + "%";
		return jdbc.query("""
				SELECT station_id, station_name, latitude, longitude FROM station
				 WHERE station_name LIKE ? OR station_id LIKE ?
				 ORDER BY station_name""",
				(rs, i) -> new Station(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4)),
				like, like);
	}

	public Station createStation(Station s) {
		if (s.stationId() == null || !STATION_ID.matcher(s.stationId()).matches()) {
			throw invalid("역 코드는 영문·숫자 10자 이내입니다.");
		}
		checkStation(s);
		try {
			jdbc.update("INSERT INTO station (station_id, station_name, latitude, longitude) VALUES (?, ?, ?, ?)",
					s.stationId(), s.stationName().trim(), s.latitude(), s.longitude());
		} catch (DuplicateKeyException e) {
			throw new BusinessException(TrainAdminErrorCode.ALREADY_EXISTS, "이미 있는 역 코드입니다: " + s.stationId());
		}
		return s;
	}

	public Station updateStation(String stationId, Station s) {
		checkStation(s);
		int n = jdbc.update("""
				UPDATE station SET station_name = ?, latitude = ?, longitude = ?, updated_at = SYSTIMESTAMP
				 WHERE station_id = ?""", s.stationName().trim(), s.latitude(), s.longitude(), stationId);
		if (n == 0) {
			throw notFound("역");
		}
		return new Station(stationId, s.stationName().trim(), s.latitude(), s.longitude());
	}

	private static void checkStation(Station s) {
		if (s.stationName() == null || s.stationName().isBlank() || s.stationName().trim().length() > 50) {
			throw invalid("역 이름은 1~50자입니다.");
		}
		if (s.latitude() != null && s.latitude().abs().compareTo(BigDecimal.valueOf(90)) > 0
				|| s.longitude() != null && s.longitude().abs().compareTo(BigDecimal.valueOf(180)) > 0) {
			throw invalid("위도는 -90~90, 경도는 -180~180입니다.");
		}
	}

	// ------------------------------------------------------------ 노선

	/** @param distanceKm 출발역에서 잰 누적 거리. 첫 역은 0 */
	public record RouteStop(String stationId, String stationName, int order, BigDecimal distanceKm) {
	}

	public record Route(long routeId, String routeName, String originStationId, String destStationId,
			List<RouteStop> stations, int trainCount) {
	}

	/** 노선 등록·수정 요청. stations는 출발역부터 순서대로 */
	public record RouteCommand(String routeName, List<StopCommand> stations) {
	}

	public record StopCommand(String stationId, BigDecimal distanceKm) {
	}

	public List<Route> routes() {
		Map<Long, List<RouteStop>> stops = new LinkedHashMap<>();
		jdbc.query("""
				SELECT rs.route_id, rs.station_id, s.station_name, rs.station_order, rs.distance_from_origin_km
				  FROM route_station rs JOIN station s ON s.station_id = rs.station_id
				 ORDER BY rs.route_id, rs.station_order""", rs -> {
			stops.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
					.add(new RouteStop(rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBigDecimal(5)));
		});
		return jdbc.query("""
				SELECT r.route_id, r.route_name, r.origin_station_id, r.dest_station_id,
				       (SELECT COUNT(*) FROM train t WHERE t.route_id = r.route_id) AS trains
				  FROM route r ORDER BY r.route_id""",
				(rs, i) -> new Route(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
						stops.getOrDefault(rs.getLong(1), List.of()), rs.getInt(5)));
	}

	public Route route(long routeId) {
		return routes().stream().filter(r -> r.routeId() == routeId).findFirst().orElseThrow(() -> notFound("노선"));
	}

	public Route createRoute(RouteCommand c) {
		checkRoute(c, true);
		long id = withRetry(() -> tx.execute(s -> {
			long next = nextId("SELECT NVL(MAX(route_id), 0) + 1 FROM route", ROUTE_ID_BASE);
			jdbc.update("INSERT INTO route (route_id, route_name, origin_station_id, dest_station_id) VALUES (?, ?, ?, ?)",
					next, c.routeName().trim(), first(c).stationId(), last(c).stationId());
			insertStops(next, c.stations());
			return next;
		}));
		return route(id);
	}

	/** 이름은 언제나 바꿀 수 있다. 역 구성(stations)은 이 노선을 쓰는 열차가 없을 때만 */
	public Route updateRoute(long routeId, RouteCommand c) {
		boolean changeStops = c.stations() != null;
		checkRoute(c, changeStops);
		tx.executeWithoutResult(s -> {
			if (jdbc.update("UPDATE route SET route_name = ?, updated_at = SYSTIMESTAMP WHERE route_id = ?",
					c.routeName().trim(), routeId) == 0) {
				throw notFound("노선");
			}
			if (!changeStops) {
				return;
			}
			if (count("SELECT COUNT(*) FROM train WHERE route_id = ?", routeId) > 0) {
				throw new BusinessException(TrainAdminErrorCode.IN_USE,
						"이 노선을 쓰는 열차가 있어 역 구성을 바꿀 수 없습니다. 이름만 바꿀 수 있습니다.");
			}
			jdbc.update("DELETE FROM route_station WHERE route_id = ?", routeId);
			insertStops(routeId, c.stations());
			jdbc.update("UPDATE route SET origin_station_id = ?, dest_station_id = ? WHERE route_id = ?",
					first(c).stationId(), last(c).stationId(), routeId);
		});
		return route(routeId);
	}

	private void insertStops(long routeId, List<StopCommand> stops) {
		List<Object[]> rows = new ArrayList<>();
		for (int i = 0; i < stops.size(); i++) {
			rows.add(new Object[] { routeId, stops.get(i).stationId(), i + 1, stops.get(i).distanceKm() });
		}
		jdbc.batchUpdate("""
				INSERT INTO route_station (route_id, station_id, station_order, distance_from_origin_km)
				VALUES (?, ?, ?, ?)""", rows);
	}

	private void checkRoute(RouteCommand c, boolean withStops) {
		if (c.routeName() == null || c.routeName().isBlank() || c.routeName().trim().length() > 50) {
			throw invalid("노선 이름은 1~50자입니다.");
		}
		if (!withStops) {
			return;
		}
		List<StopCommand> stops = c.stations();
		if (stops == null || stops.size() < 2) {
			throw invalid("노선에는 역이 2개 이상 있어야 합니다.");
		}
		Set<String> seen = new HashSet<>();
		BigDecimal prev = null;
		for (StopCommand s : stops) {
			if (s.stationId() == null || !seen.add(s.stationId())) {
				throw invalid("같은 역을 두 번 넣을 수 없습니다: " + s.stationId());
			}
			BigDecimal km = s.distanceKm();
			if (km == null || km.signum() < 0 || km.scale() > 1 || km.compareTo(BigDecimal.valueOf(999_999)) > 0) {
				throw invalid("거리는 0 이상, 소수 첫째 자리까지입니다: " + s.stationId());
			}
			if (prev == null ? km.signum() != 0 : km.compareTo(prev) <= 0) {
				throw invalid("거리는 첫 역이 0이고 뒤로 갈수록 커져야 합니다: " + s.stationId());
			}
			prev = km;
		}
		List<String> missing = new ArrayList<>(seen);
		missing.removeAll(jdbc.queryForList("SELECT station_id FROM station WHERE station_id IN ("
				+ String.join(",", seen.stream().map(x -> "?").toList()) + ")", String.class, seen.toArray()));
		if (!missing.isEmpty()) {
			throw new BusinessException(TrainAdminErrorCode.REFERENCE_NOT_FOUND, "없는 역입니다: " + missing);
		}
	}

	private static StopCommand first(RouteCommand c) {
		return c.stations().get(0);
	}

	private static StopCommand last(RouteCommand c) {
		return c.stations().get(c.stations().size() - 1);
	}

	// ------------------------------------------------------------ 차량 유형

	/** @param seatColumns 한 줄의 좌석 열 (예: ABCD). 양 끝 열이 창가다 */
	public record CarType(String carTypeCode, String carTypeName, int seatCount, String seatGrade,
			String seatColumns, int carCount) {
	}

	/** 차량 유형 등록·수정 요청 (수정 때 carTypeCode는 경로 값을 쓴다) */
	public record CarTypeCommand(String carTypeCode, String carTypeName, Integer seatCount, String seatGrade,
			String seatColumns) {
	}

	public List<CarType> carTypes() {
		return jdbc.query("""
				SELECT c.car_type_code, c.car_type_name, c.seat_count, c.seat_grade, c.seat_columns,
				       (SELECT COUNT(*) FROM train_car tc WHERE tc.car_type_code = c.car_type_code) AS cars
				  FROM car_type c ORDER BY c.seat_grade DESC, c.car_type_code""",
				(rs, i) -> new CarType(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
						rs.getString(5), rs.getInt(6)));
	}

	public CarType carType(String code) {
		return carTypes().stream().filter(c -> c.carTypeCode().equals(code)).findFirst()
				.orElseThrow(() -> notFound("차량 유형"));
	}

	public CarType createCarType(CarTypeCommand c) {
		if (c.carTypeCode() == null || !CODE.matcher(c.carTypeCode()).matches()) {
			throw invalid("차량 유형 코드는 영문·숫자 10자 이내입니다.");
		}
		checkCarType(c);
		try {
			jdbc.update("""
					INSERT INTO car_type (car_type_code, car_type_name, seat_count, seat_grade, seat_columns)
					VALUES (?, ?, ?, ?, ?)""", c.carTypeCode(), trimOrNull(c.carTypeName()), c.seatCount(),
					c.seatGrade(), c.seatColumns());
		} catch (DuplicateKeyException e) {
			throw new BusinessException(TrainAdminErrorCode.ALREADY_EXISTS, "이미 있는 차량 유형 코드입니다: " + c.carTypeCode());
		}
		return carType(c.carTypeCode());
	}

	/** 이름은 언제나, 좌석 수·등급·열 배치는 이 유형을 쓰는 열차가 없을 때만 바꾼다 */
	public CarType updateCarType(String code, CarTypeCommand c) {
		checkCarType(c);
		tx.executeWithoutResult(s -> {
			CarType cur = carType(code);
			boolean seatsChanged = cur.seatCount() != c.seatCount().intValue() || !cur.seatGrade().equals(c.seatGrade())
					|| !String.valueOf(cur.seatColumns()).equals(c.seatColumns());
			if (seatsChanged && cur.carCount() > 0) {
				throw new BusinessException(TrainAdminErrorCode.IN_USE,
						"이 차량 유형을 쓰는 열차가 있어 좌석 구성을 바꿀 수 없습니다. 이름만 바꿀 수 있습니다.");
			}
			jdbc.update("""
					UPDATE car_type SET car_type_name = ?, seat_count = ?, seat_grade = ?, seat_columns = ?,
					       updated_at = SYSTIMESTAMP
					 WHERE car_type_code = ?""", trimOrNull(c.carTypeName()), c.seatCount(), c.seatGrade(),
					c.seatColumns(), code);
		});
		layouts.evictAll(); // 좌석 배치도 응답에 차량 유형 이름이 들어 있다
		return carType(code);
	}

	private static void checkCarType(CarTypeCommand c) {
		if (c.carTypeName() != null && c.carTypeName().trim().length() > 50) {
			throw invalid("차량 유형 이름은 50자 이내입니다.");
		}
		if (c.seatCount() == null || c.seatCount() < 1 || c.seatCount() > SEATS_PER_CAR_MAX) {
			throw invalid("좌석 수는 1~" + SEATS_PER_CAR_MAX + "입니다.");
		}
		if (!GRADES.contains(c.seatGrade())) {
			throw invalid("좌석 등급은 GENERAL / FIRST 중 하나입니다.");
		}
		if (c.seatColumns() == null || !COLUMNS.matcher(c.seatColumns()).matches()
				|| c.seatColumns().chars().distinct().count() != c.seatColumns().length()) {
			throw invalid("좌석 열은 겹치지 않는 대문자 2~6개입니다 (예: ABCD).");
		}
	}

	// ------------------------------------------------------------ 열차

	public record Car(int carNo, String carTypeCode, String seatGrade, int seatCount) {
	}

	public record Train(long trainId, String trainNo, String trainKind, long routeId, String routeName,
			String direction, List<Car> cars, int seatCount, int runCount) {
	}

	/** 열차 등록·수정 요청. cars는 1호차부터 차량 유형 코드 순서대로 */
	public record TrainCommand(String trainNo, String trainKind, Long routeId, String direction, List<String> cars) {
	}

	public List<Train> trains() {
		Map<Long, List<Car>> cars = new LinkedHashMap<>();
		jdbc.query("""
				SELECT tc.train_id, tc.car_no, tc.car_type_code, ct.seat_grade, ct.seat_count
				  FROM train_car tc JOIN car_type ct ON ct.car_type_code = tc.car_type_code
				 ORDER BY tc.train_id, tc.car_no""", rs -> {
			cars.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
					.add(new Car(rs.getInt(2), rs.getString(3), rs.getString(4), rs.getInt(5)));
		});
		return jdbc.query("""
				SELECT t.train_id, t.train_no, t.train_kind, t.route_id, r.route_name, t.direction,
				       (SELECT COUNT(*) FROM seat s WHERE s.train_id = t.train_id) AS seats,
				       (SELECT COUNT(*) FROM train_run x WHERE x.train_id = t.train_id) AS runs
				  FROM train t JOIN route r ON r.route_id = t.route_id
				 ORDER BY t.train_id""",
				(rs, i) -> new Train(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5),
						rs.getString(6), cars.getOrDefault(rs.getLong(1), List.of()), rs.getInt(7), rs.getInt(8)));
	}

	public Train train(long trainId) {
		return trains().stream().filter(t -> t.trainId() == trainId).findFirst().orElseThrow(() -> notFound("열차"));
	}

	public Train createTrain(TrainCommand c) {
		checkTrain(c, null);
		Map<String, CarType> types = carTypeMap(c.cars());
		long id = withRetry(() -> tx.execute(s -> {
			long next = nextId("SELECT NVL(MAX(train_id), 0) + 1 FROM train", TRAIN_ID_BASE);
			if (next > TRAIN_ID_MAX) {
				throw invalid("열차 ID가 좌석 ID 계산식의 상한(" + TRAIN_ID_MAX + ")을 넘습니다.");
			}
			jdbc.update("INSERT INTO train (train_id, train_no, train_kind, route_id, direction) VALUES (?, ?, ?, ?, ?)",
					next, c.trainNo().trim(), c.trainKind().trim(), c.routeId(), c.direction());
			insertCarsAndSeats(next, c.cars(), types);
			return next;
		}));
		return train(id);
	}

	/**
	 * 번호·종류는 언제나 바꾼다. 노선·방향·편성(cars)은 운행이 하나도 없을 때만 바꾸고, 편성을 바꾸면 좌석을 다시 만든다.
	 * 운행이 없으면 좌석을 가리키는 재고·예약도 없다 ({@code seat_inventory}는 운행 단위).
	 */
	public Train updateTrain(long trainId, TrainCommand c) {
		Train cur = train(trainId);
		checkTrain(c, trainId);
		boolean structure = !cur.direction().equals(c.direction()) || cur.routeId() != c.routeId()
				|| !cur.cars().stream().map(Car::carTypeCode).toList().equals(c.cars());
		Map<String, CarType> types = structure ? carTypeMap(c.cars()) : Map.of();
		tx.executeWithoutResult(s -> {
			if (structure && count("SELECT COUNT(*) FROM train_run WHERE train_id = ?", trainId) > 0) {
				throw new BusinessException(TrainAdminErrorCode.IN_USE,
						"운행이 붙은 열차는 노선·방향·편성을 바꿀 수 없습니다. 번호·종류만 바꿀 수 있습니다.");
			}
			jdbc.update("""
					UPDATE train SET train_no = ?, train_kind = ?, route_id = ?, direction = ?, updated_at = SYSTIMESTAMP
					 WHERE train_id = ?""", c.trainNo().trim(), c.trainKind().trim(), c.routeId(), c.direction(), trainId);
			if (structure) {
				jdbc.update("DELETE FROM seat WHERE train_id = ?", trainId);
				jdbc.update("DELETE FROM train_car WHERE train_id = ?", trainId);
				insertCarsAndSeats(trainId, c.cars(), types);
			}
		});
		if (structure) {
			// 편성 캐시(앱 메모리)를 비운다. 운행이 없는 열차라 아직 아무도 읽지 않았겠지만, 남아 있으면 좌석 배치가 옛것으로 보인다
			layouts.evict(trainId);
		}
		return train(trainId);
	}

	/** 좌석 번호·창가 규칙은 시드와 같다: 순번 i → (i를 열 수로 나눈 올림)행 + 열 문자, 양 끝 열이 창가 */
	private void insertCarsAndSeats(long trainId, List<String> cars, Map<String, CarType> types) {
		List<Object[]> carRows = new ArrayList<>();
		List<Object[]> seatRows = new ArrayList<>();
		for (int n = 0; n < cars.size(); n++) {
			int carNo = n + 1;
			CarType t = types.get(cars.get(n));
			carRows.add(new Object[] { trainId, carNo, t.carTypeCode() });
			seatRows.addAll(seats(trainId, carNo, t));
		}
		jdbc.batchUpdate("INSERT INTO train_car (train_id, car_no, car_type_code) VALUES (?, ?, ?)", carRows);
		jdbc.batchUpdate("""
				INSERT INTO seat (seat_id, train_id, car_no, seat_no, seat_grade, is_window)
				VALUES (?, ?, ?, ?, ?, ?)""", seatRows);
	}

	static List<Object[]> seats(long trainId, int carNo, CarType t) {
		String cols = t.seatColumns();
		int w = cols.length();
		List<Object[]> rows = new ArrayList<>(t.seatCount());
		for (int i = 1; i <= t.seatCount(); i++) {
			int col = (i - 1) % w;
			String seatNo = ((i + w - 1) / w) + String.valueOf(cols.charAt(col));
			String window = col == 0 || col == w - 1 ? "Y" : "N";
			rows.add(new Object[] { trainId * 100_000 + carNo * 1000L + i, trainId, carNo, seatNo, t.seatGrade(), window });
		}
		return rows;
	}

	private void checkTrain(TrainCommand c, Long selfId) {
		if (c.trainNo() == null || c.trainNo().isBlank() || c.trainNo().trim().length() > 10) {
			throw invalid("열차 번호는 1~10자입니다.");
		}
		if (c.trainKind() == null || c.trainKind().isBlank() || c.trainKind().trim().length() > 20) {
			throw invalid("열차 종류는 1~20자입니다 (운임표의 train_kind와 같게, 예: KTX).");
		}
		if (!DIRECTIONS.contains(c.direction())) {
			throw invalid("방향은 DOWN / UP 중 하나입니다.");
		}
		if (c.routeId() == null || count("SELECT COUNT(*) FROM route WHERE route_id = ?", c.routeId()) == 0) {
			throw new BusinessException(TrainAdminErrorCode.REFERENCE_NOT_FOUND, "없는 노선입니다: " + c.routeId());
		}
		if (c.cars() == null || c.cars().isEmpty() || c.cars().size() > CARS_MAX) {
			throw invalid("호차는 1~" + CARS_MAX + "량입니다.");
		}
		// train_no에는 DB 유니크가 없다. 같은 번호가 둘이면 검색 결과에서 구분이 안 되니 화면에서 막는다
		Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM train WHERE train_no = ? AND train_id <> ?",
				Integer.class, c.trainNo().trim(), selfId == null ? -1 : selfId);
		if (dup != null && dup > 0) {
			throw new BusinessException(TrainAdminErrorCode.ALREADY_EXISTS, "이미 있는 열차 번호입니다: " + c.trainNo());
		}
	}

	private Map<String, CarType> carTypeMap(List<String> codes) {
		Map<String, CarType> all = new LinkedHashMap<>();
		carTypes().forEach(t -> all.put(t.carTypeCode(), t));
		for (String code : codes) {
			if (!all.containsKey(code)) {
				throw new BusinessException(TrainAdminErrorCode.REFERENCE_NOT_FOUND, "없는 차량 유형입니다: " + code);
			}
			if (all.get(code).seatColumns() == null) {
				throw invalid("좌석 열이 비어 있는 차량 유형은 좌석을 만들 수 없습니다: " + code);
			}
		}
		return all;
	}

	// ------------------------------------------------------------ 공통

	private long nextId(String sql, long base) {
		Long max = jdbc.queryForObject(sql, Long.class);
		return Math.max(max == null ? 1 : max, base);
	}

	private int count(String sql, Object... args) {
		Integer n = jdbc.queryForObject(sql, Integer.class, args);
		return n == null ? 0 : n;
	}

	/** MAX+1로 매긴 ID가 동시 저장과 겹치면 한 번 더 */
	static <T> T withRetry(java.util.function.Supplier<T> work) {
		try {
			return work.get();
		} catch (DuplicateKeyException e) {
			return work.get();
		}
	}

	private static String trimOrNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}

	static BusinessException invalid(String message) {
		return new BusinessException(TrainAdminErrorCode.INVALID_VALUE, message);
	}

	static BusinessException notFound(String what) {
		return new BusinessException(CommonErrorCode.NOT_FOUND, what + "을(를) 찾을 수 없습니다.");
	}
}
