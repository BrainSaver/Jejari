package com.jejari.payment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.payment.PaymentStartTx.Started;
import com.jejari.payment.pg.PgClient;
import com.jejari.payment.pg.PgClient.ApproveResult;
import com.jejari.payment.pg.PgClient.Outcome;
import com.jejari.seat.SeatErrorCode;
import com.jejari.seat.SeatReleaseService;

/**
 * PAY-01 결제 시작 + EVT-04 결제 요청 멱등 (concurrency.md §1, §4).
 *
 * <pre>
 * 멱등 키 SET NX (idem:pay:{R}:{key})
 *   ├─ 이미 있음 → 그 키의 결제 상태를 돌려줌 (처리 중이면 202)
 *   └─ 처음 → DB 트랜잭션 §4.1 (PaymentStartTx) → 커밋 → PG 승인 요청(트랜잭션 밖, §0.6)
 *              ├─ 승인 → Saga 확정 §5.1      ├─ 거절 → Saga 보상 §5.2
 *              └─ 응답 없음 → 그대로 둠 (마감이 지나면 §6.2 스캐너가 PG에 물어봄)
 * 거절로 끝나면 멱등 키를 지워, 같은 키로 다시 보내면 같은 판정을 다시 받게 한다 (§1-3)
 * </pre>
 *
 * <p>중복 결제를 막는 장치는 세 겹입니다: Redis 멱등 키(빠른 1차) → reservation HOLD→PAYMENT_PENDING 조건부 UPDATE
 * (예약당 한 번) → DB {@code UNIQUE(reservation_id, idempotency_key)}·예약당 승인 1건 인덱스 {@code ux_payment_approved}.
 */
@Service
public class PaymentService {

	private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

	/** 값이 내 예약 ID일 때만 지운다 (다른 요청이 새로 잡은 키를 지우지 않게) */
	private static final RedisScript<Long> DELETE_IF_MINE = RedisScript.of(
			"if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0", Long.class);

	public record PaymentResult(long paymentId, long reservationId, String paymentStatus, String reservationStatus,
			LocalDateTime payDeadlineAt, boolean replay) {
	}

	private final PaymentStartTx startTx;
	private final PaymentSaga saga;
	private final PgClient pg;
	private final PaymentPolicy policy;
	private final StringRedisTemplate redis;
	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final ObjectProvider<Pseudonymizer> hmac;
	private final Clock clock;
	private final PaymentRiskGuard riskGuard;
	private final SeatReleaseService seatRelease;

	public PaymentService(PaymentStartTx startTx, PaymentSaga saga, PgClient pg, PaymentPolicy policy,
			StringRedisTemplate redis, JdbcTemplate jdbc, TransactionTemplate tx, ObjectProvider<Pseudonymizer> hmac,
			Clock clock, PaymentRiskGuard riskGuard, SeatReleaseService seatRelease) {
		this.startTx = startTx;
		this.saga = saga;
		this.pg = pg;
		this.policy = policy;
		this.redis = redis;
		this.jdbc = jdbc;
		this.tx = tx;
		this.hmac = hmac;
		this.clock = clock;
		this.riskGuard = riskGuard;
		this.seatRelease = seatRelease;
	}

	public static String idemKey(long reservationId, String key) {
		return "idem:pay:" + reservationId + ":" + key;
	}

