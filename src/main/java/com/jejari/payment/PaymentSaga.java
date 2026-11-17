package com.jejari.payment;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.event.EventType;
import com.jejari.event.outbox.OutboxRecorder;
import com.jejari.seat.ReleaseReason;
import com.jejari.seat.SeatPaymentService;
import com.jejari.seat.SeatReleaseService;

/**
 * EVT-05 Saga: 결제 결과로 예약을 앞으로 진행(확정)하거나 보상(좌석 반환)합니다 (concurrency.md §5).
 *
 * <pre>
 * saga_instance.current_step   SEAT_HELD ──결제 시작──▶ PAYING ──승인──▶ CONFIRMED (status DONE)
 *                                  │                     │
 *                                  └─ HOLD 만료(B) ──────┴─ 결제 실패 ──▶ status COMPENSATED
 *                                                           (좌석 반환·카운터 감소·Redis 정리 = SeatReleaseService)
 * </pre>
 *
 * <p>PG 승인 응답, §6.2 스캐너 조회 결과가 모두 여기로 옵니다. 같은 결과가 여러 번 와도 안전합니다:
 * 모든 트랜잭션이 먼저 reservation 행을 잠그고(§0.4-1), payment 상태를 현재 상태 조건으로만 바꿉니다(§0.3).
 * 보상은 강동균(B)의 {@link SeatReleaseService#releaseInCurrentTx}를 같은 트랜잭션에서 부르며, 두 번 불러도 한 번만 일어납니다.
 */
@Service
public class PaymentSaga {

	private static final Logger log = LoggerFactory.getLogger(PaymentSaga.class);
	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
	private static final int FAIL_REASON_MAX = 100;

	/** 승인 결과를 처리한 결과 */
	public enum Approval {
		/** 이번에 확정함 */
		CONFIRMED,
		/** 이미 확정돼 있음 (중복 수신) */
		ALREADY_CONFIRMED,
		/** 승인됐지만 좌석을 잃어 확정하지 못함 → 결제는 APPROVED로 기록, 늦은 승인 환불 필요 (PAY-05) */
		CONFIRM_FAILED,
		/** 실패로 정리한 뒤 승인이 옴 → 결제는 APPROVED로 기록, 예약은 되살리지 않음, 환불 필요 (PAY-05) */
		LATE_APPROVAL,
		/** 이미 늦은 승인 환불 중 */
		REFUND_IN_PROGRESS,
		REFUNDED,
		NOT_FOUND
	}

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final SeatPaymentService seats;
	private final SeatReleaseService release;
	private final OutboxRecorder outbox;

