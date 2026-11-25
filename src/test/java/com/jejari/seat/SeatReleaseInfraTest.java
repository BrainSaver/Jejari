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
		"jejari.train.sale-open-scheduler-enabled=false",
		// 배경의 취소표 소비자(SeatReleaseConsumer)를 끈다. 켜 두면 취소·만료 이벤트를 Kafka로 받아 "대기자 없음"이면
		// REOFFER 키를 바로 풀어(T43, 2026-11-25) "REOFFER로 묶여 남이 못 잡는다"를 보는 테스트와 경합한다
		"jejari.waitlist.offer-enabled=false"})
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
	@Autowired
	SeatScripts scripts;

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
		// 키가 유예 뒤에 이미 사라진 경우(위 pastDeadline(…, true)가 흉내)에는 다른 사람이 바로 잡을 수 있다 (§3 재선점). 유예 안이면 T41·T42
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

	// ------------------------------------------------------------ 유예 (reservation.hold_release_grace_sec, concurrency.md §0.2 · T41~T44)

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Test
	void T41_선점_키는_마감보다_유예만큼_늦게_사라지고_그_사이_다른_사람은_못_잡는다() {
		long grace = policy.getLong(AppPolicy.HOLD_RELEASE_GRACE_SEC);
		long deadlineSec = policy.getLong(AppPolicy.PAY_DEADLINE_MIN) * 60;
		long s = seatIds.get(0);
		long r = hold.hold(newMember(), cmd(s)).reservationId();
		assertThat(redis.getExpire(SeatKeys.hold(RUN, s, 0))).isBetween(deadlineSec + grace - 5, deadlineSec + grace);

		pastDeadline(r, false, s); // DB 마감은 지났지만 Redis 키는 유예 동안 남아 있다 (스캐너가 아직 안 돎)

		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s, 0))).isEqualTo(Long.toString(r));
		assertThatThrownBy(() -> hold.hold(newMember(), cmd(s)))
				.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(SeatErrorCode.SEAT_ALREADY_TAKEN));
	}

	@Test
	void T42_스캐너가_유예_안에_만료_처리하면_키가_비지_않고_REOFFER로_바뀌어_일반_사용자는_계속_못_잡는다() {
		long s = seatIds.get(1);
		long r = hold.hold(newMember(), cmd(s)).reservationId();
		pastDeadline(r, false, s);

		assertThat(scanner.scanOnce()).isEqualTo(1);

		assertThat(status(r)).isEqualTo("EXPIRED");
		// 키가 한 번도 비지 않고 대기자 몫으로 넘어간다 (예전에는 마감에 키가 사라져 이 사이에 아무나 잡을 수 있었다)
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s, 0))).isEqualTo(SeatKeys.reoffer(r));
		assertThatThrownBy(() -> hold.hold(newMember(), cmd(s)))
				.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(SeatErrorCode.SEAT_ALREADY_TAKEN));
	}

	@Test
	void T44_유예가_0이면_예전처럼_마감에_키가_사라진다() {
		redis.opsForHash().put("policy:cache", AppPolicy.HOLD_RELEASE_GRACE_SEC, "0");
		sleep(1100); // AppPolicy는 값을 1초 들고 있다
		try {
			long deadlineSec = policy.getLong(AppPolicy.PAY_DEADLINE_MIN) * 60;
			long s = seatIds.get(2);
			hold.hold(newMember(), cmd(s));
			assertThat(redis.getExpire(SeatKeys.hold(RUN, s, 0))).isBetween(deadlineSec - 5, deadlineSec);
		} finally {
			redis.opsForHash().delete("policy:cache", AppPolicy.HOLD_RELEASE_GRACE_SEC);
			sleep(1100);
		}
	}

	/**
	 * T37 (§10.2): 자정 직전(어제 23:59)에 선점한 예약이 자정 뒤에 만료된다. 1일 한도 감소는 <b>그 예약을 만든 날</b>
	 * (어제) 행에서 해야 한다. 오늘 날짜로 깎으면 오늘 산 표가 없는데도 오늘 행이 줄거나(음수 CHECK 위반) 어제 행이 남는다.
	 */
	@Test
	void T37_자정_전에_잡고_자정_뒤에_만료되면_어제_날짜_1일_카운터가_준다() {
		long m = newMember();
		long yesterdaySeat = seatIds.get(40);
		long todaySeat = seatIds.get(41);
		long r = hold.hold(m, cmd(yesterdaySeat)).reservationId();
		long today = dayCounterRef(m);
		long yesterday = SeatHoldTx.yyyymmdd(java.time.LocalDate.of((int) (today / 10000), (int) (today / 100 % 100),
				(int) (today % 100)).minusDays(1).atTime(23, 59));
		// 어제 23:59에 만든 것처럼: 예약 생성 시각 · 그날 카운터 행 · Redis 그날 키를 어제로 옮긴다
		jdbc.update("UPDATE reservation SET created_at = CAST(TRUNC(SYSDATE) AS TIMESTAMP) - INTERVAL '1' MINUTE WHERE reservation_id = ?", r);
		jdbc.update("UPDATE purchase_counter SET scope_ref_id = ? WHERE scope = 'PER_DAY' AND scope_ref_id = ? AND ci_hash = ?",
				yesterday, today, ci(m));
		redis.delete(SeatKeys.limitDay(today, ci(m)));
		redis.opsForValue().set(SeatKeys.limitDay(yesterday, ci(m)), "1");
		hold.hold(m, cmd(todaySeat)); // 오늘 산 1매 (오늘 행 = 1)
		assertThat(dayCount(m, yesterday)).isEqualTo(1);
		assertThat(dayCount(m, today)).isEqualTo(1);

		pastDeadline(r, true, yesterdaySeat);
		assertThat(scanner.scanOnce()).isEqualTo(1);

		assertThat(status(r)).isEqualTo("EXPIRED");
		assertThat(dayCount(m, yesterday)).isZero();   // 어제 행이 줄었다
		assertThat(dayCount(m, today)).isEqualTo(1);   // 오늘 행은 그대로
		assertThat(perRun(m)).isEqualTo(1);            // 열차당은 오늘 산 1매만
		assertThat(redis.opsForValue().get(SeatKeys.limitDay(yesterday, ci(m)))).isEqualTo("0");
		assertThat(redis.opsForValue().get(SeatKeys.limitDay(today, ci(m)))).isEqualTo("1");
	}

	/**
	 * T45 (§0.2·§3): 만료 스캐너가 멈춰 유예 안에 돌지 못했다. 유예가 지나 Redis 좌석 키가 TTL로 사라지면 DB에는 마감이 지난
	 * HOLD가 남아 있고, 다음 사람의 선점이 §3 재선점으로 그 예약을 만료시킨다 (그 예약의 다른 좌석까지 정리, 카운터는 한 번).
	 * 스캐너가 나중에 다시 돌아도 이미 끝난 예약이라 두 번 처리하지 않는다.
	 */
	@Test
	void T45_스캐너가_멈춰도_유예가_지나면_재선점이_정리하고_스캐너가_돌아와도_두_번_처리하지_않는다() {
		long a = newMember();
		long s1 = seatIds.get(50);
		long s2 = seatIds.get(51);
		long r = hold.hold(a, cmd(s1, s2)).reservationId();
		pastDeadline(r, true, s1, s2); // 마감 + 유예가 지나 키가 사라짐. 스캐너는 돌지 않았다

		assertThat(status(r)).isEqualTo("HOLD"); // 스캐너가 없으니 DB에는 그대로 남아 있다
		long b = newMember();
		long rb = hold.hold(b, cmd(s1)).reservationId();

		assertThat(status(r)).isEqualTo("EXPIRED");
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ?", r)).isZero(); // s2 행도 (고아 없음)
		assertThat(perRun(a)).isZero();
		assertThat(perRun(b)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ? AND seat_id = ? AND status = 'HOLD'", rb, s1))
				.isEqualTo(1);
		assertThat(hold.hold(newMember(), cmd(s2)).status()).isEqualTo("HOLD"); // 남은 좌석도 바로 팔린다

		// 스캐너가 돌아온다: 이미 EXPIRED라 후보가 아니고, 카운터·이벤트도 한 번뿐
		assertThat(scanner.scanOnce()).isZero();
		assertThat(perRun(a)).isZero();
		assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'RESERVATION_EXPIRED'",
				Long.toString(r))).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ? AND to_status = 'EXPIRED'", r))
				.isEqualTo(1);
		// DB 정합: 마감이 지난 HOLD 좌석 행이 없다 (check_booking.sh 5번과 같은 조건)
		assertThat(count("""
				SELECT COUNT(*) FROM seat_inventory s JOIN reservation x ON x.reservation_id = s.reservation_id
				 WHERE s.run_id = ? AND s.status = 'HOLD' AND x.member_id BETWEEN ? AND ? AND s.hold_expires_at < SYSTIMESTAMP""",
				RUN, MEMBER_BASE + 1, MEMBER_BASE + 9999)).isZero();
	}

	/**
	 * T27 (§6): PG 결과를 모르는 결제(PAYMENT_PENDING, 마감 지남)가 한 번에 읽는 건수(scanner.batch_size)보다 많이 쌓였다.
	 * HOLD 만료 스캐너는 쿼리를 나눠 HOLD만 보므로(§6.1 · 인덱스 ix_resv_hold_due) 결과 모르는 결제에 밀리지 않고 계속 만료시킨다.
	 */
	@Test
	void T27_결과_모르는_결제가_한_번에_읽는_건수보다_많아도_HOLD_만료는_계속된다() {
		redis.opsForHash().put("policy:cache", AppPolicy.SCANNER_BATCH_SIZE, "5");
		sleep(1100); // AppPolicy는 값을 1초 들고 있다
		try {
			List<Long> pending = new ArrayList<>();
			for (int i = 0; i < 12; i++) {
				long seat = seatIds.get(100 + i);
				long r = hold.hold(newMember(), cmd(seat)).reservationId();
				pastDeadline(r, false, seat);
				jdbc.update("UPDATE reservation SET status = 'PAYMENT_PENDING', updated_at = SYSTIMESTAMP, version = version + 1 WHERE reservation_id = ?", r);
				pending.add(r);
			}
			long m = newMember();
			long s = seatIds.get(120);
			long r = hold.hold(m, cmd(s)).reservationId();
			pastDeadline(r, false, s);

			assertThat(scanner.scanOnce()).isEqualTo(1); // 결과 모르는 12건(> 5)이 있어도 HOLD 1건을 바로 처리

			assertThat(status(r)).isEqualTo("EXPIRED");
			assertThat(perRun(m)).isZero();
			for (long p : pending) {
				assertThat(status(p)).isEqualTo("PAYMENT_PENDING"); // 결과 확인(§6.2)이 맡을 것 — HOLD 스캐너는 건드리지 않는다
			}
		} finally {
			redis.opsForHash().delete("policy:cache", AppPolicy.SCANNER_BATCH_SIZE);
			sleep(1100);
		}
	}

	/**
	 * T47 (§6.3): 좌석을 잡은 사람이 결제하기 전에 Redis가 15분 죽었다 (결제 기한 10분보다 김). 장애 동안 HOLD 만료를 멈추고,
	 * 복구 뒤 장애 시간만큼(최대 결제 기한 한 번 = 10분) 기한을 늘린다. 복사본(좌석 재고·Saga)과 좌석 키도 같은 값.
	 * 장애 전에 이미 기한이 지난 예약과 결제 중 예약은 늘리지 않고, 같은 장애로 두 번 늘리지 않는다.
	 */
	@Test
	void T47_Redis_장애_동안_만료를_멈추고_복구_뒤_장애_시간만큼_결제_기한을_늘린다() {
		java.time.ZoneId zone = java.time.ZoneId.of("Asia/Seoul");
		java.time.LocalDateTime now = java.time.LocalDateTime.now(zone);
		RedisOutageGuard guard = new RedisOutageGuard(redis, jdbc, tx, scripts, policy, java.time.Clock.system(zone));
		scanner.setOutageGuard(guard);

		long a = newMember();
		long s1 = seatIds.get(130);
		long r1 = hold.hold(a, cmd(s1)).reservationId();       // 장애 중에 기한이 지남
		long r2 = hold.hold(newMember(), cmd(seatIds.get(131))).reservationId(); // 장애 전에 이미 기한이 지남
		long r3 = hold.hold(newMember(), cmd(seatIds.get(132))).reservationId(); // 결제 중
		setDeadline(r1, -5);
		redis.delete(SeatKeys.hold(RUN, s1, 0)); // 장애 동안 TTL로 사라짐
		setDeadline(r2, -30);
		setDeadline(r3, -5);
		jdbc.update("UPDATE reservation SET status = 'PAYMENT_PENDING', updated_at = SYSTIMESTAMP, version = version + 1 WHERE reservation_id = ?", r3);
		java.time.LocalDateTime r2Before = deadlineOf(r2);
		java.time.LocalDateTime r3Before = deadlineOf(r3);

		// 장애 동안: 스캐너가 돌아도 만료시키지 않는다
		guard.markDown(now.minusMinutes(15));
		scanner.tick();
		assertThat(status(r1)).isEqualTo("HOLD");

		// 복구: 15분 장애 → 최대 10분까지만 늘림 → r1 기한 = 5분 전 + 10분 = 지금부터 약 5분
		assertThat(guard.extendAfterOutage(now.minusMinutes(15), now)).isEqualTo(1);
		java.time.LocalDateTime d = deadlineOf(r1);
		assertThat(java.time.Duration.between(now, d).toSeconds()).isBetween(4 * 60L + 50, 5 * 60L + 10);
		assertThat(count("SELECT COUNT(*) FROM seat_inventory s JOIN reservation x ON x.reservation_id = s.reservation_id"
				+ " WHERE x.reservation_id = ? AND s.hold_expires_at = x.pay_deadline_at", r1)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM saga_instance g JOIN reservation x ON x.reservation_id = g.reservation_id"
				+ " WHERE x.reservation_id = ? AND g.timeout_at = x.pay_deadline_at", r1)).isEqualTo(1);
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s1, 0))).isEqualTo(Long.toString(r1)); // 사라진 키를 다시 걸었다
		long grace = policy.getLong(AppPolicy.HOLD_RELEASE_GRACE_SEC);
		assertThat(redis.getExpire(SeatKeys.hold(RUN, s1, 0))).isBetween(4 * 60L + 40 + grace, 5 * 60L + 10 + grace);
		assertThat(deadlineOf(r2)).isEqualTo(r2Before);   // 장애 전에 이미 지난 것은 그대로
		assertThat(deadlineOf(r3)).isEqualTo(r3Before);   // 결제 중은 §6.2가 맡음

		assertThat(guard.extendAfterOutage(now.minusMinutes(15), now)).isZero(); // 같은 장애로 두 번 늘리지 않는다
		assertThat(deadlineOf(r1)).isEqualTo(d);
		// 이제 결제할 수 있는 기한 안이라 스캐너가 돌아도 만료되지 않는다
		assertThat(scanner.scanOnce()).isEqualTo(1);       // r2만 (장애 전에 지난 것)
		assertThat(status(r1)).isEqualTo("HOLD");
		assertThat(status(r2)).isEqualTo("EXPIRED");
	}

	private void setDeadline(long r, int minutesFromNow) {
		jdbc.update("UPDATE reservation SET pay_deadline_at = SYSTIMESTAMP + NUMTODSINTERVAL(?, 'MINUTE') WHERE reservation_id = ?",
				minutesFromNow, r);
		jdbc.update("UPDATE seat_inventory SET hold_expires_at = (SELECT pay_deadline_at FROM reservation WHERE reservation_id = ?) WHERE reservation_id = ?",
				r, r);
		jdbc.update("UPDATE saga_instance SET timeout_at = (SELECT pay_deadline_at FROM reservation WHERE reservation_id = ?) WHERE reservation_id = ?",
				r, r);
	}

	private java.time.LocalDateTime deadlineOf(long r) {
		return jdbc.queryForObject("SELECT pay_deadline_at FROM reservation WHERE reservation_id = ?", java.time.LocalDateTime.class, r);
	}

	private long dayCounterRef(long memberId) {
		return jdbc.queryForObject("SELECT scope_ref_id FROM purchase_counter WHERE scope = 'PER_DAY' AND ci_hash = ?",
				Long.class, ci(memberId));
	}

	private long dayCount(long memberId, long yyyymmdd) {
		List<Long> v = jdbc.queryForList(
				"SELECT seat_count FROM purchase_counter WHERE scope = 'PER_DAY' AND scope_ref_id = ? AND ci_hash = ?",
				Long.class, yyyymmdd, ci(memberId));
		return v.isEmpty() ? 0 : v.get(0);
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
