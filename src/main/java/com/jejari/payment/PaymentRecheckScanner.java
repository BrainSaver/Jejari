package com.jejari.payment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import com.jejari.payment.pg.PgClient;
import com.jejari.payment.pg.PgClient.Inquiry;

/**
 * PAY-04 PG 결과 확인 스캐너 (concurrency.md §6.2). 마감이 지났는데 결과를 모르는 결제를 PG에 물어봅니다.
 *
 * <p>HOLD 만료(§6.1, 강동균 {@code HoldExpiryScanner})와 <b>쿼리를 나눠</b> 둡니다. 결과를 모르는 결제가 쌓여도
 * HOLD 만료가 밀리지 않게 하려는 것입니다 (T27). 조회식은 인덱스 {@code ix_pay_recheck}와 같은 CASE 식입니다.
 *
 * <pre>
 * PG 결과   승인 → Saga 확정(§5.1) · 실패 → Saga 보상(§5.2) · 없음 → 주문을 막고 실패로
 *           모름 → 자동으로 만료시키지 않음. 다음 조회를 미루고, 오래되면 관리자 알림(로그)
 * </pre>
 *
 * 1차 범위(기능설계서 1.1): 모름일 때 Redis 좌석 키 다시 걸기(rehold.lua)는 하지 않습니다.
 * 앱이 여러 대여도 Saga의 조건부 UPDATE로 한 번만 처리됩니다.
 */
public class PaymentRecheckScanner {

	private static final Logger log = LoggerFactory.getLogger(PaymentRecheckScanner.class);

	private static final String DUE = """
			SELECT payment_id, reservation_id, requested_at FROM payment
			 WHERE (CASE WHEN status = 'REQUESTED' THEN next_check_at END) < SYSTIMESTAMP
			 FETCH FIRST ? ROWS ONLY""";

	private record Due(long paymentId, long reservationId, LocalDateTime requestedAt) {
	}

	private final JdbcTemplate jdbc;
	private final PgClient pg;
	private final PaymentSaga saga;
	private final PaymentPolicy policy;
	private final Clock clock;

	public PaymentRecheckScanner(JdbcTemplate jdbc, PgClient pg, PaymentSaga saga, PaymentPolicy policy, Clock clock) {
		this.jdbc = jdbc;
		this.pg = pg;
		this.saga = saga;
		this.policy = policy;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${jejari.payment.recheck-interval-ms:5000}", initialDelay = 5000)
	void tick() {
		try {
			scanOnce();
		} catch (RuntimeException e) {
			log.warn("PG 결과 확인 스캔 실패 (다음 주기에 다시)", e);
		}
	}

	/** @return 확정·실패로 정리한 건수 (모름으로 미룬 건은 세지 않음) */
	public int scanOnce() {
		List<Due> due = jdbc.query(DUE, (rs, i) -> new Due(rs.getLong(1), rs.getLong(2),
				rs.getObject(3, LocalDateTime.class)), policy.getLong(PaymentPolicy.SCANNER_BATCH_SIZE));
		int settled = 0;
		for (Due d : due) {
			try {
				if (check(d)) {
					settled++;
				}
			} catch (RuntimeException e) {
				// 한 건 실패가 나머지를 막지 않게. 이 결제는 다음 주기에 다시 후보로 나온다
				log.warn("결제 {} 결과 확인 실패", d.paymentId(), e);
			}
		}
		if (!due.isEmpty()) {
			log.info("PG 결과 확인: 후보 {}건 중 {}건 정리", due.size(), settled);
		}
		return settled;
	}

	private boolean check(Due d) {
		Inquiry q = pg.inquire(d.paymentId()); // 트랜잭션 밖 (§0.6)
		switch (q.status()) {
			case APPROVED -> {
				saga.approve(d.reservationId(), d.paymentId(), q.pgTxId());
				return true;
			}
			case FAILED -> {
				return saga.fail(d.reservationId(), d.paymentId(), q.reason() == null ? "PG_FAILED" : q.reason());
			}
			case NOT_FOUND -> {
				// PG가 요청을 받은 적 없음: 늦게 도착할 승인을 먼저 막고, 막힌 게 확인될 때만 실패로
				if (pg.blockOrder(d.paymentId())) {
					return saga.fail(d.reservationId(), d.paymentId(), "PG_NOT_RECEIVED");
				}
				postpone(d);
				return false;
			}
			default -> {
				postpone(d);
				return false;
			}
		}
	}

	/** 모름: 다음 조회 시각을 미룬다 (이 행은 그때까지 후보에서 빠짐). 오래되면 관리자 확인 대상 */
	private void postpone(Due d) {
		LocalDateTime now = LocalDateTime.now(clock);
		jdbc.update("""
				UPDATE payment SET next_check_at = ?, updated_at = SYSTIMESTAMP
				 WHERE payment_id = ? AND status = 'REQUESTED'""",
				now.plusSeconds(policy.getLong(PaymentPolicy.PG_RECHECK_SEC)), d.paymentId());
		if (d.requestedAt() != null
				&& d.requestedAt().plusMinutes(policy.getLong(PaymentPolicy.PG_UNKNOWN_MAX_MIN)).isBefore(now)) {
			log.error("결제 {} (예약 {}) PG 결과를 {}분 넘게 모름. 관리자 확인 필요 (ADM-09)", d.paymentId(),
					d.reservationId(), policy.getLong(PaymentPolicy.PG_UNKNOWN_MAX_MIN));
		}
	}
}
