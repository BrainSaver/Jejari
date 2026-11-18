package com.jejari.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.config.AppPolicy;
import com.jejari.common.error.BusinessException;
import com.jejari.seat.SeatHoldService.HoldCommand;
import com.jejari.seat.SeatHoldService.SeatRequest;
import com.jejari.support.NoRunningApp;

/**
 * 좌석 해제: RSV-03 만료 스캐너(§6.1·§7), RSV-05 결제 전 취소(§7.4), 결제 실패 해제(§5.2 → §7.1)를
 * 실제 Oracle·Redis로 확인한다. 스캐너 스케줄은 끄고 {@link HoldExpiryScanner#scanOnce()}를 직접 부른다.
 */
@Tag("infra")
@ExtendWith(NoRunningApp.class)
@SpringBootTest(properties = {
		"spring.data.redis.database=15",
		"jejari.queue.scheduler-enabled=false",
		"jejari.risk.cache-resync-enabled=false",
		"jejari.risk.batch-enabled=false",
		"jejari.seat.expiry-scanner-enabled=false",
		"jejari.train.sale-open-scheduler-enabled=false"})
class SeatReleaseInfraTest {

	static final long RUN = 9001;
	static final String FROM = "3900023";
	static final String TO = "3900114";
	static final long MEMBER_BASE = 980000;
	static final AtomicLong NEXT_MEMBER = new AtomicLong(MEMBER_BASE + 1);

	@Autowired
	SeatHoldService hold;
	@Autowired
	SeatReleaseService release;
	@Autowired
	AppPolicy policy;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	TransactionTemplate tx;

	HoldExpiryScanner scanner;
	List<Long> seatIds;

	@BeforeEach
	void setUp() {
		cleanDb();
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		seatIds = jdbc.queryForList("SELECT seat_id FROM seat WHERE train_id = 1 ORDER BY seat_id DESC", Long.class);
		scanner = new HoldExpiryScanner(jdbc, release, policy);
	}

	@AfterEach
	void tearDown() {
		cleanDb();
	}

	// ------------------------------------------------------------ RSV-03 만료 스캐너

	@Test
	void 기한이_지난_HOLD는_스캐너가_만료시키고_좌석_카운터_한도를_전부_돌려준다() {
		long m = newMember();
		long s1 = seatIds.get(0);
		long s2 = seatIds.get(1);
		long r = hold.hold(m, cmd(s1, s2)).reservationId();
		pastDeadline(r, true, s1, s2);

		assertThat(scanner.scanOnce()).isEqualTo(1);

		assertThat(status(r)).isEqualTo("EXPIRED");
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ?", r)).isZero();
		assertThat(perRun(m)).isZero();
		assertThat(count("""
				SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ? AND from_status = 'HOLD'
				   AND to_status = 'EXPIRED' AND actor_type = 'SYSTEM'""", r)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM saga_instance WHERE reservation_id = ? AND status = 'COMPENSATED'", r)).isEqualTo(1);
		assertThat(count("""
				SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'RESERVATION_EXPIRED'
				   AND DBMS_LOB.INSTR(payload, 'TIMEOUT') > 0""", Long.toString(r))).isEqualTo(1);
		assertThat(redis.opsForValue().get(SeatKeys.limitRun(RUN, ci(m)))).isEqualTo("0");
		// 반환된 좌석은 다른 사람이 바로 잡을 수 있다 (HOLD 만료는 REOFFER 없이 모두에게 열림)
		assertThat(hold.hold(newMember(), cmd(s1)).status()).isEqualTo("HOLD");
	}

	@Test
	void 기한이_남은_HOLD는_건드리지_않는다() {
		long m = newMember();
		long r = hold.hold(m, cmd(seatIds.get(2))).reservationId();

		assertThat(scanner.scanOnce()).isZero();
		assertThat(status(r)).isEqualTo("HOLD");
		assertThat(perRun(m)).isEqualTo(1);
	}

	@Test
	void 기한_초과_반환율_20건_중_20건() {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			long seat = seatIds.get(10 + i);
			long r = hold.hold(newMember(), cmd(seat)).reservationId();
			pastDeadline(r, true, seat);
			ids.add(r);
		}

		// 스캐너를 부르기 전에 20건이 모두 "기한 지난 HOLD"인지 먼저 본다. 여기서 깨지면 원인은
		// 스캐너가 아니라 그 앞이다 — 앱(bootRun)이 떠 있으면 그쪽 스캐너가 먼저 만료시킨다 (NoRunningApp)
		String in = String.join(",", ids.stream().map(String::valueOf).toList());
		assertThat(count("SELECT COUNT(*) FROM reservation WHERE reservation_id IN (" + in
				+ ") AND status = 'HOLD' AND pay_deadline_at < SYSTIMESTAMP"))
				.as("스캔 전 기한 지난 HOLD 건수")
				.isEqualTo(20);

		int expired = scanner.scanOnce();

		assertThat(expired).isEqualTo(20);
		long stillHeld = count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id IN (" + in + ")");
		assertThat(stillHeld).isZero(); // 좌석 행 20/20 반환
		assertThat(count("SELECT COALESCE(SUM(seat_count), 0) FROM purchase_counter WHERE ci_hash LIKE 'rel-ci-%'")).isZero();
	}

