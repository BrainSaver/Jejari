package com.jejari.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.event.outbox.OutboxRecorder;

import com.jejari.common.error.BusinessException;
import com.jejari.payment.PaymentSaga.Approval;
import com.jejari.payment.PaymentService.PaymentResult;
import com.jejari.payment.pg.MockPgClient;
import com.jejari.payment.pg.PgClient;
import com.jejari.seat.SeatHoldService.HoldCommand;
import com.jejari.seat.SeatHoldService.SeatRequest;
import com.jejari.seat.SeatHoldService;
import com.jejari.seat.SeatKeys;
import com.jejari.support.NoRunningApp;

/**
 * PAY-01~04 + EVT-04 결제 멱등 + EVT-05 Saga를 실제 Oracle·Redis로 확인한다 (concurrency.md §1, §4~§6.2).
 * 좌석 선점은 강동균의 {@link SeatHoldService}로 실제로 만든다. PG는 {@link MockPgClient}(카드 끝자리 규칙).
 *
 * <p>준비: {@code docker compose up -d}, Oracle이 최신 DDL·시드여야 한다 (운행 9001, 열차 1의 좌석).
 */
@Tag("infra")
@ExtendWith(NoRunningApp.class)
@SpringBootTest(properties = {
		"spring.data.redis.database=15",
		"jejari.queue.scheduler-enabled=false",
		"jejari.risk.cache-resync-enabled=false",
		"jejari.seat.expiry-scanner-enabled=false",
		"jejari.payment.recheck-scanner-enabled=false",
		"jejari.event.relay.enabled=false"})
class PaymentInfraTest {

	static final long RUN = 9001;
	static final String FROM = "3900023";
	static final String TO = "3900114";
	static final long MEMBER_BASE = 970000;
	static final AtomicLong NEXT_MEMBER = new AtomicLong(MEMBER_BASE + 1);

	static final String OK = "4111111111111111";
	static final String DECLINE = "4111111111111110";
	static final String NO_RESPONSE = "4111111111111119";
	static final String DELAYED = "4111111111111118";

	@Autowired
	PaymentService payments;
	@Autowired
	PaymentSaga saga;
	@Autowired
	PaymentPolicy policy;
	@Autowired
	PgClient pgBean;
	@Autowired
	SeatHoldService hold;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	Clock clock;
	@Autowired
	LateApprovalRefund refunds;
	@Autowired
	TransactionTemplate tx;
	@Autowired
	OutboxRecorder outbox;

	MockPgClient pg;
	PaymentRecheckScanner scanner;
	List<Long> seatIds;
	int seatCursor;

	@BeforeEach
	void setUp() {
		cleanDb();
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		pg = (MockPgClient) pgBean;
		scanner = new PaymentRecheckScanner(jdbc, pg, saga, policy, clock);
		// 좌석 테스트(SeatHold·SeatRelease)와 겹치지 않게 뒤쪽 좌석을 쓴다
		seatIds = jdbc.queryForList("SELECT seat_id FROM seat WHERE train_id = 1 ORDER BY seat_id", Long.class);
		seatCursor = 0;
	}

	@AfterEach
	void tearDown() {
		cleanDb();
	}

	// ------------------------------------------------------------ 결제 수단 번호

	@Test
	void 결제_수단_번호는_payment_method_seq에서_받고_옛_행과_겹치면_다음_번호로_넘어간다() {
		// v19 전 reservation_seq로 만든 행이 마침 다음 시퀀스 번호들을 차지하고 있는 상황
		long owner = newMember();
		long next = jdbc.queryForObject("SELECT payment_method_seq.NEXTVAL FROM dual", Long.class);
		for (long id = next + 1; id <= next + 3; id++) {
			jdbc.update("""
					INSERT INTO payment_method (method_id, member_id, method_hash, method_type, card_last4)
					VALUES (?, ?, NULL, 'CARD', '0000')""", id, owner);
		}
		long m = newMember();
		long r = holdSeat(m, nextSeat());

		PaymentResult res = payments.start(m, r, key(), OK);

		// 겹친 PK가 "같은 키 동시 요청"으로 잘못 읽히지 않고 새 결제로 끝난다
		assertThat(res.replay()).isFalse();
		assertThat(res.reservationStatus()).isEqualTo("CONFIRMED");
		long methodId = jdbc.queryForObject("SELECT method_id FROM payment WHERE reservation_id = ?", Long.class, r);
		assertThat(methodId).isGreaterThan(next + 3);
		assertThat(jdbc.queryForObject("SELECT member_id FROM payment_method WHERE method_id = ?", Long.class, methodId))
				.isEqualTo(m);
	}

