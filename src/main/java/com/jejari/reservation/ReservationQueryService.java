package com.jejari.reservation;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jejari.common.error.BusinessException;
import com.jejari.reservation.CancelFeeCalculator.Band;
import com.jejari.reservation.CancelFeeCalculator.Quote;
import com.jejari.seat.SeatErrorCode;

/**
 * RSV-04 예매 내역 목록·상세 (읽기 전용).
 *
 * <ul>
 *   <li><b>내 예약만</b>: 모든 조회에 {@code member_id = 나}를 건다. 남의 예약 ID는 "없음"(404)과 똑같이 답해
 *       예약 ID가 있는지조차 알 수 없게 한다.</li>
 *   <li><b>승차권 번호는 확정(CONFIRMED)일 때만</b>: 선점 때 발급되지만 확정 전에는 효력이 없다 (기능설계서 RSV-04).</li>
 *   <li><b>결제 기한은 결제 전(HOLD·PAYMENT_PENDING)일 때만</b>.</li>
 *   <li><b>취소 가능 여부</b>: HOLD는 수수료 없이 가능(RSV-05), PAYMENT_PENDING은 결제 중이라 불가,
 *       CONFIRMED는 {@link CancelFeeCalculator}로 수수료·환불 예정액까지 계산 (CNL-01과 같은 계산).</li>
 *   <li>목록은 예약 ID 내림차순(최근 것 먼저), 커서 페이지. 회원별 조회는 UNIQUE(member_id, idempotency_key) 인덱스를 탄다.</li>
 * </ul>
 */
@Service
public class ReservationQueryService {
	private static final Logger log = LoggerFactory.getLogger(ReservationQueryService.class);

	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 50;
	static final long DEFAULT_AFTER_DEPARTURE_LIMIT_MIN = 20;

	public enum Status { HOLD, PAYMENT_PENDING, CONFIRMED, CANCELED, EXPIRED }

	private final JdbcTemplate jdbc;
	private final Clock clock;

