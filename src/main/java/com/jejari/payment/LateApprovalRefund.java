package com.jejari.payment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.event.EventType;
import com.jejari.event.outbox.OutboxRecorder;
import com.jejari.payment.pg.PgClient;
import com.jejari.payment.pg.PgClient.RefundResult;

/**
 * PAY-05 늦은 승인 환불 (concurrency.md §7.3). 돈은 승인됐는데 예약을 확정할 수 없을 때 전액을 돌려줍니다.
 *
 * <pre>
 * Saga 트랜잭션 안: payment → APPROVED + cancellation INSERT (PAY_FAILED, 전액, 수수료 0, REQUESTED)   ← {@link #request}
 * 커밋 뒤:          PG 환불 (환불 키 cancel:{cancel_id}, 두 번 보내도 한 번만)                       ← {@link #refund}
 * 새 트랜잭션:      성공 → cancellation DONE + payment REFUNDED + outbox REFUND_COMPLETED
 *                  실패 → cancellation FAILED (재시도 배치 CNL-03 대상)
 * </pre>
 *
 * <p>예약은 되살리지 않습니다. 좌석은 이미 다른 사람에게 갔을 수 있습니다.
 */
@Component
public class LateApprovalRefund {

	private static final Logger log = LoggerFactory.getLogger(LateApprovalRefund.class);

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final PgClient pg;
	private final OutboxRecorder outbox;

	public LateApprovalRefund(JdbcTemplate jdbc, TransactionTemplate tx, PgClient pg, OutboxRecorder outbox) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.pg = pg;
		this.outbox = outbox;
	}

	/**
	 * 환불할 일을 남깁니다. payment를 APPROVED로 바꾼 <b>같은 트랜잭션</b>에서 불러야 합니다.
	 * 그래야 "돈은 승인됐다"와 "돌려줘야 한다"가 같이 커밋됩니다.
	 *
	 * @return cancel_id
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public long request(long reservationId, long paymentId) {
		long amount = jdbc.queryForObject("SELECT amount FROM payment WHERE payment_id = ?", Long.class, paymentId);
		long cancelId = jdbc.queryForObject("SELECT cancellation_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO cancellation (cancel_id, reservation_id, cancel_type, canceled_at, refund_amount, cancel_fee,
				  refund_status, resold_yn)
				VALUES (?, ?, 'PAY_FAILED', SYSTIMESTAMP, ?, 0, 'REQUESTED', 'N')""", cancelId, reservationId, amount);
		return cancelId;
	}

	/**
	 * 커밋 뒤 PG 환불. 실패해도 예외를 던지지 않고 FAILED로 남깁니다 (재시도 배치 대상).
	 *
	 * @return 최종 refund_status (DONE / FAILED). 이미 끝난 건이면 그 상태
	 */
	public String refund(long cancelId) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("환불은 트랜잭션 밖에서 (§0.6)");
		}
		List<Target> rows = jdbc.query("""
				SELECT c.reservation_id, c.refund_amount, c.refund_status, p.payment_id, p.pg_tx_id
				  FROM cancellation c JOIN payment p ON p.reservation_id = c.reservation_id AND p.status = 'APPROVED'
				 WHERE c.cancel_id = ? AND c.cancel_type = 'PAY_FAILED'""",
				(rs, i) -> new Target(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5)),
				cancelId);
		if (rows.isEmpty()) {
			// 이미 환불돼 payment가 REFUNDED이거나 없는 건
			List<String> st = jdbc.queryForList("SELECT refund_status FROM cancellation WHERE cancel_id = ?", String.class,
					cancelId);
			return st.isEmpty() ? null : st.get(0);
		}
		Target t = rows.get(0);
		if ("DONE".equals(t.status())) {
			return "DONE";
		}

		RefundResult r;
		try {
			r = pg.refund(t.paymentId(), t.pgTxId(), t.amount(), "cancel:" + cancelId);
		} catch (RuntimeException e) {
			log.warn("늦은 승인 환불 요청 실패 (취소 {}), 재시도 대상으로 남김: {}", cancelId, e.toString());
			r = new RefundResult(false, null, "PG_ERROR");
		}
		RefundResult result = r;

		tx.executeWithoutResult(st -> {
			// 승인·실패·스캐너와 같은 줄에 선다 (§0.4-1)
			jdbc.query("SELECT 1 FROM reservation WHERE reservation_id = ? FOR UPDATE", rs -> {
			}, t.reservationId());
			if (result.done()) {
				int changed = jdbc.update("""
						UPDATE cancellation SET refund_status = 'DONE', refund_pg_tx_id = ?, updated_at = SYSTIMESTAMP
						 WHERE cancel_id = ? AND refund_status <> 'DONE'""", result.refundTxId(), cancelId);
				jdbc.update("""
						UPDATE payment SET status = 'REFUNDED', updated_at = SYSTIMESTAMP
						 WHERE payment_id = ? AND status = 'APPROVED'""", t.paymentId());
				if (changed == 1 && t.amount() > 0) {
					Map<String, Object> payload = new LinkedHashMap<>();
					payload.put("reservationId", t.reservationId());
					payload.put("cancelId", cancelId);
					payload.put("refundAmount", t.amount());
					payload.put("refundPgTxId", result.refundTxId());
					outbox.recordReservation(t.reservationId(), EventType.REFUND_COMPLETED, payload);
				}
				log.info("늦은 승인 환불 완료: 예약 {} 취소 {} 금액 {}", t.reservationId(), cancelId, t.amount());
			} else {
				jdbc.update("""
						UPDATE cancellation SET refund_status = 'FAILED', updated_at = SYSTIMESTAMP
						 WHERE cancel_id = ? AND refund_status = 'REQUESTED'""", cancelId);
				log.error("늦은 승인 환불 실패 (예약 {}, 취소 {}, 사유 {}) → FAILED, 재시도 배치 대상", t.reservationId(),
						cancelId, result.reason());
			}
		});
		return result.done() ? "DONE" : "FAILED";
	}

	private record Target(long reservationId, long amount, String status, long paymentId, String pgTxId) {
	}
}