	public PaymentSaga(JdbcTemplate jdbc, TransactionTemplate tx, SeatPaymentService seats, SeatReleaseService release,
			OutboxRecorder outbox) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.seats = seats;
		this.release = release;
		this.outbox = outbox;
	}

	/** §5.1 승인 */
	public Approval approve(long reservationId, long paymentId, String pgTxId) {
		Approval result = tx.execute(st -> {
			Locked rv = lock(reservationId);
			String ps = paymentStatus(paymentId);
			if (rv == null || ps == null) {
				return Approval.NOT_FOUND;
			}
			switch (ps) {
				case "REQUESTED" -> {
					// 4. 좌석 확정. 예약이 결제 중이 아니거나 K행이 아니면 확정 실패 경로 (이 트랜잭션은 롤백)
					if (!"PAYMENT_PENDING".equals(rv.status()) || !seats.confirmSeatsInCurrentTx(reservationId)) {
						st.setRollbackOnly();
						return Approval.CONFIRM_FAILED;
					}
					confirm(reservationId, paymentId, pgTxId, rv.source());
					return Approval.CONFIRMED;
				}
				case "APPROVED" -> {
					return "CONFIRMED".equals(rv.status()) ? Approval.ALREADY_CONFIRMED : Approval.REFUND_IN_PROGRESS;
				}
				case "REFUNDED" -> {
					return Approval.REFUNDED;
				}
				default -> { // FAILED: 늦은 승인. 돈은 빠졌는데 예약은 이미 풀림. 좌석은 남에게 갔을 수 있어 되살리지 않는다
					jdbc.update("""
							UPDATE payment SET status = 'APPROVED', pg_tx_id = ?, approved_at = SYSTIMESTAMP, updated_at = SYSTIMESTAMP
							 WHERE payment_id = ? AND status = 'FAILED'""", pgTxId, paymentId);
					log.error("늦은 승인: 결제 {} (예약 {}) 실패 정리 뒤 승인됨. 환불 필요 (PAY-05)", paymentId, reservationId);
					return Approval.LATE_APPROVAL;
				}
			}
		});
		if (result == Approval.CONFIRM_FAILED) {
			confirmFailed(reservationId, paymentId, pgTxId);
		}
		return result;
	}

	/**
	 * §5.2 실패. 결제 실패는 예약 만료로 처리합니다 (재시도 없음, 사용자는 다시 예매).
	 *
	 * @return 이번에 실패로 정리했으면 true, 다른 경로가 이미 처리했으면 false
	 */
	public boolean fail(long reservationId, long paymentId, String reason) {
		String why = reason == null ? "UNKNOWN" : reason.length() > FAIL_REASON_MAX ? reason.substring(0, FAIL_REASON_MAX) : reason;
		Boolean done = tx.execute(st -> {
			Locked rv = lock(reservationId);
			if (rv == null) {
				return false;
			}
			int n = jdbc.update("""
					UPDATE payment SET status = 'FAILED', fail_reason = ?, updated_at = SYSTIMESTAMP
					 WHERE payment_id = ? AND status = 'REQUESTED'""", why, paymentId);
			if (n != 1) {
				return false;
			}
			if ("PAYMENT_PENDING".equals(rv.status())) {
				// 보상: 예약 EXPIRED, 좌석 반환, 카운터 감소, Saga COMPENSATED, 이벤트(PAY_FAILED). Redis는 커밋 뒤
				release.releaseInCurrentTx(reservationId, ReleaseReason.PAY_FAILED);
			}
			return true;
		});
		return Boolean.TRUE.equals(done);
	}

	/** §5.1-5~8 */
	private void confirm(long r, long p, String pgTxId, String source) {
		require(jdbc.update("""
				UPDATE payment SET status = 'APPROVED', pg_tx_id = ?, approved_at = SYSTIMESTAMP, updated_at = SYSTIMESTAMP
				 WHERE payment_id = ? AND status = 'REQUESTED'""", pgTxId, p), "payment APPROVED");
		require(jdbc.update("""
				UPDATE reservation SET status = 'CONFIRMED', confirmed_at = SYSTIMESTAMP, updated_at = SYSTIMESTAMP,
				       version = version + 1
				 WHERE reservation_id = ? AND status = 'PAYMENT_PENDING'""", r), "reservation CONFIRMED");
		jdbc.update("""
				UPDATE saga_instance SET current_step = 'CONFIRMED', status = 'DONE', updated_at = SYSTIMESTAMP
				 WHERE reservation_id = ? AND status = 'RUNNING'""", r);
		Long waitlistId = null;
		if ("WAITLIST".equals(source)) {
			List<Long> wl = jdbc.queryForList("SELECT waitlist_id FROM waitlist WHERE offered_reservation_id = ? AND status = 'OFFERED'",
					Long.class, r);
			waitlistId = wl.isEmpty() ? null : wl.get(0);
			jdbc.update("""
					UPDATE waitlist SET status = 'ACCEPTED', updated_at = SYSTIMESTAMP
					 WHERE offered_reservation_id = ? AND status = 'OFFERED'""", r);
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("reservationId", r);
		payload.put("paymentId", p);
		payload.put("pgTxId", pgTxId);
		payload.put("confirmedAt", LocalDateTime.now(ZONE).atZone(ZONE).toOffsetDateTime().toString());
		payload.put("source", source);
		payload.put("waitlistId", waitlistId);
		String eventId = outbox.recordReservation(r, EventType.RESERVATION_CONFIRMED, payload);
		history(jdbc, r, "PAYMENT_PENDING", "CONFIRMED", eventId, "SYSTEM");
	}

	/**
	 * 확정 실패 (§5.1 끝): 돈은 승인됐는데 좌석을 잃었거나 예약이 이미 풀림. 결제를 APPROVED로 남기고(돈의 사실),
	 * 예약이 아직 결제 중이면 만료·좌석 반환까지 한 트랜잭션에서 합니다. 환불(PAY-05, P2)은 아직 자동이 아니라 로그로 알립니다.
	 */
	private void confirmFailed(long r, long p, String pgTxId) {
		tx.executeWithoutResult(st -> {
			Locked rv = lock(r);
			int n = jdbc.update("""
					UPDATE payment SET status = 'APPROVED', pg_tx_id = ?, approved_at = SYSTIMESTAMP, updated_at = SYSTIMESTAMP
					 WHERE payment_id = ? AND status = 'REQUESTED'""", pgTxId, p);
			if (n == 1 && rv != null && "PAYMENT_PENDING".equals(rv.status())) {
				release.releaseInCurrentTx(r, ReleaseReason.PAY_FAILED);
			}
			if (n == 1) {
				log.error("확정 실패: 결제 {} (예약 {}) 승인됐지만 좌석을 잃음. 환불 필요 (PAY-05)", p, r);
			}
		});
	}

	private record Locked(String status, String source) {
	}

	/** 그 예약의 트랜잭션을 줄 세운다 (§0.4-1). 승인·실패·스캐너가 같은 결제를 동시에 바꾸지 못한다 */
	private Locked lock(long r) {
		List<Locked> rows = jdbc.query("SELECT status, source FROM reservation WHERE reservation_id = ? FOR UPDATE",
				(rs, i) -> new Locked(rs.getString(1), rs.getString(2)), r);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private String paymentStatus(long p) {
		List<String> rows = jdbc.queryForList("SELECT status FROM payment WHERE payment_id = ?", String.class, p);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private static void require(int rows, String what) {
		if (rows != 1) {
			throw new IllegalStateException(what + " 영향 행 " + rows + " (예약 락을 쥔 상태라 1이어야 함)");
		}
	}

	static void history(JdbcTemplate jdbc, long r, String from, String to, String eventId, String actor) {
		jdbc.update("""
				INSERT INTO reservation_history (history_id, reservation_id, from_status, to_status, event_id, actor_type, changed_at)
				VALUES (reservation_history_seq.NEXTVAL, ?, ?, ?, ?, ?, SYSTIMESTAMP)""", r, from, to, eventId, actor);
	}
}