	public ReservationQueryService(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	// ------------------------------------------------------------ 응답 모양

	public record Station(String stationId, String name, Instant time) {
	}

	public record Summary(long reservationId, String status, long runId, LocalDate runDate, String trainNo,
			Station from, Station to, int seatCount, long totalAmount, Instant heldAt, Instant payDeadlineAt) {
	}

	public record Page(List<Summary> items, Long nextBefore) {
	}

	public record Seat(int carNo, String seatNo, String grade, String passengerType, long amount, String ticketNo) {
	}

	public record Payment(String status, long amount, Instant requestedAt, Instant approvedAt) {
	}

	public record Cancellation(String cancelType, Instant canceledAt, long fee, long refundAmount, String refundStatus) {
	}

	/**
	 * @param cancelable    지금 취소할 수 있나
	 * @param reason        불가 사유 코드 (PAYMENT_IN_PROGRESS, CANCEL_NOT_ALLOWED, ALREADY_CLOSED, FEE_POLICY_MISSING …)
	 * @param fee           예상 수수료 (확정 예약만, 아니면 0)
	 * @param refund        예상 환불액
	 * @param feeRate       적용 수수료율
	 * @param minutesBeforeDeparture 출발까지 남은 분
	 * @param policyId      적용한 cancel_fee_policy (취소 기록용, 응답에는 안 나감)
	 */
	public record CancelInfo(boolean cancelable, String reason, long fee, long refund, BigDecimal feeRate,
			Long minutesBeforeDeparture, @JsonIgnore Long policyId) {
	}

	public record Detail(Summary summary, List<Seat> seats, Payment payment, Cancellation cancellation,
			CancelInfo cancel) {
	}

	// ------------------------------------------------------------ 목록

	private static final String BASE = """
			SELECT r.reservation_id, r.status, r.run_id, tr.run_date, t.train_no,
			       r.from_station_id, fs.station_name, dep.departure_time,
			       r.to_station_id, tst.station_name, arr.arrival_time,
			       r.seat_count, r.total_amount, r.held_at, r.pay_deadline_at, tr.sale_event_id
			  FROM reservation r
			  JOIN train_run tr ON tr.run_id = r.run_id
			  JOIN train t ON t.train_id = tr.train_id
			  JOIN station fs ON fs.station_id = r.from_station_id
			  JOIN station tst ON tst.station_id = r.to_station_id
			  LEFT JOIN train_stop dep ON dep.run_id = r.run_id AND dep.station_id = r.from_station_id
			  LEFT JOIN train_stop arr ON arr.run_id = r.run_id AND arr.station_id = r.to_station_id
			 WHERE r.member_id = ?""";

	/**
	 * @param statuses 비었으면 전체
	 * @param before   이 예약 ID보다 작은 것부터 (다음 페이지 커서, 첫 페이지는 null)
	 * @param size     1~{@value #MAX_SIZE}
	 */
	public Page list(long memberId, Set<Status> statuses, Long before, int size) {
		int n = Math.max(1, Math.min(size, MAX_SIZE));
		StringBuilder sql = new StringBuilder(BASE);
		List<Object> args = new ArrayList<>();
		args.add(memberId);
		if (statuses != null && !statuses.isEmpty() && !statuses.containsAll(EnumSet.allOf(Status.class))) {
			// enum 이름만 들어간다 (자리표시자). 사용자 문자열이 SQL에 붙을 길이 없다
			sql.append(" AND r.status IN (").append(String.join(", ", Collections.nCopies(statuses.size(), "?")))
					.append(')');
			statuses.forEach(s -> args.add(s.name()));
		}
		if (before != null) {
			sql.append(" AND r.reservation_id < ?");
			args.add(before);
		}
		sql.append(" ORDER BY r.reservation_id DESC FETCH FIRST ").append(n + 1).append(" ROWS ONLY");
		List<Summary> rows = jdbc.query(sql.toString(), (rs, i) -> summary(rs), args.toArray());
		Long next = null;
		if (rows.size() > n) {
			rows = rows.subList(0, n);
			next = rows.get(n - 1).reservationId();
		}
		return new Page(List.copyOf(rows), next);
	}

	private static Summary summary(ResultSet rs) throws SQLException {
		String status = rs.getString(2);
		boolean unpaid = "HOLD".equals(status) || "PAYMENT_PENDING".equals(status);
		return new Summary(rs.getLong(1), status, rs.getLong(3), rs.getDate(4).toLocalDate(), rs.getString(5),
				new Station(rs.getString(6), rs.getString(7), instant(rs.getTimestamp(8))),
				new Station(rs.getString(9), rs.getString(10), instant(rs.getTimestamp(11))),
				rs.getInt(12), rs.getLong(13), instant(rs.getTimestamp(14)),
				unpaid ? instant(rs.getTimestamp(15)) : null);
	}

	private static Instant instant(Timestamp t) {
		return t == null ? null : t.toInstant();
	}

	// ------------------------------------------------------------ 상세

	private record Head(Summary summary, Long saleEventId) {
	}

	public Detail detail(long memberId, long reservationId) {
		List<Head> heads = jdbc.query(BASE + " AND r.reservation_id = ?", (rs, i) -> {
			long ev = rs.getLong(16);
			return new Head(summary(rs), rs.wasNull() ? null : ev);
		}, memberId, reservationId);
		if (heads.isEmpty()) {
			throw new BusinessException(SeatErrorCode.RESERVATION_NOT_FOUND); // 없음과 남의 것을 구분하지 않는다
		}
		Head head = heads.get(0);
		Summary s = head.summary();
		boolean confirmed = "CONFIRMED".equals(s.status());

		List<Seat> seats = jdbc.query("""
				SELECT s.car_no, s.seat_no, s.seat_grade, rs.passenger_type, rs.amount, rs.ticket_no
				  FROM reservation_seat rs JOIN seat s ON s.seat_id = rs.seat_id
				 WHERE rs.reservation_id = ?
				 ORDER BY s.car_no, s.seat_no""",
				(rs, i) -> new Seat(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getLong(5), confirmed ? rs.getString(6) : null),
				reservationId);

		List<Payment> payments = jdbc.query("""
				SELECT status, amount, requested_at, approved_at FROM payment
				 WHERE reservation_id = ? ORDER BY requested_at DESC FETCH FIRST 1 ROWS ONLY""",
				(rs, i) -> new Payment(rs.getString(1), rs.getLong(2), instant(rs.getTimestamp(3)),
						instant(rs.getTimestamp(4))),
				reservationId);
		Payment payment = payments.isEmpty() ? null : payments.get(0);

		List<Cancellation> cancels = jdbc.query("""
				SELECT cancel_type, canceled_at, cancel_fee, refund_amount, refund_status FROM cancellation
				 WHERE reservation_id = ? ORDER BY canceled_at DESC FETCH FIRST 1 ROWS ONLY""",
				(rs, i) -> new Cancellation(rs.getString(1), instant(rs.getTimestamp(2)), rs.getLong(3), rs.getLong(4),
						rs.getString(5)),
				reservationId);

		return new Detail(s, seats, payment, cancels.isEmpty() ? null : cancels.get(0),
				cancelInfo(s, head.saleEventId(), seats, payment));
	}

	private CancelInfo cancelInfo(Summary s, Long saleEventId, List<Seat> seats, Payment payment) {
		switch (s.status()) {
			case "HOLD":
				return new CancelInfo(true, null, 0, 0, null, null, null); // 결제 전 취소: 수수료 없음 (RSV-05)
			case "PAYMENT_PENDING":
				return new CancelInfo(false, "PAYMENT_IN_PROGRESS", 0, 0, null, null, null);
			case "CONFIRMED":
				break;
			default:
				return new CancelInfo(false, "ALREADY_CLOSED", 0, 0, null, null, null);
		}
		long paid = payment != null && "APPROVED".equals(payment.status()) ? payment.amount() : s.totalAmount();
		Quote q = CancelFeeCalculator.quote(bands(saleEventId), s.from().time(), clock.instant(),
				seats.stream().map(Seat::amount).toList(), paid, afterDepartureLimitMin());
		if (!q.cancelable()) {
			if (q.reason() == CancelFeeCalculator.Reason.FEE_POLICY_MISSING) {
				log.error("취소 수수료 정책이 없거나 겹침 (운행 {}, 행사 {}) → 취소 불가로 표시", s.runId(), saleEventId);
			}
			String code = q.reason() == CancelFeeCalculator.Reason.AFTER_DEPARTURE_LIMIT ? "CANCEL_NOT_ALLOWED"
					: q.reason().name();
			return new CancelInfo(false, code, 0, 0, null, q.minutesBeforeDeparture(), null);
		}
		return new CancelInfo(true, null, q.fee(), q.refund(), q.feeRate(), q.minutesBeforeDeparture(), q.policyId());
	}

	/** 운행의 행사 정책. 행사가 없거나 그 행사 정책이 비었으면 상시 정책(sale_event_id NULL) */
	List<Band> bands(Long saleEventId) {
		if (saleEventId != null) {
			List<Band> ev = loadBands("sale_event_id = ?", saleEventId);
			if (!ev.isEmpty()) {
				return ev;
			}
		}
		return loadBands("sale_event_id IS NULL");
	}

	private List<Band> loadBands(String where, Object... args) {
		return jdbc.query("""
				SELECT fee_policy_id, min_minutes_before, max_minutes_before, fee_rate, after_departure
				  FROM cancel_fee_policy WHERE""" + " " + where,
				(rs, i) -> {
					long min = rs.getLong(2);
					boolean minNull = rs.wasNull();
					long max = rs.getLong(3);
					boolean maxNull = rs.wasNull();
					return new Band(rs.getLong(1), minNull ? null : min, maxNull ? null : max, rs.getBigDecimal(4),
							"Y".equals(rs.getString(5)));
				}, args);
	}

	private long afterDepartureLimitMin() {
		try {
			List<String> v = jdbc.queryForList(
					"SELECT policy_value FROM app_policy WHERE policy_key = 'cancel.after_departure_limit_min'",
					String.class);
			return v.isEmpty() ? DEFAULT_AFTER_DEPARTURE_LIMIT_MIN : Math.max(0, Long.parseLong(v.get(0).trim()));
		} catch (RuntimeException e) {
			log.warn("app_policy cancel.after_departure_limit_min을 읽지 못해 기본값 {}분: {}",
					DEFAULT_AFTER_DEPARTURE_LIMIT_MIN, e.toString());
			return DEFAULT_AFTER_DEPARTURE_LIMIT_MIN;
		}
	}
}