	@Test
	void 스캐너_두_대가_동시에_돌아도_한_번만_처리한다() throws Exception {
		long m = newMember();
		long s = seatIds.get(40);
		long r = hold.hold(m, cmd(s)).reservationId();
		pastDeadline(r, true, s);
		HoldExpiryScanner other = new HoldExpiryScanner(jdbc, release, policy);

		List<Integer> results = race(() -> scanner.scanOnce(), () -> other.scanOnce());

		assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
		assertThat(perRun(m)).isZero(); // 두 번 감소했다면 CHECK(seat_count >= 0) 위반으로 실패
		assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ? AND to_status = 'EXPIRED'", r))
				.isEqualTo(1);
	}

	// ------------------------------------------------------------ RSV-05 결제 전 취소

	@Test
	void 결제_전_취소하면_좌석은_대기자_몫으로_묶이고_카운터_한도가_돌아온다() {
		long m = newMember();
		long s = seatIds.get(50);
		long r = hold.hold(m, cmd(s)).reservationId();

		assertThat(release.cancelBeforePay(m, r).status()).isEqualTo("CANCELED");

		assertThat(status(r)).isEqualTo("CANCELED");
		assertThat(count("SELECT COUNT(*) FROM reservation WHERE reservation_id = ? AND canceled_at IS NOT NULL", r)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ?", r)).isZero();
		assertThat(count("SELECT COUNT(*) FROM cancellation WHERE reservation_id = ?", r)).isZero(); // 돈이 오가지 않음
		assertThat(perRun(m)).isZero();
		assertThat(redis.opsForValue().get(SeatKeys.limitRun(RUN, ci(m)))).isEqualTo("0");
		assertThat(count("""
				SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'RESERVATION_CANCELED'
				   AND DBMS_LOB.INSTR(payload, 'USER_BEFORE_PAY') > 0""", Long.toString(r))).isEqualTo(1);
		// 대기자 먼저 (§9): 좌석 키가 REOFFER:R로 남아 일반 사용자의 선점을 막는다 (T30 앞부분)
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s, 0))).isEqualTo("REOFFER:" + r);
		assertThat(redis.getExpire(SeatKeys.hold(RUN, s, 0))).isBetween(1L, 60L);
		assertThatThrownBy(() -> hold.hold(newMember(), cmd(s)))
				.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(SeatErrorCode.SEAT_ALREADY_TAKEN);
	}

	@Test
	void 취소를_두_번_보내도_결과가_같다() {
		long m = newMember();
		long r = hold.hold(m, cmd(seatIds.get(51))).reservationId();
		release.cancelBeforePay(m, r);

		assertThat(release.cancelBeforePay(m, r).status()).isEqualTo("CANCELED");
		assertThat(perRun(m)).isZero();
		assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ?", r)).isEqualTo(2); // HOLD, CANCELED
	}

	@Test
	void 남의_예약은_없는_예약과_같게_404_결제_중이면_409() {
		long m = newMember();
		long r = hold.hold(m, cmd(seatIds.get(52))).reservationId();

		assertThatThrownBy(() -> release.cancelBeforePay(newMember(), r))
				.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(SeatErrorCode.RESERVATION_NOT_FOUND);

		jdbc.update("UPDATE reservation SET status = 'PAYMENT_PENDING' WHERE reservation_id = ?", r);
		assertThatThrownBy(() -> release.cancelBeforePay(m, r))
				.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(SeatErrorCode.PAYMENT_IN_PROGRESS);
		assertThat(perRun(m)).isEqualTo(1);
	}

	@Test
	void T10_취소와_만료가_동시에_와도_한쪽만_처리되고_카운터는_한_번_감소한다() throws Exception {
		long m = newMember();
		long s1 = seatIds.get(60);
		long s2 = seatIds.get(61);
		long r = hold.hold(m, cmd(s1, s2)).reservationId();
		pastDeadline(r, false, s1, s2); // Redis 키는 남겨 둠

		List<String> results = race(
				() -> release.expire(r) ? "EXPIRED" : "-",
				() -> {
					try {
						release.cancelBeforePay(m, r);
						return "CANCELED";
					} catch (BusinessException e) {
						return "-";
					}
				});

		// cancelBeforePay는 이미 CANCELED면 성공을 돌려주므로, 실제로 상태를 바꾼 쪽은 DB 이력으로 센다
		assertThat(results).contains(status(r));
		assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ? AND from_status = 'HOLD'", r))
				.isEqualTo(1);
		assertThat(perRun(m)).isZero();
		assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type <> 'SEAT_HELD'",
				Long.toString(r))).isEqualTo(1);
	}

	// ------------------------------------------------------------ 결제 실패 (Saga가 부르는 자리)

	@Test
	void 결제_실패는_호출한_트랜잭션_안에서_해제되고_커밋_뒤에_Redis를_되돌린다() {
		long m = newMember();
		long s = seatIds.get(70);
		long r = hold.hold(m, cmd(s)).reservationId();
		jdbc.update("UPDATE reservation SET status = 'PAYMENT_PENDING' WHERE reservation_id = ?", r);

		Boolean first = tx.execute(st -> release.releaseInCurrentTx(r, ReleaseReason.PAY_FAILED));
		Boolean again = tx.execute(st -> release.releaseInCurrentTx(r, ReleaseReason.PAY_FAILED));

		assertThat(first).isTrue();
		assertThat(again).isFalse(); // 두 번 불러도 결과가 같다
		assertThat(status(r)).isEqualTo("EXPIRED");
		assertThat(perRun(m)).isZero();
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s, 0))).isEqualTo("REOFFER:" + r);
	}

	@Test
	void 호출한_트랜잭션이_롤백되면_DB도_Redis도_그대로다() {
		long m = newMember();
		long s = seatIds.get(71);
		long r = hold.hold(m, cmd(s)).reservationId();
		jdbc.update("UPDATE reservation SET status = 'PAYMENT_PENDING' WHERE reservation_id = ?", r);

		tx.executeWithoutResult(st -> {
			release.releaseInCurrentTx(r, ReleaseReason.PAY_FAILED);
			st.setRollbackOnly();
		});

		assertThat(status(r)).isEqualTo("PAYMENT_PENDING");
		assertThat(perRun(m)).isEqualTo(1);
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s, 0))).isEqualTo(Long.toString(r));
	}

	// ------------------------------------------------------------ 도우미

	@SafeVarargs
	private static <T> List<T> race(Callable<T>... tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<T>> fs = new ArrayList<>();
		for (Callable<T> t : tasks) {
			fs.add(pool.submit(() -> {
				start.await();
				return t.call();
			}));
		}
		start.countDown();
		List<T> out = new ArrayList<>();
		for (Future<T> f : fs) {
			out.add(f.get());
		}
		pool.shutdown();
		return out;
	}

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
				id, "rel" + id + "@infra-test.jejari.dev", ci(id), "테스트");
		return id;
	}

	private static String ci(long memberId) {
		return "rel-ci-" + memberId;
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

	/** 결제 기한이 지난 것처럼: DB 마감을 과거로. dropKeys면 Redis 좌석 키도 TTL로 사라진 것처럼 지운다 */
	private void pastDeadline(long r, boolean dropKeys, long... seats) {
		jdbc.update("UPDATE reservation SET pay_deadline_at = SYSTIMESTAMP - INTERVAL '1' MINUTE WHERE reservation_id = ?", r);
		jdbc.update("UPDATE seat_inventory SET hold_expires_at = SYSTIMESTAMP - INTERVAL '1' MINUTE WHERE reservation_id = ?", r);
		if (dropKeys) {
			for (long s : seats) {
				redis.delete(SeatKeys.hold(RUN, s, 0));
			}
		}
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
		jdbc.update("DELETE FROM purchase_counter WHERE ci_hash LIKE 'rel-ci-%'");
		jdbc.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
	}
}
