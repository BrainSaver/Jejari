package com.jejari.payment;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jejari.common.error.BusinessException;
import com.jejari.event.EventType;
import com.jejari.event.outbox.OutboxRecorder;
import com.jejari.seat.SeatErrorCode;
import com.jejari.seat.SeatPaymentService;

/**
 * PAY-01 결제 시작의 DB 단계 (concurrency.md §4.1). 호출하는 쪽이 연 트랜잭션 안에서 실행합니다.
 *
 * <p>첫 문장이 reservation의 조건부 UPDATE(HOLD → PAYMENT_PENDING)라 그 예약의 행 락을 먼저 잡습니다 (§0.4-1).
 * HOLD에서 PAYMENT_PENDING으로는 한 번만 바뀌므로 <b>결제 시작·마감 연장도 예약당 한 번만</b> 일어납니다.
 * 같은 예약에 결제 요청이 동시에 여러 개 와도 하나만 1행이 되고 나머지는 0행이라 결제 행이 하나만 생깁니다.
 *
 * <p>payment_id는 reservation_id와 같은 값을 씁니다. 결제 시작이 예약당 한 번(기능설계서 PAY-01, 재시도 없음)이라
 * 결제와 예약이 1:1이고, ERD에 payment 시퀀스가 아직 없기 때문입니다.
 */
@Component
class PaymentStartTx {

	private static final Logger log = LoggerFactory.getLogger(PaymentStartTx.class);

	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	record Command(long memberId, long reservationId, String idempotencyKey, String methodHash, String cardLast4,
			LocalDateTime now) {
	}

	record Started(long paymentId, long amount, LocalDateTime deadline) {
	}

	private final JdbcTemplate jdbc;
	private final SeatPaymentService seats;
	private final OutboxRecorder outbox;
	private final PaymentPolicy policy;

	PaymentStartTx(JdbcTemplate jdbc, SeatPaymentService seats, OutboxRecorder outbox, PaymentPolicy policy) {
		this.jdbc = jdbc;
		this.seats = seats;
		this.outbox = outbox;
		this.policy = policy;
	}

	/**
	 * @return 시작한 결제. 같은 멱등 키로 이미 시작된 결제가 있으면 null (호출한 쪽이 그 결제를 돌려줌, §1-2)
	 * @throws BusinessException 시작할 수 없음 (트랜잭션은 롤백됨)
	 */
	Started start(Command c) {
		long r = c.reservationId();
		// 1. 예약 전이 + 마감 연장. 늘리기만 하고 줄이지 않는다 (§4.1-1). 후보 마감은 앱 시계로 계산 (§0.7)
		LocalDateTime cand = c.now().plusSeconds(policy.getLong(PaymentPolicy.PG_TIMEOUT_SEC)
				+ policy.getLong(PaymentPolicy.EXTEND_GRACE_SEC));
		int updated = jdbc.update("""
				UPDATE reservation
				   SET status = 'PAYMENT_PENDING',
				       pay_deadline_at = GREATEST(pay_deadline_at, ?),
				       updated_at = SYSTIMESTAMP, version = version + 1
				 WHERE reservation_id = ? AND member_id = ? AND status = 'HOLD' AND pay_deadline_at > SYSTIMESTAMP""",
				cand, r, c.memberId());
		if (updated != 1) {
			return whyNot(c);
		}

		// 2. 새 마감 :d (행 락을 쥐고 있으므로 그대로 읽음)
		record Rv(LocalDateTime deadline, long amount, String source) {
		}
		Rv rv = jdbc.queryForObject("SELECT pay_deadline_at, total_amount, source FROM reservation WHERE reservation_id = ?",
				(rs, i) -> new Rv(rs.getObject(1, LocalDateTime.class), rs.getLong(2), rs.getString(3)), r);

		// 3. 좌석 소유 재확인 + 마감 복사 (§0.2). K행이 아니면 좌석을 이미 잃음 → 롤백
		if (!seats.extendHoldInCurrentTx(r, rv.deadline())) {
			throw new BusinessException(PaymentErrorCode.SEAT_LOST);
		}

		// 4. Saga: SEAT_HELD → PAYING, 제한 시각도 같은 마감으로 (§0.2)
		jdbc.update("""
				UPDATE saga_instance SET current_step = 'PAYING', timeout_at = ?, updated_at = SYSTIMESTAMP
				 WHERE reservation_id = ? AND status = 'RUNNING'""", rv.deadline(), r);

		// 5. 취소표 제안 예약이면 제안 기한도 함께 연장
		if ("WAITLIST".equals(rv.source())) {
			jdbc.update("""
					UPDATE waitlist SET offer_deadline_at = ?, updated_at = SYSTIMESTAMP
					 WHERE offered_reservation_id = ? AND status = 'OFFERED'""", rv.deadline(), r);
		}

		// 6. 결제 수단(AUTH-07 1차: 해시만 저장)과 결제 행. 새 카드면 명의 상한부터 (§4.3, 걸리면 전체 롤백 → 예약은 HOLD).
		//    next_check_at = 마감 → 그때까지 결과가 없으면 §6.2가 PG에 조회
		long methodId = methodId(c.memberId(), c.methodHash(), c.cardLast4());
		jdbc.update("""
				INSERT INTO payment (payment_id, reservation_id, method_id, amount, status, idempotency_key,
				                     requested_at, next_check_at)
				VALUES (?, ?, ?, ?, 'REQUESTED', ?, ?, ?)""",
				r, r, methodId, rv.amount(), c.idempotencyKey(), c.now(), rv.deadline());

		// 7. 이벤트(reservation UPDATE 뒤, §0.5)와 이력
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("reservationId", r);
		payload.put("paymentId", r);
		payload.put("methodHash", c.methodHash());
		payload.put("amount", rv.amount());
		payload.put("payDeadlineAt", rv.deadline().atZone(ZONE).toOffsetDateTime().toString());
		String eventId = outbox.recordReservation(r, EventType.PAYMENT_STARTED, payload);
		PaymentSaga.history(jdbc, r, "HOLD", "PAYMENT_PENDING", eventId, "USER");
		return new Started(r, rv.amount(), rv.deadline());
	}

