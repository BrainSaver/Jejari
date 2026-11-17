package com.jejari.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.error.BusinessException;
import com.jejari.seat.SeatHoldService.HoldCommand;
import com.jejari.seat.SeatHoldService.SeatRequest;
import com.jejari.seat.SeatReleaseService.ReturnedSeats;

/**
 * 결제·취소 쪽이 부르는 좌석 단계를 실제 Oracle·Redis로 확인한다.
 * 결제 쪽(§4 결제 시작, §5.1 승인)과 취소 쪽(§8 확정 취소)의 트랜잭션은 이 테스트가 SQL 몇 줄로 흉내 낸다.
 */
@Tag("infra")
@SpringBootTest(properties = {
		"spring.data.redis.database=15",
		"jejari.queue.scheduler-enabled=false",
		"jejari.risk.cache-resync-enabled=false",
		"jejari.seat.expiry-scanner-enabled=false"})
class SeatPaymentInfraTest {

	static final long RUN = 9001;
	static final String FROM = "3900023";
	static final String TO = "3900114";
	static final long MEMBER_BASE = 970000;
	static final AtomicLong NEXT_MEMBER = new AtomicLong(MEMBER_BASE + 1);
	static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	@Autowired
	SeatHoldService hold;
	@Autowired
	SeatPaymentService seatPay;
	@Autowired
	SeatReleaseService release;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	TransactionTemplate tx;

	List<Long> seatIds;

	@BeforeEach
	void setUp() {
		cleanDb();
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		// 다른 좌석 테스트와 겹치지 않게 뒤쪽 좌석을 쓴다
		seatIds = jdbc.queryForList("SELECT seat_id FROM seat WHERE train_id = 1 ORDER BY seat_id DESC", Long.class)
				.subList(100, 140);
	}

	@AfterEach
	void tearDown() {
		cleanDb();
	}

	// ------------------------------------------------------------ §4 결제 시작