	// ------------------------------------------------------------ 정상 흐름

	@Test
	void 승인되면_예약_확정_좌석_SOLD_Saga_DONE_이벤트는_시작_다음_확정_순서() {
		long m = newMember();
		long s = nextSeat();
		long r = holdSeat(m, s);

		PaymentResult res = payments.start(m, r, key(), OK);

		assertThat(res.replay()).isFalse();
		assertThat(res.paymentStatus()).isEqualTo("APPROVED");
		assertThat(res.reservationStatus()).isEqualTo("CONFIRMED");
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ? AND status = 'SOLD'", r)).isEqualTo(1);
		assertThat(str("SELECT current_step || '/' || status FROM saga_instance WHERE reservation_id = ?", r))
				.isEqualTo("CONFIRMED/DONE");
		assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY seq", String.class,
				Long.toString(r))).containsExactly("SEAT_HELD", "PAYMENT_STARTED", "RESERVATION_CONFIRMED");
		assertThat(jdbc.queryForList("SELECT to_status FROM reservation_history WHERE reservation_id = ? ORDER BY history_id",
				String.class, r)).containsExactly("HOLD", "PAYMENT_PENDING", "CONFIRMED");
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s, 0))).isEqualTo("SOLD:" + r);
		// 카드번호 원문은 어디에도 없다 (끝 4자리만)
		assertThat(str("SELECT card_last4 FROM payment_method WHERE member_id = ?", m)).isEqualTo("1111");
	}

	@Test
	void 거절되면_예약_만료_좌석과_카운터_반환_Saga_보상() {
		long m = newMember();
		long s = nextSeat();
		long r = holdSeat(m, s);

		PaymentResult res = payments.start(m, r, key(), DECLINE);

		assertThat(res.paymentStatus()).isEqualTo("FAILED");
		assertThat(res.reservationStatus()).isEqualTo("EXPIRED");
		assertThat(str("SELECT fail_reason FROM payment WHERE payment_id = ?", r)).isEqualTo("CARD_DECLINED");
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ?", r)).isZero();
		assertThat(perRun(m)).isZero();
		assertThat(str("SELECT status FROM saga_instance WHERE reservation_id = ?", r)).isEqualTo("COMPENSATED");
		assertThat(count("""
				SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'RESERVATION_EXPIRED'
				   AND DBMS_LOB.INSTR(payload, 'PAY_FAILED') > 0""", Long.toString(r))).isEqualTo(1);
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s, 0))).isEqualTo("REOFFER:" + r); // 대기자 먼저
	}

	@Test
	void 결제_시작은_마감을_늘리고_좌석_Saga에_같은_값을_복사한다() {
		long m = newMember();
		long s = nextSeat();
		long r = holdSeat(m, s);
		LocalDateTime before = deadline(r);
		LocalDateTime now = LocalDateTime.now(clock);

		payments.start(m, r, key(), NO_RESPONSE); // 결과 모름이라 결제 중으로 남음

		LocalDateTime d = deadline(r);
		long ext = policy.getLong(PaymentPolicy.PG_TIMEOUT_SEC) + policy.getLong(PaymentPolicy.EXTEND_GRACE_SEC);
		assertThat(d).isAfterOrEqualTo(before);
		assertThat(d).isAfterOrEqualTo(now.plusSeconds(ext - 5));
		assertThat(jdbc.queryForObject("SELECT hold_expires_at FROM seat_inventory WHERE reservation_id = ?",
				LocalDateTime.class, r)).isEqualTo(d);
		assertThat(jdbc.queryForObject("SELECT timeout_at FROM saga_instance WHERE reservation_id = ?",
				LocalDateTime.class, r)).isEqualTo(d);
		assertThat(str("SELECT current_step FROM saga_instance WHERE reservation_id = ?", r)).isEqualTo("PAYING");
		assertThat(jdbc.queryForObject("SELECT next_check_at FROM payment WHERE payment_id = ?", LocalDateTime.class, r))
				.isEqualTo(d);
		Long ttl = redis.getExpire(SeatKeys.hold(RUN, s, 0));
		assertThat(ttl).isGreaterThan(ext - 10); // Redis 좌석 키도 같이 연장됨
	}

	// ------------------------------------------------------------ EVT-04 멱등

	@Test
	void 같은_멱등_키로_다시_보내면_같은_결제를_돌려주고_PG는_한_번만_간다() {
		long m = newMember();
		long r = holdSeat(m, nextSeat());
		String k = key();
		int pgBefore = pg.processedCount();

		PaymentResult first = payments.start(m, r, k, OK);
		PaymentResult again = payments.start(m, r, k, OK);

		assertThat(again.replay()).isTrue();
		assertThat(again.paymentId()).isEqualTo(first.paymentId());
		assertThat(again.reservationStatus()).isEqualTo("CONFIRMED");
		assertThat(pg.processedCount() - pgBefore).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM payment WHERE reservation_id = ?", r)).isEqualTo(1);
	}

	@Test
	void 멱등_키가_만료된_뒤_같은_키로_와도_DB가_막고_기존_결제를_돌려준다() {
		long m = newMember();
		long r = holdSeat(m, nextSeat());
		String k = key();
		payments.start(m, r, k, OK);
		redis.delete(PaymentService.idemKey(r, k)); // TTL 지남

		PaymentResult again = payments.start(m, r, k, OK);

		assertThat(again.replay()).isTrue();
		assertThat(again.paymentStatus()).isEqualTo("APPROVED");
		assertThat(count("SELECT COUNT(*) FROM payment WHERE reservation_id = ?", r)).isEqualTo(1);
	}

	@Test
	void 다른_키로_다시_결제하면_PAYMENT_DUPLICATED() {
		long m = newMember();
		long r = holdSeat(m, nextSeat());
		payments.start(m, r, key(), NO_RESPONSE);

		assertThatThrownBy(() -> payments.start(m, r, key(), OK))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode().code()).isEqualTo("PAYMENT_DUPLICATED");
		assertThat(count("SELECT COUNT(*) FROM payment WHERE reservation_id = ?", r)).isEqualTo(1);
	}

	@Test
	void 같은_예약에_다른_키로_20개가_동시에_와도_결제는_1건_PG도_1번() throws Exception {
		long m = newMember();
		long r = holdSeat(m, nextSeat());
		int pgBefore = pg.processedCount();

		List<Object> results = concurrently(20, i -> payments.start(m, r, key(), OK));

		long created = results.stream().filter(o -> o instanceof PaymentResult p && !p.replay()).count();
		long duplicated = results.stream().filter(o -> "PAYMENT_DUPLICATED".equals(o)).count();
		assertThat(created).isEqualTo(1);
		assertThat(duplicated).isEqualTo(19);
		assertThat(count("SELECT COUNT(*) FROM payment WHERE reservation_id = ?", r)).isEqualTo(1);
		assertThat(pg.processedCount() - pgBefore).isEqualTo(1); // 중복 결제 0건
		assertThat(str("SELECT status FROM reservation WHERE reservation_id = ?", r)).isEqualTo("CONFIRMED");
	}

	@Test
	void 같은_키로_20개가_동시에_와도_결제는_1건_나머지는_재응답이나_처리_중() throws Exception {
		long m = newMember();
		long r = holdSeat(m, nextSeat());
		String k = key();
		int pgBefore = pg.processedCount();

		List<Object> results = concurrently(20, i -> payments.start(m, r, k, OK));

		long created = results.stream().filter(o -> o instanceof PaymentResult p && !p.replay()).count();
		long others = results.stream()
				.filter(o -> (o instanceof PaymentResult p && p.replay()) || "REQUEST_IN_PROGRESS".equals(o)).count();
		assertThat(created).isEqualTo(1);
		assertThat(others).isEqualTo(19);
		assertThat(count("SELECT COUNT(*) FROM payment WHERE reservation_id = ?", r)).isEqualTo(1);
		assertThat(pg.processedCount() - pgBefore).isEqualTo(1);
	}

	// ------------------------------------------------------------ 거절 (§4.1)

	@Test
	void 기한이_지난_HOLD는_결제할_수_없고_멱등_키도_남기지_않는다() {
		long m = newMember();
		long r = holdSeat(m, nextSeat());
		jdbc.update("UPDATE reservation SET pay_deadline_at = SYSTIMESTAMP - INTERVAL '1' MINUTE WHERE reservation_id = ?", r);
		String k = key();

		assertThat(code(() -> payments.start(m, r, k, OK))).isEqualTo("RESERVATION_EXPIRED");
		assertThat(redis.hasKey(PaymentService.idemKey(r, k))).isFalse(); // 같은 키로 다시 보내면 같은 판정 (§1-3)
		assertThat(count("SELECT COUNT(*) FROM payment WHERE reservation_id = ?", r)).isZero();
	}

	@Test
	void 남의_예약은_없는_예약과_같게_404() {
		long owner = newMember();
		long r = holdSeat(owner, nextSeat());

		assertThat(code(() -> payments.start(newMember(), r, key(), OK))).isEqualTo("RESERVATION_NOT_FOUND");
		assertThat(str("SELECT status FROM reservation WHERE reservation_id = ?", r)).isEqualTo("HOLD");
	}

	@Test
	void 좌석을_이미_잃었으면_SEAT_LOST이고_예약은_HOLD_그대로() {
		long m = newMember();
		long r = holdSeat(m, nextSeat());
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id = ?", r);

		assertThat(code(() -> payments.start(m, r, key(), OK))).isEqualTo("SEAT_LOST");
		assertThat(str("SELECT status FROM reservation WHERE reservation_id = ?", r)).isEqualTo("HOLD"); // 롤백됨
		assertThat(count("SELECT COUNT(*) FROM payment WHERE reservation_id = ?", r)).isZero();
		assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'PAYMENT_STARTED'",
				Long.toString(r))).isZero();
	}

	// ------------------------------------------------------------ PAY-04 스캐너

	@Test
	void 결과를_모르면_스캐너는_만료시키지_않고_다음_조회로_미룬다() {
		long m = newMember();
		long r = holdSeat(m, nextSeat());
		payments.start(m, r, key(), NO_RESPONSE);
		dueNow(r);

		assertThat(scanner.scanOnce()).isZero();

		assertThat(str("SELECT status FROM payment WHERE payment_id = ?", r)).isEqualTo("REQUESTED");
		assertThat(str("SELECT status FROM reservation WHERE reservation_id = ?", r)).isEqualTo("PAYMENT_PENDING");
		assertThat(count("SELECT COUNT(*) FROM payment WHERE payment_id = ? AND next_check_at > SYSTIMESTAMP", r)).isEqualTo(1);
		assertThat(scanner.scanOnce()).isZero(); // 미룬 동안은 후보에서 빠짐
	}

	@Test
	void 늦게_승인된_결제는_스캐너가_PG에_물어_확정한다() {
		long m = newMember();
		long r = holdSeat(m, nextSeat());
		PaymentResult res = payments.start(m, r, key(), DELAYED);
		assertThat(res.reservationStatus()).isEqualTo("PAYMENT_PENDING");
		dueNow(r);

		assertThat(scanner.scanOnce()).isEqualTo(1);

		assertThat(str("SELECT status FROM reservation WHERE reservation_id = ?", r)).isEqualTo("CONFIRMED");
		assertThat(str("SELECT status FROM payment WHERE payment_id = ?", r)).isEqualTo("APPROVED");
	}

	@Test
	void PG가_받은_적_없는_결제는_주문을_막고_실패로_정리한다() {
		long m = newMember();
		long r = holdSeat(m, nextSeat());
		payments.start(m, r, key(), NO_RESPONSE);
		dueNow(r);
		MockPgClient restarted = new MockPgClient(); // PG가 주문을 모름 (요청이 도착하지 않은 경우)

		int settled = new PaymentRecheckScanner(jdbc, restarted, saga, policy, clock).scanOnce();

		assertThat(settled).isEqualTo(1);
		assertThat(str("SELECT fail_reason FROM payment WHERE payment_id = ?", r)).isEqualTo("PG_NOT_RECEIVED");
		assertThat(str("SELECT status FROM reservation WHERE reservation_id = ?", r)).isEqualTo("EXPIRED");
		assertThat(perRun(m)).isZero();
	}

	// ------------------------------------------------------------ EVT-05 Saga 경합·예외 경로

	@Test
	void 승인을_두_번_받아도_확정은_한_번_확정_뒤_실패가_와도_무시() {
		long m = newMember();
		long r = holdSeat(m, nextSeat());
		payments.start(m, r, key(), OK);

		assertThat(saga.approve(r, r, "dup")).isEqualTo(Approval.ALREADY_CONFIRMED);
		assertThat(saga.fail(r, r, "LATE_DECLINE")).isFalse();

		assertThat(str("SELECT status FROM reservation WHERE reservation_id = ?", r)).isEqualTo("CONFIRMED");
		assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'RESERVATION_CONFIRMED'",
				Long.toString(r))).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ? AND to_status = 'CONFIRMED'", r))
				.isEqualTo(1);
	}

	@Test
	void 승인됐는데_좌석을_잃었으면_예약은_만료하고_전액_환불() {
		long m = newMember();
		long r = holdSeat(m, nextSeat());
		payments.start(m, r, key(), NO_RESPONSE);
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id = ?", r); // 좌석을 잃음 (정상이면 안 생기는 안전망 경로)

		assertThat(saga.approve(r, r, "mock-tx")).isEqualTo(Approval.CONFIRM_FAILED);

		assertThat(str("SELECT status FROM reservation WHERE reservation_id = ?", r)).isEqualTo("EXPIRED");
		assertThat(str("SELECT status FROM saga_instance WHERE reservation_id = ?", r)).isEqualTo("COMPENSATED");
		assertThat(perRun(m)).isZero();
		// PAY-05: 승인된 돈은 전액 환불
		assertLateRefundDone(r);
	}

	@Test
	void 늦은_승인은_전액_환불_수수료_0_환불_완료_이벤트는_한_번() {
		long m = newMember();
		long r = holdSeat(m, nextSeat());
		payments.start(m, r, key(), DECLINE);
		int refundsBefore = pg.refundCount();

		assertThat(saga.approve(r, r, "late-tx")).isEqualTo(Approval.LATE_APPROVAL);
		// 같은 승인이 또 와도 환불은 한 번
		assertThat(saga.approve(r, r, "late-tx")).isEqualTo(Approval.REFUNDED);

		assertLateRefundDone(r);
		assertThat(pg.refundCount()).isEqualTo(refundsBefore + 1);
		assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY seq", String.class,
				Long.toString(r))).endsWith("RESERVATION_EXPIRED", "REFUND_COMPLETED");
	}

	@Test
	void 늦은_승인_환불이_실패하면_FAILED로_남고_다시_하면_완료된다() {
		long m = newMember();
		long r = holdSeat(m, nextSeat());
		payments.start(m, r, key(), DECLINE);
		// PG 환불이 장애인 상황: 늦은 승인을 기록하는 트랜잭션만 직접 실행하고 환불은 실패하는 PG로
		long cancelId = tx.execute(st -> {
			jdbc.update("UPDATE payment SET status = 'APPROVED', pg_tx_id = 'late-tx' WHERE payment_id = ?", r);
			return refunds.request(r, r);
		});
		PgClient down = Mockito.mock(PgClient.class);
		Mockito.when(down.refund(Mockito.anyLong(), Mockito.any(), Mockito.anyLong(), Mockito.any()))
				.thenThrow(new IllegalStateException("PG 장애"));

		assertThat(new LateApprovalRefund(jdbc, tx, down, outbox).refund(cancelId)).isEqualTo("FAILED");

		assertThat(str("SELECT refund_status FROM cancellation WHERE cancel_id = ?", cancelId)).isEqualTo("FAILED");
		assertThat(str("SELECT status FROM payment WHERE payment_id = ?", r)).isEqualTo("APPROVED");
		assertThat(saga.approve(r, r, "late-tx")).isEqualTo(Approval.REFUND_IN_PROGRESS);
		assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'REFUND_COMPLETED'",
				Long.toString(r))).isZero();

		// 재시도 (CNL-03 배치가 할 일): 같은 cancel_id로 다시 하면 완료
		assertThat(refunds.refund(cancelId)).isEqualTo("DONE");
		assertLateRefundDone(r);
	}

	/** 늦은 승인 환불이 끝난 모습 (§7.3) */
	private void assertLateRefundDone(long r) {
		assertThat(str("SELECT status FROM payment WHERE payment_id = ?", r)).isEqualTo("REFUNDED");
		java.util.Map<String, Object> c = jdbc.queryForMap("SELECT * FROM cancellation WHERE reservation_id = ?", r);
		assertThat(c.get("CANCEL_TYPE")).isEqualTo("PAY_FAILED");
		assertThat(((Number) c.get("REFUND_AMOUNT")).longValue())
				.isEqualTo(count("SELECT amount FROM payment WHERE payment_id = ?", r));
		assertThat(((Number) c.get("CANCEL_FEE")).longValue()).isZero();
		assertThat(c.get("REFUND_STATUS")).isEqualTo("DONE");
		assertThat(c.get("REFUND_PG_TX_ID")).asString().startsWith("mock-refund-");
		assertThat(c.get("FEE_POLICY_ID")).isNull();
		assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'REFUND_COMPLETED'",
				Long.toString(r))).isEqualTo(1);
	}

	@Test
	void 실패로_정리한_뒤_승인이_오면_늦은_승인으로_기록하고_예약은_되살리지_않는다() {
		long m = newMember();
		long r = holdSeat(m, nextSeat());
		payments.start(m, r, key(), DECLINE);

		assertThat(saga.approve(r, r, "late-tx")).isEqualTo(Approval.LATE_APPROVAL);

		assertThat(str("SELECT status FROM payment WHERE payment_id = ?", r)).isEqualTo("REFUNDED");   // 승인 → 바로 환불
		assertThat(str("SELECT status FROM reservation WHERE reservation_id = ?", r)).isEqualTo("EXPIRED");
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ?", r)).isZero();
	}

	@Test
	void 승인과_실패가_동시에_와도_한쪽만_반영되고_좌석과_카운터가_어긋나지_않는다() throws Exception {
		long m = newMember();
		long r = holdSeat(m, nextSeat());
		payments.start(m, r, key(), NO_RESPONSE);

		concurrently(20, i -> i % 2 == 0 ? saga.approve(r, r, "tx") : saga.fail(r, r, "DECLINED"));

		String rs = str("SELECT status FROM reservation WHERE reservation_id = ?", r);
		long sold = count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ? AND status = 'SOLD'", r);
		long confirmedEvents = count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'RESERVATION_CONFIRMED'",
				Long.toString(r));
		long expiredEvents = count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'RESERVATION_EXPIRED'",
				Long.toString(r));
		assertThat(confirmedEvents + expiredEvents).isEqualTo(1);
		if ("CONFIRMED".equals(rs)) {
			assertThat(sold).isEqualTo(1);
			assertThat(perRun(m)).isEqualTo(1);
		} else {
			assertThat(rs).isEqualTo("EXPIRED");
			assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ?", r)).isZero();
			assertThat(perRun(m)).isZero();
		}
	}

	// ------------------------------------------------------------ 도우미

	private interface Task {
		Object run(int i) throws Exception;
	}

	/** 동시에 출발시켜 결과(성공 값 또는 실패 코드)를 모은다 */
	private static List<Object> concurrently(int n, Task task) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(n);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<Object>> fs = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				int idx = i;
				Callable<Object> c = () -> {
					go.await();
					try {
						return task.run(idx);
					} catch (BusinessException e) {
						return e.getErrorCode().code();
					}
				};
				fs.add(pool.submit(c));
			}
			go.countDown();
			List<Object> out = new ArrayList<>();
			for (Future<Object> f : fs) {
				out.add(f.get());
			}
			return out;
		} finally {
			pool.shutdownNow();
		}
	}

	private static String code(Runnable call) {
		try {
			call.run();
		} catch (BusinessException e) {
			return e.getErrorCode().code();
		}
		throw new AssertionError("BusinessException이 나야 함");
	}

	private long holdSeat(long memberId, long seatId) {
		return hold.hold(memberId, new HoldCommand(RUN, FROM, TO, List.of(new SeatRequest(seatId, "ADULT")), "WEB",
				key())).reservationId();
	}

	private long nextSeat() {
		return seatIds.get(seatIds.size() / 2 + seatCursor++);
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

	private long newMember() {
		long id = NEXT_MEMBER.getAndIncrement();
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
				id, "pay" + id + "@infra-test.jejari.dev", "pay-ci-" + id, "테스트");
		return id;
	}

	private LocalDateTime deadline(long r) {
		return jdbc.queryForObject("SELECT pay_deadline_at FROM reservation WHERE reservation_id = ?", LocalDateTime.class, r);
	}

	/** 마감이 지나 스캐너 후보가 되게 */
	private void dueNow(long r) {
		jdbc.update("UPDATE payment SET next_check_at = SYSTIMESTAMP - INTERVAL '1' SECOND WHERE payment_id = ?", r);
	}

	private long perRun(long memberId) {
		List<Long> v = jdbc.queryForList(
				"SELECT seat_count FROM purchase_counter WHERE scope = 'PER_RUN' AND scope_ref_id = ? AND ci_hash = ?",
				Long.class, RUN, "pay-ci-" + memberId);
		return v.isEmpty() ? 0 : v.get(0);
	}

	private String str(String sql, Object... args) {
		return jdbc.queryForObject(sql, String.class, args);
	}

	private long count(String sql, Object... args) {
		Long v = jdbc.queryForObject(sql, Long.class, args);
		return v == null ? 0 : v;
	}

	private void cleanDb() {
		long lo = MEMBER_BASE + 1;
		long hi = MEMBER_BASE + 9999;
		String mine = "SELECT reservation_id FROM reservation WHERE member_id BETWEEN " + lo + " AND " + hi;
		jdbc.update("DELETE FROM outbox_event WHERE aggregate_type = 'RESERVATION' AND aggregate_id IN (SELECT TO_CHAR(reservation_id) FROM reservation WHERE member_id BETWEEN ? AND ?)",
				lo, hi);
		jdbc.update("DELETE FROM cancellation WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM payment WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM payment_method WHERE member_id BETWEEN ? AND ?", lo, hi);
		jdbc.update("DELETE FROM reservation_history WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM saga_instance WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation_seat WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation WHERE member_id BETWEEN ? AND ?", lo, hi);
		jdbc.update("DELETE FROM purchase_counter WHERE ci_hash LIKE 'pay-ci-%'");
		jdbc.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?", lo, hi);
	}
}
