package com.jejari.train.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.error.BusinessException;

/**
 * ADM-02 운행·명절 행사 관리 (화면 A-03). 시각에 맞춘 상태 전이는 {@link com.jejari.train.SaleOpenScheduler}가 하고,
 * 여기서는 운행을 만들고 · 시각을 고치고 · 손으로 판매를 여는 일만 한다.
 *
 * <p>운행을 만드는 규칙은 시드와 같다. 같은 규칙을 두 곳에 두는 대신 시드 SQL의 계산을 옮겨 왔고,
 * 바꿀 때는 둘을 함께 바꾼다.
 * <ul>
 *   <li>판매 오픈: 명절 행사에 묶으면 {@code sale_event.sale_open_at}(일괄 오픈), 아니면
 *       {@code ADD_MONTHS(run_date, -2) + 07:00} (코레일 "출발 2개월 전 오전 7시", {@code db/seed/04_regular_run.sql})</li>
 *   <li>판매 마감: 운행일 다음 날 00:00 (시드와 같음)</li>
 *   <li>상태: 오픈 시각이 이미 지났으면 ON_SALE, 아니면 SCHEDULED</li>
 *   <li>정차 시각: 누적 거리 / 2.7km/분 + 중간 정차 2분씩, 상행은 순번·거리를 뒤집는다
 *       ({@code db/seed/05_train_stop_fare.sql}). 기준 출발 시각은 하행 09:00, 상행 15:00이고 화면에서 바꿀 수 있다.</li>
 * </ul>
 *
 * <p><b>판매가 시작된 운행은 고치지 않는다.</b> 시각 수정·삭제는 {@code status = 'SCHEDULED'}를 WHERE에 넣은
 * 조건부 UPDATE·DELETE라, 스케줄러가 같은 순간 ON_SALE로 바꿨다면 0행이 되어 거절된다 (concurrency.md §0.3).
 */
@Service
public class RunAdminService {

	/** 화면에서 만든 운행 ID 시작값. 시드는 1001~2004·9001~9003, 통합 테스트는 9101~ 대역을 쓴다 */
	static final long RUN_ID_BASE = 1_000_000;
	static final long EVENT_ID_BASE = 100;
	static final long POLICY_ID_BASE = 100;
	/** 한 번에 만들 수 있는 기간 (약 석 달) */
	static final int BULK_DAYS_MAX = 92;
	static final BigDecimal KM_PER_MIN = new BigDecimal("2.7");
	static final int DWELL_MIN = 2;
	static final LocalTime DOWN_DEPART = LocalTime.of(9, 0);
	static final LocalTime UP_DEPART = LocalTime.of(15, 0);
	static final Set<String> EVENT_STATUSES = Set.of("PLANNED", "OPEN", "CLOSED");
	/** 명절 행사 매수 제한 기본값 (기능설계서 RSV-02, db/seed/03 행사 2와 같음) */
	static final Map<String, Integer> DEFAULT_LIMITS = Map.of("PER_RESERVATION", 4, "PER_RUN", 4, "PER_DAY", 20,
			"PER_HOLIDAY", 8);

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final Clock clock;

	public RunAdminService(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.clock = clock;
	}

	// ------------------------------------------------------------ 명절 행사

	public record SaleEvent(long saleEventId, String name, LocalDate holidayFrom, LocalDate holidayTo,
			LocalDateTime saleOpenAt, LocalDateTime priorityOpenAt, String status, Map<String, Integer> limits,
			int runCount) {
	}

	/** @param limits scope → 매수. 비우면 {@link #DEFAULT_LIMITS} (등록할 때만 쓴다) */
	public record EventCommand(String name, LocalDate holidayFrom, LocalDate holidayTo, LocalDateTime saleOpenAt,
			LocalDateTime priorityOpenAt, String status, Map<String, Integer> limits) {
	}