	@Test
	void 결제_시작은_좌석_마감과_Redis_키를_새_마감으로_함께_연장한다() {
		long s1 = seatIds.get(0);
		long s2 = seatIds.get(1);
		long r = hold.hold(newMember(), cmd(s1, s2)).reservationId();
		LocalDateTime d = LocalDateTime.now(ZONE).plusMinutes(20).truncatedTo(ChronoUnit.SECONDS);

		assertThat(startPayment(r, d)).isTrue();

		assertThat(status(r)).isEqualTo("PAYMENT_PENDING");
		assertThat(jdbc.queryForList("SELECT hold_expires_at FROM seat_inventory WHERE reservation_id = ?",
				LocalDateTime.class, r)).containsOnly(d);
		for (long s : List.of(s1, s2)) {
			assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s, 0))).isEqualTo(Long.toString(r));
			assertThat(redis.getExpire(SeatKeys.hold(RUN, s, 0))).isBetween(19 * 60L, 20 * 60L); // 최초 10분 → 20분
		}
	}

	@Test
	void 좌석_하나라도_잃었으면_결제_시작은_false이고_롤백하면_아무것도_안_바뀐다() {
		long s1 = seatIds.get(2);
		long s2 = seatIds.get(3);
		long r = hold.hold(newMember(), cmd(s1, s2)).reservationId();
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id = ? AND seat_id = ?", r, s2); // 좌석 하나를 잃은 상태
		long ttlBefore = redis.getExpire(SeatKeys.hold(RUN, s1, 0));

		assertThat(startPayment(r, LocalDateTime.now(ZONE).plusMinutes(20))).isFalse();

		assertThat(status(r)).isEqualTo("HOLD");
		assertThat(redis.getExpire(SeatKeys.hold(RUN, s1, 0))).isLessThanOrEqualTo(ttlBefore); // 연장되지 않음
	}

	@Test
	void 결제_시작은_TTL로_사라진_Redis_키를_되살리지_않는다() {
		long s = seatIds.get(4);
		long r = hold.hold(newMember(), cmd(s)).reservationId();
		redis.delete(SeatKeys.hold(RUN, s, 0));

		assertThat(startPayment(r, LocalDateTime.now(ZONE).plusMinutes(20))).isTrue(); // DB가 최종 판정
		assertThat(redis.hasKey(SeatKeys.hold(RUN, s, 0))).isFalse();
	}

	// ------------------------------------------------------------ §5.1 승인

	@Test
	void 승인하면_좌석이_SOLD로_확정되고_Redis_키는_출발까지_SOLD_R() {
		long s1 = seatIds.get(5);
		long s2 = seatIds.get(6);
		long r = hold.hold(newMember(), cmd(s1, s2)).reservationId();
		startPayment(r, LocalDateTime.now(ZONE).plusMinutes(20));

		assertThat(approve(r)).isTrue();

		assertThat(status(r)).isEqualTo("CONFIRMED");
		assertThat(count("""
				SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ? AND status = 'SOLD' AND hold_expires_at IS NULL""", r))
				.isEqualTo(2);
		for (long s : List.of(s1, s2)) {
			assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s, 0))).isEqualTo("SOLD:" + r);
			assertThat(redis.getExpire(SeatKeys.hold(RUN, s, 0))).isGreaterThan(24 * 3600L); // 운행은 3일 뒤
		}
		// 팔린 좌석은 Redis 단계에서 바로 막힌다
		assertThatThrownBy(() -> hold.hold(newMember(), cmd(s1)))
				.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(SeatErrorCode.SEAT_ALREADY_TAKEN);
	}

	@Test
	void 승인_때_Redis_키가_사라졌어도_SOLD_R로_다시_쓴다() {
		long s = seatIds.get(7);
		long r = hold.hold(newMember(), cmd(s)).reservationId();
		startPayment(r, LocalDateTime.now(ZONE).plusMinutes(20));
		redis.delete(SeatKeys.hold(RUN, s, 0));

		assertThat(approve(r)).isTrue();
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s, 0))).isEqualTo("SOLD:" + r);
	}

	@Test
	void 좌석을_잃은_뒤_승인이_오면_false이고_롤백하면_좌석도_Redis도_그대로다() {
		long s1 = seatIds.get(8);
		long s2 = seatIds.get(9);
		long r = hold.hold(newMember(), cmd(s1, s2)).reservationId();
		startPayment(r, LocalDateTime.now(ZONE).plusMinutes(20));
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id = ? AND seat_id = ?", r, s2);

		assertThat(approve(r)).isFalse(); // 결제 쪽은 "확정 실패" 경로(늦은 승인 환불)로 간다

		assertThat(status(r)).isEqualTo("PAYMENT_PENDING");
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ? AND status = 'HOLD'", r)).isEqualTo(1);
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s1, 0))).isEqualTo(Long.toString(r));
	}

	// ------------------------------------------------------------ §8 확정 취소

	@Test
	void 확정_취소하면_SOLD_행을_지우고_카운터를_줄이고_좌석은_대기자_몫으로_묶인다() {
		long m = newMember();
		long s1 = seatIds.get(10);
		long s2 = seatIds.get(11);
		long r = confirmed(m, s1, s2);

		ReturnedSeats seats = cancelConfirmed(r);

		assertThat(seats.seatIds()).containsExactlyInAnyOrder(s1, s2);
		assertThat(seats.seatCount()).isEqualTo(2);
		Map<String, Object> payload = seats.eventPayload();
		assertThat(payload).containsEntry("reservationId", r).containsEntry("runId", RUN).containsEntry("from", FROM)
				.containsEntry("to", TO).containsEntry("seatCount", 2).containsKey("seats");
		assertThat(status(r)).isEqualTo("CANCELED");
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ?", r)).isZero();
		assertThat(perRun(m)).isZero();
		assertThat(redis.opsForValue().get(SeatKeys.limitRun(RUN, ci(m)))).isEqualTo("0");
		for (long s : List.of(s1, s2)) {
			assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s, 0))).isEqualTo("REOFFER:" + r);
			assertThat(redis.getExpire(SeatKeys.hold(RUN, s, 0))).isBetween(1L, 60L);
		}
		// T30 앞부분: 대기자 제안 전에는 일반 사용자가 잡지 못한다
		assertThatThrownBy(() -> hold.hold(newMember(), cmd(s1)))
				.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(SeatErrorCode.SEAT_ALREADY_TAKEN);
	}

	@Test
	void 확정_취소_좌석_반환을_두_번_불러도_카운터는_한_번만_준다() {
		long m = newMember();
		long r = confirmed(m, seatIds.get(12));
		cancelConfirmed(r);

		ReturnedSeats again = tx.execute(st -> release.returnSoldSeatsInCurrentTx(r));

		assertThat(again).isNull();
		assertThat(perRun(m)).isZero(); // 두 번 줄였다면 CHECK(seat_count >= 0) 위반
	}

	@Test
	void 예약을_CANCELED로_바꾸기_전에_부르면_거부한다() {
		long m = newMember();
		long r = confirmed(m, seatIds.get(13));

		assertThatThrownBy(() -> tx.execute(st -> release.returnSoldSeatsInCurrentTx(r)))
				.isInstanceOf(IllegalStateException.class);
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ? AND status = 'SOLD'", r)).isEqualTo(1);
		assertThat(perRun(m)).isEqualTo(1);
	}

	@Test
	void 확정_취소_트랜잭션이_롤백되면_좌석도_카운터도_Redis도_그대로다() {
		long m = newMember();
		long s = seatIds.get(14);
		long r = confirmed(m, s);

		tx.executeWithoutResult(st -> {
			jdbc.update("UPDATE reservation SET status = 'CANCELED' WHERE reservation_id = ? AND status = 'CONFIRMED'", r);
			release.returnSoldSeatsInCurrentTx(r);
			st.setRollbackOnly();
		});

		assertThat(status(r)).isEqualTo("CONFIRMED");
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ? AND status = 'SOLD'", r)).isEqualTo(1);
		assertThat(perRun(m)).isEqualTo(1);
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s, 0))).isEqualTo("SOLD:" + r);
	}

	@Test
	void 트랜잭션_밖에서_부르면_거부한다() {
		assertThatThrownBy(() -> seatPay.extendHoldInCurrentTx(1L, LocalDateTime.now())).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> seatPay.confirmSeatsInCurrentTx(1L)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> release.returnSoldSeatsInCurrentTx(1L)).isInstanceOf(IllegalStateException.class);
	}

	// ------------------------------------------------------------ 결제·취소 쪽 흉내

	/** §4.1: reservation 전이 + 새 마감 → 좌석 단계. false면 롤백 */
	private boolean startPayment(long r, LocalDateTime d) {
		return Boolean.TRUE.equals(tx.execute(st -> {
			jdbc.update("""
					UPDATE reservation SET status = 'PAYMENT_PENDING', pay_deadline_at = ?, updated_at = SYSTIMESTAMP,
					       version = version + 1
					 WHERE reservation_id = ? AND status = 'HOLD'""", d, r);
			boolean ok = seatPay.extendHoldInCurrentTx(r, d);
			if (!ok) {
				st.setRollbackOnly();
			}
			return ok;
		}));
	}

	/** §5.1: reservation 락 → 좌석 확정 → reservation CONFIRMED. false면 롤백 */
	private boolean approve(long r) {
		return Boolean.TRUE.equals(tx.execute(st -> {
			jdbc.queryForObject("SELECT status FROM reservation WHERE reservation_id = ? FOR UPDATE", String.class, r);
			boolean ok = seatPay.confirmSeatsInCurrentTx(r);
			if (!ok) {
				st.setRollbackOnly();
				return false;
			}
			jdbc.update("""
					UPDATE reservation SET status = 'CONFIRMED', confirmed_at = SYSTIMESTAMP, updated_at = SYSTIMESTAMP,
					       version = version + 1
					 WHERE reservation_id = ? AND status = 'PAYMENT_PENDING'""", r);
			return true;
		}));
	}

	/** §8.1: CONFIRMED → CANCELED 1행 → 좌석 반환 */
	private ReturnedSeats cancelConfirmed(long r) {
		return tx.execute(st -> {
			int n = jdbc.update("""
					UPDATE reservation SET status = 'CANCELED', canceled_at = SYSTIMESTAMP, updated_at = SYSTIMESTAMP,
					       version = version + 1
					 WHERE reservation_id = ? AND status = 'CONFIRMED'""", r);
			assertThat(n).isEqualTo(1);
			return release.returnSoldSeatsInCurrentTx(r);
		});
	}

	private long confirmed(long m, long... seats) {
		long r = hold.hold(m, cmd(seats)).reservationId();
		assertThat(startPayment(r, LocalDateTime.now(ZONE).plusMinutes(20))).isTrue();
		assertThat(approve(r)).isTrue();
		return r;
	}

	// ------------------------------------------------------------ 도우미

	private static HoldCommand cmd(long... seats) {
		List<SeatRequest> list = new ArrayList<>();
		for (long s : seats) {
			list.add(new SeatRequest(s, "ADULT"));
		}
		return new HoldCommand(RUN, FROM, TO, list, "WEB", UUID.randomUUID().toString());
	}

	private long newMember() {
		long id = NEXT_MEMBER.getAndIncrement();
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
				id, "pay" + id + "@infra-test.jejari.dev", ci(id), "테스트");
		return id;
	}

	private static String ci(long memberId) {
		return "pay-ci-" + memberId;
	}

	private String status(long r) {
		return jdbc.queryForObject("SELECT status FROM reservation WHERE reservation_id = ?", String.class, r);
	}

	private long perRun(long memberId) {
		List<Long> v = jdbc.queryForList(
				"SELECT seat_count FROM purchase_counter WHERE scope = 'PER_RUN' AND scope_ref_id = ? AND ci_hash = ?",
				Long.class, RUN, ci(memberId));
		return v.isEmpty() ? 0 : v.get(0);
	}

	private long count(String sql, Object... args) {
		Long v = jdbc.queryForObject(sql, Long.class, args);
		return v == null ? 0 : v;
	}

	private void cleanDb() {
		String mine = "SELECT reservation_id FROM reservation WHERE member_id BETWEEN " + (MEMBER_BASE + 1) + " AND " + (MEMBER_BASE + 9999);
		jdbc.update("DELETE FROM outbox_event WHERE aggregate_type = 'RESERVATION' AND aggregate_id IN (SELECT TO_CHAR(reservation_id) FROM reservation WHERE member_id BETWEEN ? AND ?)",
				MEMBER_BASE + 1, MEMBER_BASE + 9999);
		jdbc.update("DELETE FROM reservation_history WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM saga_instance WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation_seat WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
		jdbc.update("DELETE FROM purchase_counter WHERE ci_hash LIKE 'pay-ci-%'");
		jdbc.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
	}
}