	/** 0행인 이유를 가려 거절한다. 같은 키로 이미 시작된 결제면 null */
	private Started whyNot(Command c) {
		List<String> status = jdbc.queryForList("SELECT status FROM reservation WHERE reservation_id = ? AND member_id = ?",
				String.class, c.reservationId(), c.memberId());
		if (status.isEmpty()) {
			throw new BusinessException(SeatErrorCode.RESERVATION_NOT_FOUND); // 남의 예약도 없는 예약과 같게
		}
		Long same = jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE reservation_id = ? AND idempotency_key = ?",
				Long.class, c.reservationId(), c.idempotencyKey());
		if (same != null && same > 0) {
			return null; // 멱등 키 TTL이 지난 뒤 같은 키로 다시 옴 → 기존 결제를 돌려줌
		}
		throw switch (status.get(0)) {
			case "PAYMENT_PENDING", "CONFIRMED" -> new BusinessException(PaymentErrorCode.PAYMENT_DUPLICATED);
			case "HOLD", "EXPIRED" -> new BusinessException(SeatErrorCode.RESERVATION_EXPIRED); // HOLD면 마감이 지난 것
			default -> new BusinessException(SeatErrorCode.RESERVATION_NOT_HOLD);
		};
	}

	/** 시퀀스 번호가 기존 행과 겹칠 때 다음 번호로 넘어가는 최대 횟수 */
	static final int METHOD_ID_RETRIES = 50;

	/**
	 * 같은 회원의 같은 카드는 한 행으로 씁니다. method_id는 {@code payment_method_seq}에서 받습니다 (ERD v19).
	 *
	 * <p>v19 전에는 reservation_seq에서 받았으므로, 이미 행이 있는 로컬 DB에서는 새 시퀀스 번호가 옛 행과 겹칠 수 있습니다.
	 * 겹치면 다음 번호로 넘어갑니다. 이 PK 충돌이 밖으로 나가면 {@code PaymentService}가 "같은 멱등 키 동시 요청"으로
	 * 잘못 읽기 때문입니다 (Oracle은 실패한 문장만 되돌리므로 트랜잭션은 그대로 이어 갑니다).
	 */
	private long methodId(long memberId, String methodHash, String last4) {
		List<Long> found = methodHash != null
				? jdbc.queryForList("SELECT method_id FROM payment_method WHERE member_id = ? AND method_hash = ? ORDER BY method_id",
						Long.class, memberId, methodHash)
				: jdbc.queryForList("""
						SELECT method_id FROM payment_method WHERE member_id = ? AND method_hash IS NULL AND card_last4 = ?
						 ORDER BY method_id""", Long.class, memberId, last4);
		if (!found.isEmpty()) {
			return found.get(0);
		}
		if (methodHash != null) {
			checkCardLink(memberId, methodHash); // 새 카드를 붙이는 때만 (같은 회원의 같은 카드는 위에서 이미 돌아감)
		}
		for (int i = 0; i < METHOD_ID_RETRIES; i++) {
			long id = jdbc.queryForObject("SELECT payment_method_seq.NEXTVAL FROM dual", Long.class);
			try {
				jdbc.update("""
						INSERT INTO payment_method (method_id, member_id, method_hash, method_type, card_last4)
						VALUES (?, ?, ?, 'CARD', ?)""", id, memberId, methodHash, last4);
				return id;
			} catch (DuplicateKeyException e) {
				log.info("결제 수단 번호 {}가 기존 행과 겹쳐 다음 번호로 넘어감", id);
			}
		}
		throw new IllegalStateException("결제 수단 번호를 " + METHOD_ID_RETRIES + "번 연속 기존 행과 겹쳐 받지 못함");
	}

	/**
	 * 이 카드를 이 회원에게 새로 붙여도 되는지 본다 — 카드 한 장에 붙는 서로 다른 명의 수 상한 (도용 카드, concurrency.md §4.3).
	 *
	 * <p><b>센 다음 넣기만 하면 동시 요청에 뚫린다.</b> 새로 훔친 카드로 계정 여러 개가 동시에 결제를 시작하면 모두
	 * "0명"으로 세고 통과한다. 아직 payment_method 행이 없는 카드라 그 행을 {@code FOR UPDATE}로 잠글 수도 없다.
	 * 그래서 카드 해시마다 한 행짜리 card_lock을 만들고 잠근 <b>뒤에</b> 센다. 같은 카드를 붙이는 트랜잭션은 그 행에서
	 * 줄을 서고, 앞 트랜잭션이 커밋한 행까지 보고 센다. 잠금은 트랜잭션 맨 나중의 한 행이라 데드락이 없다 (§0.4-8).
	 *
	 * <p>개수를 card_lock에 따로 두지 않고 매번 원천에서 센다. payment_method를 이 경로 밖에서 넣는 곳
	 * (합성 데이터 생성기, 테스트)이 있어 따로 세면 어긋난다.
	 */
	private void checkCardLink(long memberId, String methodHash) {
		long max = policy.getLong(PaymentPolicy.MAX_CI_PER_CARD);
		if (max <= 0) {
			return; // 끔 (기본). 잠금도 만들지 않아 결제 시작에 비용이 없다
		}
		try {
			jdbc.update("""
					MERGE INTO card_lock t USING (SELECT ? AS method_hash FROM dual) s
					   ON (t.method_hash = s.method_hash)
					 WHEN NOT MATCHED THEN INSERT (method_hash) VALUES (s.method_hash)""", methodHash);
		} catch (DuplicateKeyException e) {
			// 처음 보는 카드를 다른 트랜잭션이 먼저 만들었다. 이 문장만 되돌아가고 행은 이미 있다 (Oracle은 트랜잭션을 이어 감)
		}
		jdbc.queryForObject("SELECT method_hash FROM card_lock WHERE method_hash = ? FOR UPDATE", String.class, methodHash);

		record Link(long linked, long mine) {
		}
		// 명의가 빈 계정은 회원 하나를 한 명으로 센다 — 명의 없는 계정으로 상한을 피할 수 없게
		Link l = jdbc.queryForObject("""
				SELECT COUNT(DISTINCT NVL(m.ci_hash, 'M' || m.member_id)),
				       COUNT(CASE WHEN m.ci_hash = (SELECT me.ci_hash FROM member me WHERE me.member_id = ?) THEN 1 END)
				  FROM payment_method pm JOIN member m ON m.member_id = pm.member_id
				 WHERE pm.method_hash = ?""",
				(rs, i) -> new Link(rs.getLong(1), rs.getLong(2)), memberId, methodHash);
		if (l.mine() == 0 && l.linked() >= max) {
			log.info("카드 명의 상한: 회원 #{} 거절 (이미 붙은 명의 {})", memberId, l.linked());
			throw new BusinessException(PaymentErrorCode.PAYMENT_CARD_LINK_LIMIT);
		}
	}
}
