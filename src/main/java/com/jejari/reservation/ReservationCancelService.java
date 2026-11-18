package com.jejari.reservation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.error.BusinessException;
import com.jejari.event.EventType;
import com.jejari.event.outbox.OutboxRecorder;
import com.jejari.payment.pg.PgClient;
import com.jejari.payment.pg.PgClient.RefundResult;
import com.jejari.reservation.ReservationQueryService.CancelInfo;
import com.jejari.reservation.ReservationQueryService.Detail;
import com.jejari.risk.admin.CaseActionService;
import com.jejari.seat.SeatErrorCode;
import com.jejari.seat.SeatReleaseService;
import com.jejari.seat.SeatReleaseService.ReturnedSeats;

/**
 * CNL-01 취소 수수료 미리보기, CNL-02 확정 예약 취소·환불 (concurrency.md §8). 결제 전 취소(RSV-05)는 좌석 쪽
 * {@link SeatReleaseService#cancelBeforePay}가 하고, 취소 요청 하나({@code POST /reservations/{id}/cancel})를
 * 상태에 따라 나눠 보낸다 ({@link #cancel}).
 *
 * <pre>
 * 확정 취소 트랜잭션 (§8.1, actor USER)
 *  0. reservation 행 FOR UPDATE (내 예약, §0.4 락 순서의 첫 행)
 *  1. 상태 CONFIRMED 확인 → 수수료 다시 계산 (미리보기와 같은 계산, 잠근 뒤라 그 사이 바뀐 것 없음)
 *  2. reservation CONFIRMED → CANCELED (조건부 UPDATE)
 *  3. 좌석 SOLD 행 삭제 + 매수 카운터 감소 (SeatReleaseService.returnSoldSeatsInCurrentTx, 커밋 뒤 REOFFER)
 *  4. cancellation INSERT (USER, 수수료·환불액·구매 후 분·출발 전 분, refund_status REQUESTED, resold_yn N)
 *  5. outbox RESERVATION_CANCELED + reservation_history (CONFIRMED → CANCELED, USER)
 * 커밋 뒤 (§8.2): PG 환불 → 성공하면 cancellation DONE + payment REFUNDED (같은 트랜잭션), 실패하면 FAILED (CNL-03 재시도 대상)
 * </pre>
 *
 * <ul>
 *   <li><b>수수료를 잠근 뒤 다시 계산</b>: 미리보기 값을 클라이언트에서 받지 않는다. 남이 보낸 수수료를 믿으면 0원으로 바꿔 보낼 수 있다.</li>
 *   <li><b>환불은 트랜잭션 밖</b> (§0.6): PG 호출 중에 DB 락을 잡고 있지 않는다. 환불 키 {@code cancel:<cancel_id>}로 재시도해도 한 번만 환불된다.</li>
 *   <li>관리자 강제 취소(ADM-07, {@link #cancelByAdmin})도 같은 트랜잭션을 actor ADMIN으로 쓴다: 수수료 0·전액 환불·admin_action 기록.</li>
 *   <li>사용자 취소(USER)만 위험 점수 신호다. 이 취소는 예약 이벤트로 risk-signal이 받아 다음 1분 배치에 반영한다.</li>
 * </ul>
 */
@Service
public class ReservationCancelService {
	private static final Logger log = LoggerFactory.getLogger(ReservationCancelService.class);

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final ReservationQueryService queries;
	private final SeatReleaseService seats;
	private final OutboxRecorder outbox;
	private final PgClient pg;
	private final Clock clock;