	public List<SaleEvent> events() {
		Map<Long, Map<String, Integer>> limits = new LinkedHashMap<>();
		jdbc.query("SELECT sale_event_id, scope, max_seats FROM purchase_limit_policy WHERE sale_event_id IS NOT NULL",
				rs -> {
					limits.computeIfAbsent(rs.getLong(1), k -> new LinkedHashMap<>()).put(rs.getString(2), rs.getInt(3));
				});
		return jdbc.query("""
				SELECT e.sale_event_id, e.name, e.holiday_from, e.holiday_to, e.sale_open_at, e.priority_open_at, e.status,
				       (SELECT COUNT(*) FROM train_run r WHERE r.sale_event_id = e.sale_event_id) AS runs
				  FROM sale_event e ORDER BY e.holiday_from DESC""",
				(rs, i) -> new SaleEvent(rs.getLong(1), rs.getString(2), rs.getDate(3).toLocalDate(),
						rs.getDate(4).toLocalDate(), rs.getObject(5, LocalDateTime.class),
						rs.getObject(6, LocalDateTime.class), rs.getString(7),
						limits.getOrDefault(rs.getLong(1), Map.of()), rs.getInt(8)));
	}

	public SaleEvent event(long id) {
		return events().stream().filter(e -> e.saleEventId() == id).findFirst()
				.orElseThrow(() -> MasterDataService.notFound("명절 행사"));
	}

	/**
	 * 행사와 그 행사 전용 매수 제한 정책을 함께 만든다. 정책이 없으면 동시성 §10.6대로 상시 정책만 걸려
	 * 명절 전체 한도(PER_HOLIDAY)가 아예 적용되지 않는다 — 화면에서 행사를 만들었는데 한도가 없는 일을 막는다.
	 * 정책 유효 기간은 행사 마지막 날 다음 날까지(시작은 열어 둠: 우선 예매가 더 일찍 열릴 수 있다).
	 */
	public SaleEvent createEvent(EventCommand c) {
		checkEvent(c);
		Map<String, Integer> limits = c.limits() == null || c.limits().isEmpty() ? DEFAULT_LIMITS : c.limits();
		checkLimits(limits);
		long id = MasterDataService.withRetry(() -> tx.execute(s -> {
			long next = nextId("SELECT NVL(MAX(sale_event_id), 0) + 1 FROM sale_event", EVENT_ID_BASE);
			jdbc.update("""
					INSERT INTO sale_event (sale_event_id, name, holiday_from, holiday_to, sale_open_at, priority_open_at, status)
					VALUES (?, ?, ?, ?, ?, ?, ?)""", next, c.name().trim(), Date.valueOf(c.holidayFrom()),
					Date.valueOf(c.holidayTo()), Timestamp.valueOf(c.saleOpenAt()), ts(c.priorityOpenAt()),
					c.status() == null ? "PLANNED" : c.status());
			long policy = nextId("SELECT NVL(MAX(policy_id), 0) + 1 FROM purchase_limit_policy", POLICY_ID_BASE);
			for (Map.Entry<String, Integer> l : limits.entrySet()) {
				jdbc.update("""
						INSERT INTO purchase_limit_policy (policy_id, sale_event_id, scope, max_seats, valid_from, valid_to)
						VALUES (?, ?, ?, ?, NULL, ?)""", policy++, next, l.getKey(), l.getValue(),
						Timestamp.valueOf(c.holidayTo().plusDays(1).atStartOfDay()));
			}
			return next;
		}));
		return event(id);
	}