	public PaymentResult start(long memberId, long reservationId, String idempotencyKey, String cardNumber) {
		if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "Idempotency-Key 헤더가 필요합니다 (64자 이하).");
		}
		if (cardNumber == null || !cardNumber.matches("\\d{12,19}")) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "카드번호는 숫자 12~19자리입니다.");
		}

		// 1. 멱등 1차 (Redis). 값은 예약 ID = 결제 ID
		String idem = idemKey(reservationId, idempotencyKey);
		String mine = Long.toString(reservationId);
		Boolean first = redis.opsForValue().setIfAbsent(idem, mine, policy.getLong(PaymentPolicy.IDEM_TTL_SEC), TimeUnit.SECONDS);
		if (!Boolean.TRUE.equals(first)) {
			return replay(memberId, reservationId, idempotencyKey);
		}

		// 1-1. 결제 단계 차단 (§7.5, PaymentRiskGuard): 위험 점수가 케이스 기준 이상이면 PG 전에 막고 선점 좌석을 바로 돌려준다.
		//      처음 요청에서만 본다 (위 재요청 = 이미 결제 중이면 상태만 돌려줌). 점수를 모르면 통과
		PaymentRiskGuard.Verdict risk = riskGuard.check(memberId);
		if (risk.blocked()) {
			deleteIdem(idem, mine);
			boolean released = seatRelease.releaseForRiskBlock(memberId, reservationId);
			log.info("결제 단계 차단 member={} reservation={} 점수 {} ≥ 기준 {} → 좌석 {}", memberId, reservationId,
					risk.score(), risk.threshold(), released ? "해제" : "해제할 HOLD 없음");
			throw new BusinessException(PaymentErrorCode.PAYMENT_RISK_BLOCKED);
		}

		// 2. DB 트랜잭션 (§4.1). 카드번호는 해시와 끝 4자리만 남긴다
		Pseudonymizer h = hmac.getIfAvailable();
		if (h == null) {
			log.warn("JEJARI_HMAC_KEY가 없어 결제 수단 해시 없이 저장합니다 (위험 점수의 결제 수단 묶음이 빠짐)");
		}
		var cmd = new PaymentStartTx.Command(memberId, reservationId, idempotencyKey,
				h == null ? null : h.paymentMethod(cardNumber), cardNumber.substring(cardNumber.length() - 4),
				LocalDateTime.now(clock));
		Started started;
		try {
			started = tx.execute(st -> startTx.start(cmd));
		} catch (DuplicateKeyException e) {
			// UNIQUE(reservation_id, idempotency_key): 같은 키가 동시에 DB까지 옴 → 먼저 된 쪽 결과를 돌려줌
			return replay(memberId, reservationId, idempotencyKey);
		} catch (RuntimeException e) {
			deleteIdem(idem, mine); // 거절·오류: 같은 키로 다시 보내면 같은 판정을 다시 받게 (§1-3)
			throw e;
		}
		if (started == null) {
			// 멱등 키 TTL이 지난 뒤 같은 키로 다시 옴 (§1-2). 방금 잡은 키는 그대로 둬도 값이 같다
			return replay(memberId, reservationId, idempotencyKey);
		}

		// 3. 커밋 후 PG 승인 요청 (트랜잭션 밖)
		ApproveResult res;
		try {
			res = pg.approve(started.paymentId(), started.amount(), cardNumber);
		} catch (RuntimeException e) {
			log.warn("결제 {} PG 승인 요청 오류, 결과 모름으로 둠 (§6.2 스캐너가 조회): {}", started.paymentId(), e.toString());
			res = new ApproveResult(Outcome.UNKNOWN, null, "PG_ERROR");
		}
		switch (res.outcome()) {
			case APPROVED -> saga.approve(reservationId, started.paymentId(), res.pgTxId());
			case DECLINED -> saga.fail(reservationId, started.paymentId(), res.reason());
			case UNKNOWN -> log.info("결제 {} PG 응답 없음, 마감 {}에 스캐너가 조회", started.paymentId(), started.deadline());
		}
		return current(memberId, reservationId, idempotencyKey, false);
	}

	/** 같은 멱등 키 재요청: 그 키의 결제 상태. DB에 아직 없으면 처리 중(202) */
	private PaymentResult replay(long memberId, long reservationId, String key) {
		return current(memberId, reservationId, key, true);
	}

	private PaymentResult current(long memberId, long reservationId, String key, boolean replay) {
		List<PaymentResult> rows = jdbc.query("""
				SELECT p.payment_id, p.status, r.status, r.pay_deadline_at
				  FROM payment p JOIN reservation r ON r.reservation_id = p.reservation_id
				 WHERE p.reservation_id = ? AND p.idempotency_key = ? AND r.member_id = ?""",
				(rs, i) -> new PaymentResult(rs.getLong(1), reservationId, rs.getString(2), rs.getString(3),
						rs.getObject(4, LocalDateTime.class), replay),
				reservationId, key, memberId);
		if (rows.isEmpty()) {
			throw new BusinessException(SeatErrorCode.REQUEST_IN_PROGRESS);
		}
		return rows.get(0);
	}

	private void deleteIdem(String key, String value) {
		try {
			redis.execute(DELETE_IF_MINE, List.of(key), value);
		} catch (RuntimeException e) {
			log.warn("결제 멱등 키 정리 실패 (TTL로 사라짐): {}", e.toString());
		}
	}
}
