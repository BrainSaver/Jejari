package com.jejari.admin;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ADM-03이 쓰는 Oracle 집계. 10초마다 불리므로 <b>인덱스를 타는 모양만</b> 쓴다.
 *
 * <p>쓰는 인덱스 (CLAUDE.md "쓰기 폭주 테이블에 인덱스를 추가할 땐 쓰는 쿼리를 같이 적는다"):
 * <ul>
 *   <li>{@code ix_reservation_run_id} — 운행 하나의 예약만 읽는다. 한 운행의 예약 행은 좌석 수에
 *       묶여 많아도 수천 건이라 그 안에서 시각을 걸러도 가볍다. <b>새 인덱스를 만들지 않았다.</b></li>
 *   <li>{@code uq_payment_phs12n}(reservation_id 선행) · {@code ix_cancellation_reservation_id} —
 *       결제·취소에는 run_id가 없어 예약을 거쳐 조인한다</li>
 *   <li>{@code ix_pay_recheck} — 결과 모름 건수. §6.2의 조회식과 <b>같은 CASE 식</b>이라야 인덱스를 탄다</li>
 * </ul>
 *
 * <p>분당 이력({@link #history})도 같은 인덱스·같은 조인이다. 창이 60초에서 최대 60분으로 길어질 뿐
 * 운행 하나의 행만 읽는 건 그대로라 새 인덱스가 필요 없다.
 *
 * <p>시각 비교는 Oracle {@code SYSTIMESTAMP} 기준이다. 화면이 여러 대의 앱을 번갈아 찔러도
 * 같은 창을 보게 하려는 것이다.
 */
@Repository
public class ReservationStatsReader {

	private static final String RESERVATIONS = """
			SELECT COUNT(*) FROM reservation
			 WHERE run_id = ? AND created_at >= SYSTIMESTAMP - NUMTODSINTERVAL(?, 'SECOND')""";

	/** 승인된 결제만 센다 (요청 건수가 아니라 실제로 팔린 수) */
	private static final String PAYMENTS = """
			SELECT COUNT(*)
			  FROM payment p
			  JOIN reservation r ON r.reservation_id = p.reservation_id
			 WHERE r.run_id = ? AND p.status = 'APPROVED'
			   AND p.approved_at >= SYSTIMESTAMP - NUMTODSINTERVAL(?, 'SECOND')""";

	/** 만료는 돈이 오가지 않아 cancellation 행이 없다 (ERD cancel_type 설명). 여기 수치는 "사용자·운영 취소"다 */
	private static final String CANCELS = """
			SELECT COUNT(*)
			  FROM cancellation c
			  JOIN reservation r ON r.reservation_id = c.reservation_id
			 WHERE r.run_id = ? AND c.canceled_at >= SYSTIMESTAMP - NUMTODSINTERVAL(?, 'SECOND')""";

	/** 결과를 모르는 결제 (concurrency.md §6.2). 운행과 무관한 전체 건수 */
	private static final String UNKNOWN_PAYMENTS = """
			SELECT COUNT(*) FROM payment
			 WHERE (CASE WHEN status = 'REQUESTED' THEN next_check_at END) < SYSTIMESTAMP""";

	/** 분 경계. 이력의 마지막 칸(진행 중인 분)의 시작 */
	private static final String NOW_MINUTE = "SELECT TRUNC(SYSTIMESTAMP, 'MI') FROM dual";

	private static final String RESERVATIONS_BY_MINUTE = """
			SELECT TRUNC(created_at, 'MI'), COUNT(*) FROM reservation
			 WHERE run_id = ? AND created_at >= ?
			 GROUP BY TRUNC(created_at, 'MI')""";

	private static final String PAYMENTS_BY_MINUTE = """
			SELECT TRUNC(p.approved_at, 'MI'), COUNT(*)
			  FROM payment p
			  JOIN reservation r ON r.reservation_id = p.reservation_id
			 WHERE r.run_id = ? AND p.status = 'APPROVED' AND p.approved_at >= ?
			 GROUP BY TRUNC(p.approved_at, 'MI')""";

	private static final String CANCELS_BY_MINUTE = """
			SELECT TRUNC(c.canceled_at, 'MI'), COUNT(*)
			  FROM cancellation c
			  JOIN reservation r ON r.reservation_id = c.reservation_id
			 WHERE r.run_id = ? AND c.canceled_at >= ?
			 GROUP BY TRUNC(c.canceled_at, 'MI')""";

	private final JdbcTemplate jdbc;

	public ReservationStatsReader(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 최근 {@code windowSec}초 동안 그 운행에 생긴 예약·승인된 결제·취소 건수 */
	public RunDashboard.Rates rates(long runId, int windowSec) {
		return new RunDashboard.Rates(
				count(RESERVATIONS, runId, windowSec),
				count(PAYMENTS, runId, windowSec),
				count(CANCELS, runId, windowSec));
	}

	/**
	 * 최근 {@code minutes}분의 분별 건수. 지금 진행 중인 분이 마지막 칸이고, 건수가 0인 분도 채워 준다.
	 * 쿼리는 4번 (지금 분 1 + 종류별 GROUP BY 3).
	 */
	public RateHistory history(long runId, int minutes) {
		LocalDateTime now = jdbc.queryForObject(NOW_MINUTE, Timestamp.class).toLocalDateTime();
		LocalDateTime from = now.minusMinutes(minutes - 1L);
		Map<LocalDateTime, Integer> resv = byMinute(RESERVATIONS_BY_MINUTE, runId, from);
		Map<LocalDateTime, Integer> pay = byMinute(PAYMENTS_BY_MINUTE, runId, from);
		Map<LocalDateTime, Integer> cancel = byMinute(CANCELS_BY_MINUTE, runId, from);
		List<RateHistory.Minute> rows = new ArrayList<>(minutes);
		for (LocalDateTime m = from; !m.isAfter(now); m = m.plusMinutes(1)) {
			rows.add(new RateHistory.Minute(m, resv.getOrDefault(m, 0), pay.getOrDefault(m, 0),
					cancel.getOrDefault(m, 0)));
		}
		return new RateHistory(runId, now, rows);
	}

	private Map<LocalDateTime, Integer> byMinute(String sql, long runId, LocalDateTime from) {
		Map<LocalDateTime, Integer> out = new HashMap<>();
		jdbc.query(sql, rs -> {
			out.put(rs.getTimestamp(1).toLocalDateTime(), rs.getInt(2));
		}, runId, Timestamp.valueOf(from));
		return out;
	}

	public int unknownPayments() {
		Integer n = jdbc.queryForObject(UNKNOWN_PAYMENTS, Integer.class);
		return n == null ? 0 : n;
	}

	private int count(String sql, long runId, int windowSec) {
		Integer n = jdbc.queryForObject(sql, Integer.class, runId, windowSec);
		return n == null ? 0 : n;
	}
}