	/**
	 * 판매 오픈 시각을 바꾸면 이 행사에 묶인 <b>아직 판매 전(SCHEDULED)</b> 운행의 오픈 시각도 같이 바꾼다
	 * (행사 운행은 행사 오픈 시각을 복사해 쓰므로, 한쪽만 바뀌면 일괄 오픈이 깨진다). 이미 열린 운행은 그대로 둔다.
	 * 매수 제한 값 수정은 아직 화면에 없다 (보완할-일.md).
	 */
	public SaleEvent updateEvent(long id, EventCommand c) {
		checkEvent(c);
		tx.executeWithoutResult(s -> {
			int n = jdbc.update("""
					UPDATE sale_event SET name = ?, holiday_from = ?, holiday_to = ?, sale_open_at = ?, priority_open_at = ?,
					       status = ?, updated_at = SYSTIMESTAMP
					 WHERE sale_event_id = ?""", c.name().trim(), Date.valueOf(c.holidayFrom()),
					Date.valueOf(c.holidayTo()), Timestamp.valueOf(c.saleOpenAt()), ts(c.priorityOpenAt()),
					c.status() == null ? "PLANNED" : c.status(), id);
			if (n == 0) {
				throw MasterDataService.notFound("명절 행사");
			}
			Integer outside = jdbc.queryForObject("""
					SELECT COUNT(*) FROM train_run WHERE sale_event_id = ? AND (run_date < ? OR run_date > ?)""",
					Integer.class, id, Date.valueOf(c.holidayFrom()), Date.valueOf(c.holidayTo()));
			if (outside != null && outside > 0) {
				throw MasterDataService.invalid("이 행사에 묶인 운행 " + outside + "건이 새 기간 밖에 있습니다.");
			}
			jdbc.update("""
					UPDATE train_run SET sale_open_at = ?, updated_at = SYSTIMESTAMP
					 WHERE sale_event_id = ? AND status = 'SCHEDULED'""", Timestamp.valueOf(c.saleOpenAt()), id);
		});
		return event(id);
	}

	private static void checkEvent(EventCommand c) {
		if (c.name() == null || c.name().isBlank() || c.name().trim().length() > 50) {
			throw MasterDataService.invalid("행사 이름은 1~50자입니다.");
		}
		if (c.holidayFrom() == null || c.holidayTo() == null || c.holidayTo().isBefore(c.holidayFrom())) {
			throw MasterDataService.invalid("명절 기간의 시작·끝을 넣어 주세요 (끝 ≥ 시작).");
		}
		if (c.saleOpenAt() == null || !c.saleOpenAt().toLocalDate().isBefore(c.holidayFrom())) {
			throw MasterDataService.invalid("판매 오픈 시각은 명절 시작일보다 앞이어야 합니다.");
		}
		if (c.priorityOpenAt() != null && !c.priorityOpenAt().isBefore(c.saleOpenAt())) {
			throw MasterDataService.invalid("우선 예매 시각은 판매 오픈 시각보다 앞이어야 합니다.");
		}
		if (c.status() != null && !EVENT_STATUSES.contains(c.status())) {
			throw MasterDataService.invalid("상태는 PLANNED / OPEN / CLOSED 중 하나입니다.");
		}
	}

	private static void checkLimits(Map<String, Integer> limits) {
		for (Map.Entry<String, Integer> l : limits.entrySet()) {
			if (!DEFAULT_LIMITS.containsKey(l.getKey())) {
				throw MasterDataService.invalid("매수 제한 범위는 " + DEFAULT_LIMITS.keySet() + " 중 하나입니다.");
			}
			if (l.getValue() == null || l.getValue() < 1 || l.getValue() > 99) {
				throw MasterDataService.invalid("매수 제한은 1~99입니다: " + l.getKey());
			}
		}
	}

	// ------------------------------------------------------------ 운행

	/**
	 * @param origin    첫 정차역 이름 (진행 방향 기준)
	 * @param dest      마지막 정차역 이름
	 * @param soldSeats 결제까지 끝난 좌석 수. 구간이 나뉘어 팔려도 좌석 하나는 1로 센다 (seat_inventory SOLD)
	 */
	public record Run(long runId, long trainId, String trainNo, LocalDate runDate, Long saleEventId,
			String saleEventName, LocalDateTime saleOpenAt, LocalDateTime saleCloseAt, String status,
			LocalDateTime departureAt, LocalDateTime arrivalAt, String origin, String dest, int soldSeats,
			int totalSeats) {
	}

	public record RunFilter(LocalDate from, LocalDate to, Long trainId, String status, Long saleEventId) {
	}

