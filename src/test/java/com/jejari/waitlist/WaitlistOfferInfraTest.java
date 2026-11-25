package com.jejari.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.jejari.common.error.BusinessException;
import com.jejari.seat.SeatHoldService;
import com.jejari.seat.SeatHoldService.HoldCommand;
import com.jejari.seat.SeatHoldService.SeatRequest;
import com.jejari.seat.SeatKeys;
import com.jejari.seat.SeatReleaseService;
import com.jejari.support.NoRunningApp;
import com.jejari.waitlist.offer.WaitlistOfferTx;

/**
 * WL-03 취소표 제안을 실제 Oracle·Redis로 확인한다 (concurrency.md §9.1).
 *
 * <p>신청·조회·취소(WL-01·02)는 {@link WaitlistInfraTest}(강다인)가 덮는다. 여기서는
 * <b>풀린 좌석이 대기 순서대로 넘어가는지</b>만 본다.
 *
 * <p>Kafka 소비자({@code SeatReleaseConsumer})는 띄우지 않고 그 안의 트랜잭션
 * {@link WaitlistOfferTx#offer}를 직접 부른다. 메시지가 언제 오는지에 기대면 테스트가 흔들린다.
 *
 * <p>운행 9001은 1,050석이라 매진을 만들 수 없어 대기 행을 직접 넣는다.
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
		// 이 테스트는 소비자가 할 일을 직접 부른다. 켜 두면 배경의 소비자가 Kafka로 이벤트를 먼저 받아
		// 대기자에게 제안해 버려서, 내가 부를 때는 WAITING인 대기자가 남지 않는다
		"jejari.waitlist.offer-enabled=false"})
class WaitlistOfferInfraTest {

	static final long RUN = 9001;
	static final String SEOUL = "3900023";
	static final String BUSAN = "3900114";
	static final String DAEJEON = "3900073";
	static final long MEMBER_BASE = 930000;
	static final AtomicLong NEXT_MEMBER = new AtomicLong(MEMBER_BASE + 1);

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

	List<Long> seatIds;

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
	}

	@AfterEach
	void tearDown() {
		cleanDb();
	}

	// ─────────────────────────────── WL-03 제안 ───────────────────────────────

	@Test
	void 풀린_좌석은_대기_순서대로_제안되고_예약이_생긴다() {
		long seat = seatIds.get(300);
		long waiter = newMember();
		long w = insertWaiting(waiter, SEOUL, BUSAN, "GENERAL", 1, 0);
		long freed = freeSeat(seat);   // 누가 잡았다가 취소 → 좌석 키가 REOFFER:freed

		List<WaitlistOfferTx.Offered> offered = tx.execute(st ->
				offerTx.offer(RUN, SEOUL, BUSAN, List.of(seat), freed));

		assertThat(offered).hasSize(1);
		long r2 = offered.get(0).reservationId();

		// 대기는 OFFERED가 되고 제안 예약을 가리킨다
		assertThat(jdbc.queryForObject("SELECT status FROM waitlist WHERE waitlist_id = ?", String.class, w))
				.isEqualTo("OFFERED");
		assertThat(jdbc.queryForObject("SELECT offered_reservation_id FROM waitlist WHERE waitlist_id = ?",
				Long.class, w)).isEqualTo(r2);

		// 제안 예약은 WAITLIST 출처이고 멱등 키가 wl:{대기ID}다 (§9.1-6)
		assertThat(jdbc.queryForObject("SELECT source FROM reservation WHERE reservation_id = ?", String.class, r2))
				.isEqualTo("WAITLIST");
		assertThat(jdbc.queryForObject("SELECT idempotency_key FROM reservation WHERE reservation_id = ?",
				String.class, r2)).isEqualTo("wl:" + w);
		assertThat(jdbc.queryForObject("SELECT member_id FROM reservation WHERE reservation_id = ?", Long.class, r2))
				.isEqualTo(waiter);

		// 좌석 키가 대기자 예약으로 넘어갔다 (REOFFER 인계)
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, seat, 0))).isEqualTo(Long.toString(r2));

		// 이력 주체는 사람이 아니라 시스템
		assertThat(jdbc.queryForObject(
				"SELECT actor_type FROM reservation_history WHERE reservation_id = ? AND to_status = 'HOLD'",
				String.class, r2)).isEqualTo("SYSTEM");

		// 제안 알림이 같은 트랜잭션에 남았다 (WL-04)
		assertThat(count("SELECT COUNT(*) FROM notification WHERE member_id = ? AND type = 'WAITLIST_OFFER'", waiter))
				.isEqualTo(1);
	}

	@Test
	void 구간이_다른_대기자에게는_제안하지_않는다() {
		long seat = seatIds.get(310);
		long other = newMember();
		insertWaiting(other, SEOUL, DAEJEON, "GENERAL", 1, 0);   // 서울 → 대전
		long freed = freeSeat(seat);                              // 비는 좌석은 서울 → 부산

		List<WaitlistOfferTx.Offered> offered = tx.execute(st ->
				offerTx.offer(RUN, SEOUL, BUSAN, List.of(seat), freed));

		assertThat(offered).isEmpty();
		assertThat(jdbc.queryForObject("SELECT status FROM waitlist WHERE member_id = ?", String.class, other))
				.isEqualTo("WAITING");   // 건너뛴 것이지 SKIPPED가 아니다
	}

	@Test
	void 등급이_다르거나_매수가_모자라면_제안하지_않는다() {
		long seat = seatIds.get(320);
		long firstClass = newMember();
		long tooMany = newMember();
		insertWaiting(firstClass, SEOUL, BUSAN, "FIRST", 1, 0);    // 특실 대기
		insertWaiting(tooMany, SEOUL, BUSAN, "GENERAL", 2, 0);     // 2매인데 1석만 빔
		long freed = freeSeat(seat);

		List<WaitlistOfferTx.Offered> offered = tx.execute(st ->
				offerTx.offer(RUN, SEOUL, BUSAN, List.of(seat), freed));
		assertThat(offered).isEmpty();
	}

	@Test
	void 앞사람이_먼저_제안받고_좌석이_떨어지면_뒷사람은_그대로_기다린다() {
		long seat = seatIds.get(330);
		long first = newMember();
		long second = newMember();
		long w1 = insertWaiting(first, SEOUL, BUSAN, "GENERAL", 1, 0);
		long w2 = insertWaiting(second, SEOUL, BUSAN, "GENERAL", 1, 60_000L);
		long freed = freeSeat(seat);

		List<WaitlistOfferTx.Offered> offered = tx.execute(st ->
				offerTx.offer(RUN, SEOUL, BUSAN, List.of(seat), freed));

		assertThat(offered).hasSize(1);
		assertThat(offered.get(0).waitlistId()).isEqualTo(w1);
		assertThat(jdbc.queryForObject("SELECT status FROM waitlist WHERE waitlist_id = ?", String.class, w2))
				.isEqualTo("WAITING");
	}

	/** T16 (§9.1-5): 앞 대기자가 열차당 한도에 이미 차 있으면 SKIPPED, 좌석은 다음 대기자에게 */
	@Test
	void T16_앞_대기자가_매수_한도를_넘으면_SKIPPED가_되고_다음_대기자가_받는다() {
		long full = newMember();
		long next = newMember();
		// full은 이 열차에서 이미 10매(열차당 한도)를 잡고 있다: 9매 + 1매
		hold.hold(full, new HoldCommand(RUN, SEOUL, BUSAN, seatsOf(340, 9), "WEB", UUID.randomUUID().toString()));
		hold.hold(full, new HoldCommand(RUN, SEOUL, BUSAN, seatsOf(349, 1), "WEB", UUID.randomUUID().toString()));
		long wFull = insertWaiting(full, SEOUL, BUSAN, "GENERAL", 1, 0);
		long wNext = insertWaiting(next, SEOUL, BUSAN, "GENERAL", 1, 60_000L);   // 뒤 순번
		long seat = seatIds.get(360);
		long freed = freeSeat(seat);

		List<WaitlistOfferTx.Offered> offered = tx.execute(st ->
				offerTx.offer(RUN, SEOUL, BUSAN, List.of(seat), freed));

		assertThat(jdbc.queryForObject("SELECT status FROM waitlist WHERE waitlist_id = ?", String.class, wFull))
				.isEqualTo("SKIPPED");
		assertThat(offered).hasSize(1);
		assertThat(offered.get(0).waitlistId()).isEqualTo(wNext);
		assertThat(jdbc.queryForObject("SELECT status FROM waitlist WHERE waitlist_id = ?", String.class, wNext))
				.isEqualTo("OFFERED");
		assertThat(count("SELECT COUNT(*) FROM notification WHERE member_id = ? AND type = 'WAITLIST_SKIPPED'", full))
				.isEqualTo(1);
	}

	// ─────────────────────────── §14.3 보장 경로 (T26) ───────────────────────────

	/**
	 * T26 (§14.3): 취소가 커밋된 직후 앱이 죽어 빠른 경로(커밋 직후 Redis 보상)가 유실됐다.
	 * 그대로면 좌석 키가 이전 예약 값으로 남아 출발까지 그 좌석을 못 팔고, 한도도 남아 그 사람이 막힌다.
	 * seat-release 소비자가 같은 트랜잭션의 outbox 이벤트를 받아 하는 1단계({@code compensateAfterRelease})가
	 * 같은 일을 다시 해 준다 — 좌석 키는 REOFFER:R(대기자 몫), 한도는 예약당 한 번만 감소.
	 */
	@Test
	void T26_취소_커밋_직후_빠른_경로가_유실돼도_보장_경로가_좌석_키와_한도를_되돌린다() {
		long seat = seatIds.get(340);
		long m = newMember();
		long other = newMember();
		long r = hold.hold(m, new HoldCommand(RUN, SEOUL, BUSAN, List.of(new SeatRequest(seat, "ADULT")), "WEB",
				UUID.randomUUID().toString())).reservationId();
		String seatKey = SeatKeys.hold(RUN, seat, 0);
		String limitKey = SeatKeys.limitRun(RUN, ciHash(m));
		assertThat(redis.opsForValue().get(limitKey)).isEqualTo("1");

		release.cancelBeforePay(m, r);   // DB 커밋 + 빠른 경로
		assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE reservation_id = ?", String.class, r))
				.isEqualTo("CANCELED");
		// 같은 트랜잭션에 보장 경로의 재료(빈 좌석 이벤트)가 남았다 (§0.5)
		assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'RESERVATION_CANCELED'",
				Long.toString(r))).isEqualTo(1);

		// 앱이 커밋 직후 죽어 빠른 경로가 실행되지 않은 상태로 되돌린다 (보상 유실 흉내)
		redis.opsForValue().set(seatKey, Long.toString(r), java.time.Duration.ofMinutes(10));
		redis.opsForValue().set(limitKey, "1");
		redis.delete(SeatKeys.limitDec(r));

		// 피해: 취소된 좌석인데 다른 사람이 못 잡는다 (hold.lua가 이전 예약 값을 보고 거절)
		assertThatThrownBy(() -> hold.hold(other, new HoldCommand(RUN, SEOUL, BUSAN,
				List.of(new SeatRequest(seat, "ADULT")), "WEB", UUID.randomUUID().toString())))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode().code()).isEqualTo("SEAT_ALREADY_TAKEN");

		// 보장 경로: seat-release 소비자의 1단계 (값은 이벤트가 아니라 Oracle에서 다시 읽는다)
		release.compensateAfterRelease(r);
		assertThat(redis.opsForValue().get(seatKey)).isEqualTo(SeatKeys.reoffer(r)); // 대기자 몫으로 (§9가 넘기거나 푼다)
		assertThat(redis.opsForValue().get(limitKey)).isEqualTo("0");

		// 평소에는 빠른 경로와 보장 경로가 둘 다 돈다 → 두 번 돌려도 한 번만 감소 (limit:dec 표시)
		release.compensateAfterRelease(r);
		assertThat(redis.opsForValue().get(limitKey)).isEqualTo("0");
		assertThat(redis.opsForValue().get(seatKey)).isEqualTo(SeatKeys.reoffer(r));
	}

	// ─────────────────────── §9.1-9 남은 REOFFER 풀기 (T43) ───────────────────────

	/** T43: 대기자가 없으면 REOFFER:R을 바로 지워 일반 판매로. 예전에는 reoffer_hold_sec(60초) 동안 아무도 못 샀다 */
	@Test
	void T43_대기자가_없으면_REOFFER를_바로_풀어_다른_사람이_살_수_있다() {
		long seat = seatIds.get(350);
		long freed = freeSeat(seat);   // 좌석 키 REOFFER:freed
		List<WaitlistOfferTx.Offered> offered = tx.execute(st -> offerTx.offer(RUN, SEOUL, BUSAN, List.of(seat), freed));
		assertThat(offered).isEmpty();

		assertThat(release.releaseUnofferedReoffer(freed, RUN, List.of(seat))).isEqualTo(1);
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, seat, 0))).isNull();

		long other = newMember();
		long r = hold.hold(other, new HoldCommand(RUN, SEOUL, BUSAN, List.of(new SeatRequest(seat, "ADULT")), "WEB",
				UUID.randomUUID().toString())).reservationId();
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, seat, 0))).isEqualTo(Long.toString(r));
	}

	/** T43·T30 뒷부분: 두 좌석이 풀리고 대기자는 한 석만 원함 → 넘긴 좌석은 그대로(R'), 남은 좌석만 일반 판매. 두 번 해도 같다 */
	@Test
	void T43_대기자에게_넘긴_좌석은_두고_남은_좌석만_일반_판매로_돌린다() {
		long s1 = seatIds.get(361);
		long s2 = seatIds.get(362);
		long waiter = newMember();
		insertWaiting(waiter, SEOUL, BUSAN, "GENERAL", 1, 0);
		long m = newMember();
		long freed = hold.hold(m, new HoldCommand(RUN, SEOUL, BUSAN,
				List.of(new SeatRequest(s1, "ADULT"), new SeatRequest(s2, "ADULT")), "WEB", UUID.randomUUID().toString()))
				.reservationId();
		release.cancelBeforePay(m, freed);

		List<WaitlistOfferTx.Offered> offered = tx.execute(st -> offerTx.offer(RUN, SEOUL, BUSAN, List.of(s1, s2), freed));
		assertThat(offered).hasSize(1);
		long given = offered.get(0).seatIds().get(0);
		long left = given == s1 ? s2 : s1;

		assertThat(release.releaseUnofferedReoffer(freed, RUN, List.of(s1, s2))).isEqualTo(1);
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, given, 0)))
				.isEqualTo(Long.toString(offered.get(0).reservationId())); // 대기자 몫은 그대로
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, left, 0))).isNull();
		assertThat(release.releaseUnofferedReoffer(freed, RUN, List.of(s1, s2))).isZero(); // 다시 와도 같다
	}

	/**
	 * T17 (§9): 앱 두 대가 같은 "좌석 풀림" 이벤트를 동시에 처리한다. 대기 행을 FOR UPDATE SKIP LOCKED로 잡으므로
	 * 한 대만 제안하고, 다른 대는 잠긴 대기자를 건너뛰어(또는 이미 OFFERED라) 아무것도 하지 않는다 → 제안 예약 1건.
	 */
	@Test
	void T17_두_대가_같은_빈_좌석_이벤트를_동시에_처리해도_대기자에게_한_번만_제안한다() throws Exception {
		long seat = seatIds.get(400);
		long waiter = newMember();
		long w = insertWaiting(waiter, SEOUL, BUSAN, "GENERAL", 1, 0);
		long freed = freeSeat(seat);

		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
		for (int i = 0; i < 2; i++) {
			results.add(pool.submit(() -> {
				start.await();
				return tx.execute(st -> offerTx.offer(RUN, SEOUL, BUSAN, List.of(seat), freed)).size();
			}));
		}
		start.countDown();
		int total = 0;
		for (var f : results) {
			total += f.get(30, java.util.concurrent.TimeUnit.SECONDS);
		}
		pool.shutdown();

		assertThat(total).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM reservation WHERE idempotency_key = ?", "wl:" + w)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM reservation WHERE member_id = ?", waiter)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM notification WHERE member_id = ? AND type = 'WAITLIST_OFFER'", waiter))
				.isEqualTo(1);
		long r2 = jdbc.queryForObject("SELECT offered_reservation_id FROM waitlist WHERE waitlist_id = ?", Long.class, w);
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, seat, 0))).isEqualTo(Long.toString(r2));
	}

	/**
	 * T35 (§1·§9): 같은 이벤트가 다시 와도(재전달) 대기자는 이미 OFFERED라 다시 제안하지 않는다. 상태가 어떻게든 WAITING으로
	 * 돌아가 다시 제안하려 해도, 제안 예약의 멱등 키 {@code wl:{대기ID}}가 UNIQUE(member_id, idempotency_key)라 DB가 막는다.
	 */
	@Test
	void T35_같은_대기자에게_이벤트가_다시_와도_제안_예약은_한_건이다() {
		long s1 = seatIds.get(410);
		long s2 = seatIds.get(411);
		long waiter = newMember();
		long w = insertWaiting(waiter, SEOUL, BUSAN, "GENERAL", 1, 0);
		long freed = freeSeat(s1);

		List<WaitlistOfferTx.Offered> first = tx.execute(st -> offerTx.offer(RUN, SEOUL, BUSAN, List.of(s1), freed));
		List<WaitlistOfferTx.Offered> again = tx.execute(st -> offerTx.offer(RUN, SEOUL, BUSAN, List.of(s1), freed)); // 재전달
		assertThat(first).hasSize(1);
		assertThat(again).isEmpty();
		assertThat(count("SELECT COUNT(*) FROM reservation WHERE idempotency_key = ?", "wl:" + w)).isEqualTo(1);

		// 마지막 방어선: 대기 행이 WAITING으로 되돌아간 채 다른 좌석 이벤트가 와도 같은 wl: 키라 두 번째 예약은 안 생긴다
		jdbc.update("UPDATE waitlist SET status = 'WAITING' WHERE waitlist_id = ?", w);
		long freed2 = freeSeat(s2);
		assertThatThrownBy(() -> tx.execute(st -> offerTx.offer(RUN, SEOUL, BUSAN, List.of(s2), freed2)))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThat(count("SELECT COUNT(*) FROM reservation WHERE idempotency_key = ?", "wl:" + w)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM reservation WHERE member_id = ?", waiter)).isEqualTo(1);
	}

	/**
	 * T38 (§9.3): 위험 점수 80인 A가 10:00에 신청(패널티 4시간 → priority_at 14:00), 정상 B가 10:30에 신청.
	 * 좌석 1개가 비면 priority_at이 이른 B에게 먼저 제안하고 A는 WAITING으로 남는다. (시각은 지금 기준 상대값)
	 */
	@Test
	void T38_패널티로_뒤로_밀린_A보다_나중에_신청한_정상_B가_먼저_제안받는다() {
		long seat = seatIds.get(420);
		long a = newMember();
		long b = newMember();
		long wa = insertWaitingAt(a, 300, 4 * 60);  // 5시간 전 신청 + 4시간 → priority = 1시간 전
		long wb = insertWaitingAt(b, 270, 0);       // 4시간 30분 전 신청, 패널티 없음
		long freed = freeSeat(seat);

		List<WaitlistOfferTx.Offered> offered = tx.execute(st -> offerTx.offer(RUN, SEOUL, BUSAN, List.of(seat), freed));

		assertThat(offered).hasSize(1);
		assertThat(offered.get(0).waitlistId()).isEqualTo(wb);
		assertThat(statusOf(wb)).isEqualTo("OFFERED");
		assertThat(statusOf(wa)).isEqualTo("WAITING");
	}

	/** T39 (§9.3): 같은 조건에서 B가 A의 priority_at(14:00)이 지난 14:30에 신청 → A에게 먼저 제안 */
	@Test
	void T39_A의_priority_at이_지난_뒤_신청한_B보다_A가_먼저_제안받는다() {
		long seat = seatIds.get(430);
		long a = newMember();
		long b = newMember();
		long wa = insertWaitingAt(a, 300, 4 * 60);  // priority = 1시간 전
		long wb = insertWaitingAt(b, 30, 0);        // 30분 전 신청 (A의 priority_at 뒤)
		long freed = freeSeat(seat);

		List<WaitlistOfferTx.Offered> offered = tx.execute(st -> offerTx.offer(RUN, SEOUL, BUSAN, List.of(seat), freed));

		assertThat(offered).hasSize(1);
		assertThat(offered.get(0).waitlistId()).isEqualTo(wa);
		assertThat(statusOf(wa)).isEqualTo("OFFERED");
		assertThat(statusOf(wb)).isEqualTo("WAITING");
	}

	// ─────────────────────────────── 도우미 ───────────────────────────────

	/** 신청 시각을 minutesAgo분 전으로, priority_at = 신청 + penaltyMin분 (§9.3) */
	private long insertWaitingAt(long memberId, int minutesAgo, int penaltyMin) {
		long id = jdbc.queryForObject("SELECT waitlist_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO waitlist (waitlist_id, member_id, run_id, from_station_id, to_station_id, seat_grade,
				       seat_count, requested_at, priority_at, penalty_ms, status)
				VALUES (?, ?, ?, ?, ?, 'GENERAL', 1, SYSTIMESTAMP - NUMTODSINTERVAL(?, 'MINUTE'),
				        SYSTIMESTAMP - NUMTODSINTERVAL(?, 'MINUTE'), ?, 'WAITING')""",
				id, memberId, RUN, SEOUL, BUSAN, minutesAgo, minutesAgo - penaltyMin, penaltyMin * 60_000L);
		return id;
	}

	private String statusOf(long waitlistId) {
		return jdbc.queryForObject("SELECT status FROM waitlist WHERE waitlist_id = ?", String.class, waitlistId);
	}

	private List<SeatRequest> seatsOf(int from, int n) {
		return seatIds.subList(from, from + n).stream().map(s -> new SeatRequest(s, "ADULT")).toList();
	}

	private long newMember() {
		long id = NEXT_MEMBER.getAndIncrement();
		// ci_hash는 64자 16진수여야 한다 (OracleMemberIdentityPort가 형식을 검사한다)
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
				id, "wl" + id + "@infra-test.jejari.dev", ciHash(id), "테스트");
		return id;
	}

	/** 테스트용 CI 해시. OracleMemberIdentityPort가 ^[0-9a-f]{64}$ 를 요구한다 */
	private static String ciHash(long memberId) {
		return String.format("%064x", memberId);
	}

	/** 대기 행을 직접 넣는다 (신청 API는 매진이어야 통과하는데 9001은 1,050석이라 매진을 만들 수 없다) */
	private long insertWaiting(long memberId, String from, String to, String grade, int seatCount, long penaltyMs) {
		long id = jdbc.queryForObject("SELECT waitlist_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO waitlist (waitlist_id, member_id, run_id, from_station_id, to_station_id, seat_grade,
				       seat_count, requested_at, priority_at, penalty_ms, status)
				VALUES (?, ?, ?, ?, ?, ?, ?, SYSTIMESTAMP,
				        SYSTIMESTAMP + NUMTODSINTERVAL(? / 1000, 'SECOND'), ?, 'WAITING')""",
				id, memberId, RUN, from, to, grade, seatCount, penaltyMs, penaltyMs);
		return id;
	}

	/** 좌석 하나를 잡았다가 결제 전 취소 → 좌석 키가 REOFFER:{예약}이 된다 (§8.2) */
	private long freeSeat(long seatId) {
		long m = newMember();
		long r = hold.hold(m, new HoldCommand(RUN, SEOUL, BUSAN,
				List.of(new SeatRequest(seatId, "ADULT")), "WEB", UUID.randomUUID().toString())).reservationId();
		release.cancelBeforePay(m, r);
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, seatId, 0))).isEqualTo(SeatKeys.reoffer(r));
		return r;
	}

	private long count(String sql, Object... args) {
		Long v = jdbc.queryForObject(sql, Long.class, args);
		return v == null ? 0 : v;
	}

	private void cleanDb() {
		String mine = "SELECT reservation_id FROM reservation WHERE member_id BETWEEN " + (MEMBER_BASE + 1)
				+ " AND " + (MEMBER_BASE + 9999);
		String members = "SELECT member_id FROM member WHERE member_id BETWEEN " + (MEMBER_BASE + 1)
				+ " AND " + (MEMBER_BASE + 9999);
		jdbc.update("DELETE FROM notification WHERE member_id IN (" + members + ")");
		jdbc.update("DELETE FROM waitlist WHERE member_id IN (" + members + ")");
		jdbc.update("DELETE FROM outbox_event WHERE aggregate_type = 'RESERVATION' AND aggregate_id IN ("
				+ "SELECT TO_CHAR(reservation_id) FROM reservation WHERE member_id BETWEEN ? AND ?)",
				MEMBER_BASE + 1, MEMBER_BASE + 9999);
		jdbc.update("DELETE FROM reservation_history WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM saga_instance WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation_seat WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
		// 해시에 표시를 넣을 수 없어(16진수만 허용) 회원 번호로 만든 해시 목록으로 지운다
		for (long id = MEMBER_BASE + 1; id < NEXT_MEMBER.get(); id++) {
			jdbc.update("DELETE FROM purchase_counter WHERE ci_hash = ?", ciHash(id));
		}
		jdbc.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
	}
}
