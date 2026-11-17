package com.jejari.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.payment.PaymentService.PaymentResult;
import com.jejari.seat.SeatHoldService;
import com.jejari.seat.SeatHoldService.HoldCommand;
import com.jejari.seat.SeatHoldService.SeatRequest;
import com.jejari.seat.SeatReleaseService;
import com.jejari.support.NoRunningApp;
import com.jejari.waitlist.offer.WaitlistOfferTx;

/**
 * 취소표(대기자) 예약의 결제 (WL-05, concurrency.md §4.1-5·§5.1·§7.1).
 * 강동균의 제안 트랜잭션 {@link WaitlistOfferTx#offer}로 실제 제안 예약을 만든 뒤 결제한다.
 *
 * <ul>
 *   <li>결제 시작: {@code waitlist.offer_deadline_at}이 예약 마감과 같이 늘어난다</li>
 *   <li>승인: 대기 ACCEPTED (결제 Saga)</li>
 *   <li>거절: 대기 EXPIRED (강동균의 SeatReleaseTx, 결제 Saga가 부름)</li>
 * </ul>
 */
@Tag("infra")
@ExtendWith(NoRunningApp.class)
@SpringBootTest(properties = {
		"spring.data.redis.database=15",
		"jejari.queue.scheduler-enabled=false",
		"jejari.risk.cache-resync-enabled=false",
		"jejari.risk.batch-enabled=false",
		"jejari.seat.expiry-scanner-enabled=false",
		"jejari.train.sale-open-scheduler-enabled=false",
		"jejari.payment.recheck-scanner-enabled=false",
		"jejari.event.relay.enabled=false",
		"jejari.notification.consumer-enabled=false",
		"jejari.notification.reminder-enabled=false",
		"jejari.notification.dispatcher-enabled=false"})
class WaitlistPaymentInfraTest {

	static final long RUN = 9001;
	static final String SEOUL = "3900023";
	static final String BUSAN = "3900114";
	static final long MEMBER_BASE = 940000;
	static final AtomicLong NEXT_MEMBER = new AtomicLong(MEMBER_BASE + 1);

	static final String OK = "4111111111111111";
	static final String DECLINE = "4111111111111110";
	static final String NO_RESPONSE = "4111111111111119";

	@Autowired
	PaymentService payments;
	@Autowired
	WaitlistOfferTx offerTx;
	@Autowired
	SeatHoldService hold;
	@Autowired
	SeatReleaseService release;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	TransactionTemplate tx;
	@Autowired
	Clock clock;

	List<Long> seatIds;
	int seatCursor;

	@BeforeEach
	void setUp() {
		cleanDb();
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		seatIds = jdbc.queryForList(
				"SELECT seat_id FROM seat WHERE train_id = 1 AND seat_grade = 'GENERAL' ORDER BY seat_id DESC",
				Long.class);
		seatCursor = 100;
	}

	@AfterEach
	void tearDown() {
		cleanDb();
	}

	record Offer(long waitlistId, long memberId, long reservationId) {
	}

	@Test
	void 결제를_시작하면_대기_제안_마감도_예약_마감과_같이_늘어난다() {
		Offer o = offer();
		// 마감이 30초 남은 상태로 만든다. 결제 시작은 마감을 now + PG 타임아웃 + 여유까지 민다 (§4.1-2)
		LocalDateTime soon = LocalDateTime.now(clock).plusSeconds(30);
		setDeadline(o, soon);

		PaymentResult res = payments.start(o.memberId(), o.reservationId(), key(), NO_RESPONSE);

		assertThat(res.reservationStatus()).isEqualTo("PAYMENT_PENDING");
		LocalDateTime resvDeadline = jdbc.queryForObject(
				"SELECT pay_deadline_at FROM reservation WHERE reservation_id = ?", LocalDateTime.class, o.reservationId());
		LocalDateTime offerDeadline = jdbc.queryForObject(
				"SELECT offer_deadline_at FROM waitlist WHERE waitlist_id = ?", LocalDateTime.class, o.waitlistId());
		assertThat(resvDeadline).isAfter(soon.plusSeconds(60));
		// 대기 마감은 예약 마감의 복사본이라 같이 바뀐다 (CLAUDE.md "마감 시각")
		assertThat(offerDeadline).isEqualTo(resvDeadline);
		assertThat(waitStatus(o)).isEqualTo("OFFERED");
	}

	@Test
	void 승인되면_예약_확정_대기_ACCEPTED() {
		Offer o = offer();

		PaymentResult res = payments.start(o.memberId(), o.reservationId(), key(), OK);

		assertThat(res.reservationStatus()).isEqualTo("CONFIRMED");
		assertThat(waitStatus(o)).isEqualTo("ACCEPTED");
	}