	/**
	 * 운행 일괄 생성 요청.
	 *
	 * @param weekdays    1=월 … 7=일. 비우면 매일
	 * @param departTime  첫 역 출발 시각. 비우면 하행 09:00, 상행 15:00
	 * @param saleEventId 명절 행사에 묶을 때. 운행일이 모두 행사 기간 안이어야 한다
	 */
	public record BulkCommand(Long trainId, LocalDate from, LocalDate to, List<Integer> weekdays, LocalTime departTime,
			Long saleEventId) {
	}

	public record BulkResult(List<Long> created, List<LocalDate> skipped) {
	}

	public record RunCommand(Long saleEventId, LocalDateTime saleOpenAt, LocalDateTime saleCloseAt) {
	}

	private static final String RUN_SELECT = """
			SELECT r.run_id, r.train_id, t.train_no, r.run_date, r.sale_event_id, e.name, r.sale_open_at, r.sale_close_at,
			       r.status,
			       (SELECT MIN(s.departure_time) FROM train_stop s WHERE s.run_id = r.run_id) AS dep,
			       (SELECT MAX(s.arrival_time) FROM train_stop s WHERE s.run_id = r.run_id) AS arr,
			       (SELECT st.station_name FROM train_stop s JOIN station st ON st.station_id = s.station_id
			         WHERE s.run_id = r.run_id ORDER BY s.stop_order FETCH FIRST 1 ROWS ONLY) AS origin,
			       (SELECT st.station_name FROM train_stop s JOIN station st ON st.station_id = s.station_id
			         WHERE s.run_id = r.run_id ORDER BY s.stop_order DESC FETCH FIRST 1 ROWS ONLY) AS dest,
			       -- seat_inventory PK가 run_id로 시작해 운행 하나씩 범위 조회로 끝난다 (쓰기 폭주 표지만 읽기만, 인덱스 추가 없음)
			       (SELECT COUNT(DISTINCT i.seat_id) FROM seat_inventory i
			         WHERE i.run_id = r.run_id AND i.status = 'SOLD') AS sold,
			       (SELECT COUNT(*) FROM seat x WHERE x.train_id = r.train_id) AS seats
			  FROM train_run r
			  JOIN train t ON t.train_id = r.train_id
			  LEFT JOIN sale_event e ON e.sale_event_id = r.sale_event_id
			""";

	public List<Run> runs(RunFilter f) {
		LocalDate from = f.from() == null ? LocalDate.now(clock) : f.from();
		LocalDate to = f.to() == null ? from.plusDays(62) : f.to();
		StringBuilder sql = new StringBuilder(RUN_SELECT).append(" WHERE r.run_date BETWEEN ? AND ?");
		List<Object> args = new ArrayList<>(List.of(Date.valueOf(from), Date.valueOf(to)));
		if (f.trainId() != null) {
			sql.append(" AND r.train_id = ?");
			args.add(f.trainId());
		}
		if (f.status() != null && !f.status().isBlank()) {
			sql.append(" AND r.status = ?");
			args.add(f.status());
		}
		if (f.saleEventId() != null) {
			sql.append(" AND r.sale_event_id = ?");
			args.add(f.saleEventId());
		}
		sql.append(" ORDER BY r.run_date, r.train_id FETCH FIRST 1000 ROWS ONLY");
		return jdbc.query(sql.toString(), (rs, i) -> runRow(rs), args.toArray());
	}

	public Run run(long runId) {
		return jdbc.query(RUN_SELECT + " WHERE r.run_id = ?", (rs, i) -> runRow(rs), runId).stream().findFirst()
				.orElseThrow(() -> MasterDataService.notFound("운행"));
	}

	private static Run runRow(java.sql.ResultSet rs) throws java.sql.SQLException {
		long ev = rs.getLong(5);
		Long eventId = rs.wasNull() ? null : ev;
		return new Run(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getDate(4).toLocalDate(), eventId,
				rs.getString(6), rs.getObject(7, LocalDateTime.class), rs.getObject(8, LocalDateTime.class),
				rs.getString(9), rs.getObject(10, LocalDateTime.class), rs.getObject(11, LocalDateTime.class),
				rs.getString(12), rs.getString(13), rs.getInt(14), rs.getInt(15));
	}