	public ReservationCancelService(JdbcTemplate jdbc, TransactionTemplate tx, ReservationQueryService queries,
			SeatReleaseService seats, OutboxRecorder outbox, PgClient pg, Clock clock) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.queries = queries;
		this.seats = seats;
		this.outbox = outbox;
		this.pg = pg;
		this.clock = clock;
	}

	// ------------------------------------------------------------ CNL-01

	/**
	 * @param paidAmount             결제 금액
	 * @param departureAt            승차역 출발 시각
	 * @param minutesBeforeDeparture 출발까지 남은 분 (출발 후면 음수)
	 * @param feeRate                적용 수수료율
	 * @param fee                    수수료
	 * @param refund                 환불 예정액
	 */
	public record Quote(long reservationId, long paidAmount, Instant departureAt, long minutesBeforeDeparture,
			BigDecimal feeRate, long fee, long refund) {
	}

	public Quote quote(long memberId, long reservationId) {
		Detail d = queries.detail(memberId, reservationId); // 남의 예약·없는 예약은 여기서 404
		return toQuote(d);
	}

	private static Quote toQuote(Detail d) {
		if (!"CONFIRMED".equals(d.summary().status())) {
			throw new BusinessException(ReservationErrorCode.RESERVATION_NOT_CONFIRMED);
		}
		CancelInfo c = d.cancel();
		if (!c.cancelable()) {
			if (!"CANCEL_NOT_ALLOWED".equals(c.reason())) {
				log.error("확정 예약 {}의 수수료를 정할 수 없음 ({}) → 취소 불가", d.summary().reservationId(), c.reason());
			}
			throw new BusinessException(ReservationErrorCode.CANCEL_NOT_ALLOWED);
		}
		long paid = d.payment() != null && "APPROVED".equals(d.payment().status()) ? d.payment().amount()
				: d.summary().totalAmount();
		return new Quote(d.summary().reservationId(), paid, d.summary().from().time(), c.minutesBeforeDeparture(),
				c.feeRate(), c.fee(), c.refund());
	}

	// ------------------------------------------------------------ 취소 요청 나누기

	/**
	 * @param kind         BEFORE_PAY (결제 전 취소) / CONFIRMED (확정 취소)
	 * @param refundStatus 확정 취소의 환불 상태 (DONE / FAILED / REQUESTED), 결제 전 취소면 null
	 */
	public record CancelResponse(long reservationId, String status, String kind, Long runId, int seatCount,
			long cancelFee, long refundAmount, String refundStatus) {
	}

	/**
	 * 취소 요청 하나를 상태에 따라 나눈다. HOLD·PAYMENT_PENDING은 결제 전 취소(좌석 쪽 규칙: PAYMENT_PENDING은 409),
	 * CONFIRMED는 확정 취소. 상태를 읽은 뒤 바뀌어도 각 경로가 조건부 UPDATE라 안전하다.
	 */
	public CancelResponse cancel(long memberId, long reservationId) {
		List<String> st = jdbc.queryForList("SELECT status FROM reservation WHERE reservation_id = ? AND member_id = ?",
				String.class, reservationId, memberId);
		if (st.isEmpty()) {
			throw new BusinessException(SeatErrorCode.RESERVATION_NOT_FOUND);
		}
		if (!"CONFIRMED".equals(st.get(0))) {
			SeatReleaseService.CancelResult r = seats.cancelBeforePay(memberId, reservationId);
			return new CancelResponse(r.reservationId(), r.status(), "BEFORE_PAY", r.runId(), r.seatCount(), 0, 0,
					null);
		}
		return cancelConfirmed(memberId, reservationId);
	}

	// ------------------------------------------------------------ CNL-02

	private record Done(long cancelId, long paymentId, String pgTxId, long refund, long runId, int seatCount,
			long fee, long actionId) {
	}

	CancelResponse cancelConfirmed(long memberId, long reservationId) {
		return cancelConfirmed(memberId, reservationId, null).cancel();
	}

	// ------------------------------------------------------------ ADM-07

	/** 취소 결과. actionId = 관리자 강제 취소를 남긴 admin_action 행 (사용자 취소면 0) */
	public record AdminCancelResponse(CancelResponse cancel, long actionId) {
	}

	/** 관리자 정보 (§8.1 ADMIN). admin_action에 누가·왜를 남긴다 */
	private record AdminActor(long adminId, String memo) {
	}

	/**
	 * ADM-07 예약 강제 취소 (§8.1 actor ADMIN). 사용자 확정 취소와 같은 트랜잭션이고 다른 점은 셋:
	 * 남의 예약이어도 되고, 수수료 0·결제 금액 전액 환불(출발 뒤여도), admin_action(CANCEL_RESERVATION)에 관리자·사유를 남긴다.
	 * cancel_type ADMIN은 위험 점수에서 세지 않는다 ({@code RiskInputReader}·{@code SuspiciousCaseOpener}가 USER만 셈).
	 * 확정 예약만 받는다. 결제 전 예약은 기한이 지나면 스스로 풀리므로 강제 취소할 일이 없다.
	 *
	 * @param memo 사유 (5~500자, 필수)
	 */
	public AdminCancelResponse cancelByAdmin(long adminId, long reservationId, String memo) {
		String reason = CaseActionService.memo(memo);
		List<Long> owner = jdbc.queryForList("SELECT member_id FROM reservation WHERE reservation_id = ?", Long.class,
				reservationId);
		if (owner.isEmpty()) {
			throw new BusinessException(SeatErrorCode.RESERVATION_NOT_FOUND);
		}
		AdminActor actor = new AdminActor(adminId, reason);
		return cancelConfirmed(owner.get(0), reservationId, actor);
	}

	/** @param admin null이면 사용자 취소(USER), 아니면 관리자 강제 취소(ADMIN) */
	private AdminCancelResponse cancelConfirmed(long memberId, long reservationId, AdminActor admin) {
		String actorType = admin == null ? "USER" : "ADMIN";
		Done done = tx.execute(st -> {
			List<Timestamp> locked = jdbc.queryForList("""
					SELECT confirmed_at FROM reservation
					 WHERE reservation_id = ? AND member_id = ? AND status = 'CONFIRMED' FOR UPDATE""",
					Timestamp.class, reservationId, memberId);
			if (locked.isEmpty()) {
				// 그 사이 취소·환불됐거나 애초에 확정이 아님 (없는·남의 예약은 cancel()에서 이미 걸러짐)
				throw new BusinessException(ReservationErrorCode.RESERVATION_NOT_CONFIRMED);
			}
			Detail detail = queries.detail(memberId, reservationId);
			Quote q = admin == null ? toQuote(detail) : adminQuote(detail);
			Long policyId = admin == null ? detail.cancel().policyId() : null;

			int n = jdbc.update("""
					UPDATE reservation SET status = 'CANCELED', canceled_at = SYSTIMESTAMP, updated_at = SYSTIMESTAMP,
					       version = version + 1
					 WHERE reservation_id = ? AND status = 'CONFIRMED' AND member_id = ?""", reservationId, memberId);
			if (n != 1) {
				throw new IllegalStateException("잠근 확정 예약을 취소하지 못함: " + reservationId);
			}
			ReturnedSeats returned = seats.returnSoldSeatsInCurrentTx(reservationId);

			Instant now = clock.instant();
			long actionId = admin == null ? 0 : adminAction(admin, memberId, reservationId, now);
			Timestamp confirmedAt = locked.get(0);
			long minutesAfterPurchase = confirmedAt == null ? 0
					: Math.max(0, Duration.between(confirmedAt.toInstant(), now).toMinutes());
			long cancelId = jdbc.queryForObject("SELECT cancellation_seq.NEXTVAL FROM dual", Long.class);
			jdbc.update("""
					INSERT INTO cancellation (cancel_id, reservation_id, cancel_type, canceled_at, minutes_after_purchase,
					  minutes_before_departure, refund_amount, cancel_fee, fee_policy_id, refund_status, resold_yn)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', 'N')""",
					cancelId, reservationId, actorType, Timestamp.from(now), minutesAfterPurchase,
					q.minutesBeforeDeparture(), q.refund(), q.fee(), policyId);

			Map<String, Object> payload = returned != null ? returned.eventPayload()
					: new LinkedHashMap<>(Map.of("reservationId", (Object) reservationId));
			payload.put("reason", actorType);
			payload.put("cancelFee", q.fee());
			payload.put("refundAmount", q.refund());
			payload.put("minutesAfterPurchase", minutesAfterPurchase);
			payload.put("minutesBeforeDeparture", q.minutesBeforeDeparture());
			String eventId = outbox.recordReservation(reservationId, EventType.RESERVATION_CANCELED, payload);
			jdbc.update("""
					INSERT INTO reservation_history (history_id, reservation_id, from_status, to_status, event_id, actor_type, changed_at)
					VALUES (reservation_history_seq.NEXTVAL, ?, 'CONFIRMED', 'CANCELED', ?, ?, SYSTIMESTAMP)""",
					reservationId, eventId, actorType);

			List<Map<String, Object>> pay = jdbc.queryForList("""
					SELECT payment_id, pg_tx_id FROM payment WHERE reservation_id = ? AND status = 'APPROVED'""",
					reservationId);
			long paymentId = pay.isEmpty() ? 0 : ((Number) pay.get(0).get("PAYMENT_ID")).longValue();
			String pgTxId = pay.isEmpty() ? null : (String) pay.get(0).get("PG_TX_ID");
			return new Done(cancelId, paymentId, pgTxId, q.refund(),
					returned == null ? 0 : returned.runId(), returned == null ? 0 : returned.seatCount(), q.fee(),
					actionId);
		});

		String refundStatus = refund(reservationId, done);
		return new AdminCancelResponse(new CancelResponse(reservationId, "CANCELED", "CONFIRMED",
				done.runId() == 0 ? null : done.runId(), done.seatCount(), done.fee(), done.refund(), refundStatus),
				done.actionId());
	}

	/**
	 * CNL-03 환불 재시도: 사용자·관리자 취소 한 건의 환불을 다시 요청한다. 처음 환불과 같은 {@link #refund}를 쓰므로
	 * 환불 키({@code cancel:<cancel_id>})가 같아 PG에서 한 번만 환불되고, DONE으로 바꾼 쪽만 REFUND_COMPLETED를 남긴다.
	 *
	 * @return 이번 결과 (DONE / FAILED). 이미 DONE이거나 없는 건이면 그 상태 또는 null
	 */
	public String retryRefund(long cancelId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT c.reservation_id, c.refund_amount, c.cancel_fee, c.refund_status, p.payment_id, p.pg_tx_id
				  FROM cancellation c
				  LEFT JOIN payment p ON p.reservation_id = c.reservation_id AND p.status = 'APPROVED'
				 WHERE c.cancel_id = ? AND c.cancel_type IN ('USER', 'ADMIN')""", cancelId);
		if (rows.isEmpty()) {
			return null;
		}
		Map<String, Object> c = rows.get(0);
		if ("DONE".equals(c.get("REFUND_STATUS"))) {
			return "DONE";
		}
		long reservationId = ((Number) c.get("RESERVATION_ID")).longValue();
		Number paymentId = (Number) c.get("PAYMENT_ID");
		Done d = new Done(cancelId, paymentId == null ? 0 : paymentId.longValue(), (String) c.get("PG_TX_ID"),
				((Number) c.get("REFUND_AMOUNT")).longValue(), 0, 0, ((Number) c.get("CANCEL_FEE")).longValue(), 0);
		return refund(reservationId, d);
	}

	/** §8.1-4 ADMIN: 운영 사유 취소라 수수료 0, 결제 금액 전액. 출발 뒤여도 된다 (사용자 취소 규칙과 무관) */
	private static Quote adminQuote(Detail d) {
		long paid = d.payment() != null && "APPROVED".equals(d.payment().status()) ? d.payment().amount()
				: d.summary().totalAmount();
		Instant departure = d.summary().from().time();
		Long before = d.cancel() == null ? null : d.cancel().minutesBeforeDeparture();
		return new Quote(d.summary().reservationId(), paid, departure, before == null ? 0 : before, BigDecimal.ZERO, 0,
				paid);
	}

	/**
	 * 강제 취소 기록. 예약 대상 조치는 그 예약 회원의 CI를 subject로, reservation_id를 채운다 (ERD admin_action).
	 * 취소와 같은 트랜잭션이라 기록 없이 취소만 되는 일은 없다.
	 */
	private long adminAction(AdminActor admin, long memberId, long reservationId, Instant at) {
		String ciHash = jdbc.queryForObject("SELECT ci_hash FROM member WHERE member_id = ?", String.class, memberId);
		long id = jdbc.queryForObject("SELECT admin_action_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO admin_action (action_id, admin_id, subject_type, subject_hash, action_type, memo, reservation_id, acted_at)
				VALUES (?, ?, 'CI', ?, 'CANCEL_RESERVATION', ?, ?, ?)""",
				id, admin.adminId(), ciHash, admin.memo(), reservationId, Timestamp.from(at));
		return id;
	}

	/**
	 * 커밋 뒤 PG 환불 (§8.2). 실패해도 취소는 이미 커밋됐으므로 예외를 던지지 않고 FAILED로 남긴다
	 * (환불 재시도 배치 CNL-03의 대상, 1차는 로그만).
	 */
	String refund(long reservationId, Done d) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("환불은 트랜잭션 밖에서 (§0.6)");
		}
		RefundResult r;
		if (d.refund() == 0) {
			r = new RefundResult(true, null, null); // 돌려줄 돈이 없음 (수수료 100%)
		} else if (d.paymentId() == 0) {
			r = new RefundResult(false, null, "NO_APPROVED_PAYMENT");
		} else {
			try {
				r = pg.refund(d.paymentId(), d.pgTxId(), d.refund(), "cancel:" + d.cancelId());
			} catch (RuntimeException e) {
				log.warn("PG 환불 요청 실패 (취소 {}), 재시도 대상으로 남김: {}", d.cancelId(), e.toString());
				r = new RefundResult(false, null, "PG_ERROR");
			}
		}
		RefundResult result = r;
		tx.executeWithoutResult(st -> {
			if (result.done()) {
				int changed = jdbc.update("""
						UPDATE cancellation SET refund_status = 'DONE', refund_pg_tx_id = ?, updated_at = SYSTIMESTAMP
						 WHERE cancel_id = ? AND refund_status <> 'DONE'""", result.refundTxId(), d.cancelId());
				jdbc.update("""
						UPDATE payment SET status = 'REFUNDED', updated_at = SYSTIMESTAMP
						 WHERE reservation_id = ? AND status = 'APPROVED'""", reservationId);
				// 환불 완료 이벤트 (CNL-03, 기능설계서 8장) → 알림(REFUNDED). DONE으로 바꾼 쪽만 남겨 한 번만 나간다.
				// 돌려준 돈이 없으면(수수료 100%) 환불이 아니므로 남기지 않는다 (길동현 추가)
				if (changed == 1 && d.refund() > 0) {
					Map<String, Object> payload = new LinkedHashMap<>();
					payload.put("reservationId", reservationId);
					payload.put("cancelId", d.cancelId());
					payload.put("refundAmount", d.refund());
					payload.put("refundPgTxId", result.refundTxId());
					outbox.recordReservation(reservationId, EventType.REFUND_COMPLETED, payload);
				}
			} else {
				jdbc.update("""
						UPDATE cancellation SET refund_status = 'FAILED', updated_at = SYSTIMESTAMP
						 WHERE cancel_id = ? AND refund_status = 'REQUESTED'""", d.cancelId());
				log.error("환불 실패 (취소 {}, 사유 {}) → refund_status FAILED", d.cancelId(), result.reason());
			}
		});
		return result.done() ? "DONE" : "FAILED";
	}

}