	@Test
	void 거절되면_예약_만료_대기_EXPIRED() {
		Offer o = offer();

		PaymentResult res = payments.start(o.memberId(), o.reservationId(), key(), DECLINE);

		assertThat(res.reservationStatus()).isEqualTo("EXPIRED");
		assertThat(waitStatus(o)).isEqualTo("EXPIRED");
	}

	// ------------------------------------------------------------ 준비

	/** 누가 잡았다가 취소한 좌석을 대기자에게 제안 → 대기자 예약(source WAITLIST, HOLD) */
	private Offer offer() {
		long seat = seatIds.get(seatCursor++);
		long waiter = newMember();
		long w = insertWaiting(waiter);
		long freed = freeSeat(seat);
		List<WaitlistOfferTx.Offered> offered = tx.execute(st -> offerTx.offer(RUN, SEOUL, BUSAN, List.of(seat), freed));
		assertThat(offered).hasSize(1);
		long r = offered.get(0).reservationId();
		assertThat(jdbc.queryForObject("SELECT source FROM reservation WHERE reservation_id = ?", String.class, r))
				.isEqualTo("WAITLIST");
		return new Offer(w, waiter, r);
	}

	/** 마감 시각과 그 복사본(좌석 HOLD 만료, 대기 제안 마감)을 함께 바꾼다 */
	private void setDeadline(Offer o, LocalDateTime at) {
		jdbc.update("UPDATE reservation SET pay_deadline_at = ? WHERE reservation_id = ?", at, o.reservationId());
		jdbc.update("UPDATE seat_inventory SET hold_expires_at = ? WHERE reservation_id = ? AND status = 'HOLD'", at,
				o.reservationId());
		jdbc.update("UPDATE waitlist SET offer_deadline_at = ? WHERE waitlist_id = ?", at, o.waitlistId());
	}

	private long freeSeat(long seatId) {
		long m = newMember();
		long r = hold.hold(m, new HoldCommand(RUN, SEOUL, BUSAN, List.of(new SeatRequest(seatId, "ADULT")), "WEB",
				key())).reservationId();
		release.cancelBeforePay(m, r);
		return r;
	}

	private long insertWaiting(long memberId) {
		long id = jdbc.queryForObject("SELECT waitlist_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO waitlist (waitlist_id, member_id, run_id, from_station_id, to_station_id, seat_grade,
				       seat_count, requested_at, priority_at, penalty_ms, status)
				VALUES (?, ?, ?, ?, ?, 'GENERAL', 1, SYSTIMESTAMP, SYSTIMESTAMP, 0, 'WAITING')""",
				id, memberId, RUN, SEOUL, BUSAN);
		return id;
	}

	private long newMember() {
		long id = NEXT_MEMBER.getAndIncrement();
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
				id, "wlpay" + id + "@infra-test.jejari.dev", ciHash(id), "테스트");
		return id;
	}

	/** OracleMemberIdentityPort가 ^[0-9a-f]{64}$ 를 요구한다 */
	private static String ciHash(long memberId) {
		return String.format("%064x", memberId);
	}

	private String waitStatus(Offer o) {
		return jdbc.queryForObject("SELECT status FROM waitlist WHERE waitlist_id = ?", String.class, o.waitlistId());
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

	private void cleanDb() {
		long lo = MEMBER_BASE + 1;
		long hi = MEMBER_BASE + 9999;
		String mine = "SELECT reservation_id FROM reservation WHERE member_id BETWEEN " + lo + " AND " + hi;
		String members = "SELECT member_id FROM member WHERE member_id BETWEEN " + lo + " AND " + hi;
		jdbc.update("DELETE FROM notification WHERE member_id IN (" + members + ")");
		jdbc.update("DELETE FROM waitlist WHERE member_id IN (" + members + ")");
		jdbc.update("DELETE FROM outbox_event WHERE aggregate_type = 'RESERVATION' AND aggregate_id IN ("
				+ "SELECT TO_CHAR(reservation_id) FROM reservation WHERE member_id BETWEEN ? AND ?)", lo, hi);
		jdbc.update("DELETE FROM payment WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM payment_method WHERE member_id BETWEEN ? AND ?", lo, hi);
		jdbc.update("DELETE FROM reservation_history WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM saga_instance WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation_seat WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation WHERE member_id BETWEEN ? AND ?", lo, hi);
		for (long id = lo; id < NEXT_MEMBER.get(); id++) {
			jdbc.update("DELETE FROM purchase_counter WHERE ci_hash = ?", ciHash(id));
		}
		jdbc.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?", lo, hi);
	}
}