	/**
	 * 기간·요일로 운행을 한꺼번에 만든다. 이미 같은 날 운행이 있는 열차(uq_train_run_train_id_run_date)는 건너뛰고
	 * 건너뛴 날짜를 돌려준다. 전부 한 트랜잭션이라 중간에 실패하면 하나도 남지 않는다.
	 */
	public BulkResult createRuns(BulkCommand c) {
		TrainRow train = trainRow(c.trainId());
		LocalDate today = LocalDate.now(clock);
		if (c.from() == null || c.to() == null || c.to().isBefore(c.from())) {
			throw MasterDataService.invalid("기간의 시작·끝을 넣어 주세요 (끝 ≥ 시작).");
		}
		if (c.from().isBefore(today)) {
			throw MasterDataService.invalid("지난 날짜에는 운행을 만들 수 없습니다.");
		}
		if (ChronoUnit.DAYS.between(c.from(), c.to()) >= BULK_DAYS_MAX) {
			throw MasterDataService.invalid("한 번에 " + BULK_DAYS_MAX + "일까지 만들 수 있습니다.");
		}
		Set<DayOfWeek> days = weekdays(c.weekdays());
		SaleEvent event = c.saleEventId() == null ? null : event(c.saleEventId());
		if (event != null && (c.from().isBefore(event.holidayFrom()) || c.to().isAfter(event.holidayTo()))) {
			throw MasterDataService.invalid("명절 행사 운행은 행사 기간(" + event.holidayFrom() + " ~ " + event.holidayTo()
					+ ") 안에서만 만들 수 있습니다.");
		}
		List<StopTemplate> template = stopTemplate(train);
		LocalTime depart = c.departTime() != null ? c.departTime() : "DOWN".equals(train.direction()) ? DOWN_DEPART : UP_DEPART;

		return tx.execute(s -> {
			Set<LocalDate> existing = new HashSet<>();
			jdbc.query("SELECT run_date FROM train_run WHERE train_id = ? AND run_date BETWEEN ? AND ?",
					rs -> {
						existing.add(rs.getDate(1).toLocalDate());
					}, train.trainId(), Date.valueOf(c.from()), Date.valueOf(c.to()));
			long next = nextId("SELECT NVL(MAX(run_id), 0) + 1 FROM train_run WHERE run_id >= " + RUN_ID_BASE,
					RUN_ID_BASE);
			List<Long> created = new ArrayList<>();
			List<LocalDate> skipped = new ArrayList<>();
			for (LocalDate d = c.from(); !d.isAfter(c.to()); d = d.plusDays(1)) {
				if (!days.contains(d.getDayOfWeek())) {
					continue;
				}
				if (existing.contains(d)) {
					skipped.add(d);
					continue;
				}
				long runId = next++;
				insertRun(runId, train.trainId(), d, event);
				insertStops(runId, d.atTime(depart), template);
				created.add(runId);
			}
			return new BulkResult(created, skipped);
		});
	}

	private void insertRun(long runId, long trainId, LocalDate runDate, SaleEvent event) {
		// 오픈 시각 계산을 SQL에 둔 이유: ADD_MONTHS의 말일 규칙(2/28 → 12/31)이 Java minusMonths와 달라 시드와 어긋난다.
		// 행사 유무로 문을 나눈 이유: NULL 바인드를 NVL 안에 넣으면 Oracle이 타입을 정하지 못한다
		String open = event == null ? "CAST(ADD_MONTHS(?, -2) AS TIMESTAMP) + INTERVAL '7' HOUR" : "?";
		String sql = """
				INSERT INTO train_run (run_id, train_id, sale_event_id, run_date, sale_open_at, sale_close_at, status)
				SELECT run_id, train_id, sale_event_id, d, o, CAST(d + 1 AS TIMESTAMP),
				       CASE WHEN o <= SYSTIMESTAMP THEN 'ON_SALE' ELSE 'SCHEDULED' END
				  FROM (SELECT ? AS run_id, ? AS train_id, %s AS sale_event_id, ? AS d, %s AS o FROM dual)"""
				.formatted(event == null ? "CAST(NULL AS NUMBER)" : "?", open);
		List<Object> args = new ArrayList<>(List.of(runId, trainId));
		if (event != null) {
			args.add(event.saleEventId());
		}
		args.add(Date.valueOf(runDate));
		args.add(event == null ? Date.valueOf(runDate) : Timestamp.valueOf(event.saleOpenAt()));
		jdbc.update(sql, args.toArray());
	}

