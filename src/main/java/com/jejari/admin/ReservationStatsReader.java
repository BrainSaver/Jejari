package com.jejari.admin;

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

	public int unknownPayments() {
		Integer n = jdbc.queryForObject(UNKNOWN_PAYMENTS, Integer.class);
		return n == null ? 0 : n;
	}

	private int count(String sql, long runId, int windowSec) {
		Integer n = jdbc.queryForObject(sql, Integer.class, runId, windowSec);
		return n == null ? 0 : n;
	}
}