	private void insertStops(long runId, LocalDateTime departBase, List<StopTemplate> template) {
		int last = template.size();
		List<Object[]> rows = new ArrayList<>();
		for (StopTemplate t : template) {
			long travel = t.cumKm().divide(KM_PER_MIN, 0, RoundingMode.HALF_UP).longValue()
					+ (long) DWELL_MIN * (t.order() - 1);
			LocalDateTime arrival = t.order() == 1 ? null : departBase.plusMinutes(travel);
			LocalDateTime departure = t.order() == last ? null
					: t.order() == 1 ? departBase : departBase.plusMinutes(travel + DWELL_MIN);
			rows.add(new Object[] { runId, t.stationId(), t.order(), ts(arrival), ts(departure) });
		}
		jdbc.batchUpdate("""
				INSERT INTO train_stop (run_id, station_id, stop_order, arrival_time, departure_time)
				VALUES (?, ?, ?, ?, ?)""", rows);
	}

	record StopTemplate(String stationId, int order, BigDecimal cumKm) {
	}

	/** 진행 방향 기준 정차 순서와 누적 거리. 상행은 노선 순서를 뒤집는다 */
	List<StopTemplate> stopTemplate(TrainRow train) {
		record Rs(String id, int order, BigDecimal km) {
		}
		List<Rs> rs = jdbc.query("""
				SELECT station_id, station_order, distance_from_origin_km FROM route_station
				 WHERE route_id = ? ORDER BY station_order""",
				(r, i) -> new Rs(r.getString(1), r.getInt(2), r.getBigDecimal(3)), train.routeId());
		if (rs.size() < 2) {
			throw MasterDataService.invalid("노선에 역이 2개 이상 있어야 운행을 만들 수 있습니다.");
		}
		int maxOrder = rs.stream().mapToInt(Rs::order).max().orElseThrow();
		BigDecimal maxKm = rs.stream().map(Rs::km).reduce(BigDecimal::max).orElseThrow();
		boolean down = "DOWN".equals(train.direction());
		List<StopTemplate> out = new ArrayList<>();
		for (Rs r : rs) {
			out.add(down ? new StopTemplate(r.id(), r.order(), r.km())
					: new StopTemplate(r.id(), maxOrder + 1 - r.order(), maxKm.subtract(r.km())));
		}
		out.sort((a, b) -> Integer.compare(a.order(), b.order()));
		return out;
	}

	/** 판매 전 운행의 시각·행사를 고친다. 이미 열린 운행은 거절 */
	public Run updateRun(long runId, RunCommand c) {
		Run cur = run(runId);
		if (c.saleOpenAt() == null) {
			throw MasterDataService.invalid("판매 오픈 시각을 넣어 주세요.");
		}
		if (c.saleCloseAt() != null && !c.saleCloseAt().isAfter(c.saleOpenAt())) {
			throw MasterDataService.invalid("판매 마감은 오픈보다 뒤여야 합니다.");
		}
		if (c.saleEventId() != null) {
			SaleEvent e = event(c.saleEventId());
			if (cur.runDate().isBefore(e.holidayFrom()) || cur.runDate().isAfter(e.holidayTo())) {
				throw MasterDataService.invalid("운행일이 명절 행사 기간 밖입니다.");
			}
		}
		int n = jdbc.update("""
				UPDATE train_run SET sale_event_id = ?, sale_open_at = ?, sale_close_at = ?, updated_at = SYSTIMESTAMP
				 WHERE run_id = ? AND status = 'SCHEDULED'""", c.saleEventId(), Timestamp.valueOf(c.saleOpenAt()),
				ts(c.saleCloseAt()), runId);
		if (n == 0) {
			throw new BusinessException(TrainAdminErrorCode.RUN_NOT_SCHEDULED);
		}
		return run(runId);
	}

	/**
	 * 지금 바로 판매를 연다 (시연·점검용). 오픈 시각도 지금으로 당겨 둔다 — 그래야 스케줄러·조회가 보는
	 * "오픈 시각 ≤ 지금 ⇔ ON_SALE" 관계가 깨지지 않는다. 마감이 지난 운행은 열지 않는다.
	 */
	public Run openNow(long runId) {
		run(runId);
		int n = jdbc.update("""
				UPDATE train_run SET status = 'ON_SALE', sale_open_at = LEAST(sale_open_at, SYSTIMESTAMP),
				       updated_at = SYSTIMESTAMP
				 WHERE run_id = ? AND status = 'SCHEDULED'
				   AND (sale_close_at IS NULL OR sale_close_at > SYSTIMESTAMP)""", runId);
		if (n == 0) {
			throw new BusinessException(TrainAdminErrorCode.RUN_NOT_SCHEDULED,
					"판매 전(SCHEDULED)이고 마감이 지나지 않은 운행만 열 수 있습니다.");
		}
		return run(runId);
	}

	/**
	 * 판매 전 운행을 지운다 (정차 시각과 함께). 예약·재고·취소표 대기가 가리키면 FK가 막는다.
	 * 판매 전이라 보통은 없지만, 운영 중 손으로 넣은 행이 있을 수 있어 FK 오류를 그대로 IN_USE로 돌려준다.
	 */
	public void deleteRun(long runId) {
		run(runId);
		try {
			tx.executeWithoutResult(s -> {
				// 행을 잠그고 상태를 본다 (Oracle은 집계 함수와 FOR UPDATE를 함께 쓰지 못한다). 잠근 동안 스케줄러가 못 연다
				String status = jdbc.queryForObject("SELECT status FROM train_run WHERE run_id = ? FOR UPDATE",
						String.class, runId);
				if (!"SCHEDULED".equals(status)) {
					throw new BusinessException(TrainAdminErrorCode.RUN_NOT_SCHEDULED);
				}
				jdbc.update("DELETE FROM train_stop WHERE run_id = ?", runId);
				jdbc.update("DELETE FROM train_run WHERE run_id = ?", runId);
			});
		} catch (DataIntegrityViolationException e) {
			throw new BusinessException(TrainAdminErrorCode.IN_USE, "이 운행을 가리키는 예약·재고·대기가 있어 지울 수 없습니다.");
		}
	}

	// ------------------------------------------------------------ 공통

	record TrainRow(long trainId, long routeId, String direction) {
	}

	private TrainRow trainRow(Long trainId) {
		if (trainId == null) {
			throw MasterDataService.invalid("열차를 골라 주세요.");
		}
		return jdbc.query("SELECT train_id, route_id, direction FROM train WHERE train_id = ?",
				(rs, i) -> new TrainRow(rs.getLong(1), rs.getLong(2), rs.getString(3)), trainId).stream().findFirst()
				.orElseThrow(() -> new BusinessException(TrainAdminErrorCode.REFERENCE_NOT_FOUND, "없는 열차입니다: " + trainId));
	}

	static Set<DayOfWeek> weekdays(List<Integer> in) {
		if (in == null || in.isEmpty()) {
			return EnumSet.allOf(DayOfWeek.class);
		}
		Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
		for (Integer d : in) {
			if (d == null || d < 1 || d > 7) {
				throw MasterDataService.invalid("요일은 1(월)~7(일)입니다.");
			}
			out.add(DayOfWeek.of(d));
		}
		return out;
	}

	private long nextId(String sql, long base) {
		Long max = jdbc.queryForObject(sql, Long.class);
		return Math.max(max == null ? 1 : max, base);
	}

	private static Timestamp ts(LocalDateTime t) {
		return t == null ? null : Timestamp.valueOf(t);
	}
}
