package com.jejari.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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

import com.jejari.common.config.AppPolicy;
import com.jejari.common.error.BusinessException;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.group.GroupAllocator.Outcome;
import com.jejari.group.GroupAllocator.Result;
import com.jejari.group.GroupApplyService.ApplyCommand;
import com.jejari.group.GroupApplyService.ApplyResult;
import com.jejari.group.GroupApplyService.PassengerInput;
import com.jejari.queue.redis.QueueKeys;
import com.jejari.seat.SeatKeys;
import com.jejari.support.NoRunningApp;

/**
 * 단체 신청·승인 (concurrency.md §18.3, §18.3a, §18.3b)을 실제 Oracle·Redis로 확인한다.
 *
 * <ul>
 * <li>G3: 같은 단체 동시 승인 2건 → 예약 1건</li>
 * <li>G4: 두 단체가 같은 할당석 → 좌석 중복 없음</li>
 * <li>G5: 할당석 + 일반 좌석, 행 수 = 인원, 개인 카운터 0</li>
 * <li>G5c: 대기자에게 제안 중인 좌석(REOFFER)은 고르지 않음</li>
 * <li>G5d: 대기열에 사람이 있으면 일반 좌석이 필요한 신청은 미룸 → 비면 스캐너가 승인</li>
 * <li>G5e: 할당석만으로 되면 대기열과 상관없이 승인</li>
 * </ul>
 * 할당석은 GroupQuotaScheduler(좌석 패키지) 대신 BLOCKED 행 + GROUP 키를 직접 만든다 (§18.1과 같은 모양).
 */
@Tag("infra")
@ExtendWith(NoRunningApp.class)
@SpringBootTest(properties = {
		"spring.data.redis.database=15",
		"jejari.queue.scheduler-enabled=false",
		"jejari.risk.cache-resync-enabled=false",
		"jejari.risk.batch-enabled=false",
		"jejari.seat.expiry-scanner-enabled=false",
		"jejari.seat.group-quota-scheduler-enabled=false",
		"jejari.group.retry-scanner-enabled=false",
		"jejari.train.sale-open-scheduler-enabled=false"})
class GroupBookingInfraTest {

	static final long RUN = 9001; // 출발 45일 뒤, ON_SALE (db/seed/04)
	static final long TRAIN = 1;
	static final String FROM = "3900023";
	static final String TO = "3900114";
	static final long MEMBER_BASE = 986000;
	static final AtomicLong NEXT = new AtomicLong(MEMBER_BASE + 1);
	static final AtomicLong PHONE = new AtomicLong(10_000_000L + System.nanoTime() % 80_000_000L);

	@Autowired
	GroupAllocator allocator;
	@Autowired
	GroupApplyService apply;
	@Autowired
	AppPolicy policy;
	@Autowired
	Pseudonymizer pseudonymizer;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	Clock clock;

	GroupRetryScanner scanner;

	@BeforeEach
	void setUp() {
		cleanDb();
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		scanner = new GroupRetryScanner(jdbc, allocator, policy);
	}

	@AfterEach
	void tearDown() {
		cleanDb();
	}

	// ------------------------------------------------------------ 신청

	@Test
	void 신청하면_명단을_암호화해_저장하고_할당석으로_바로_자동_승인한다() {
		makeQuota(20);
		Rep rep = newRep();

		ApplyResult r = apply.apply(rep.memberId(), command(rep, 11, 0));

		assertThat(r.outcome()).isEqualTo(Outcome.APPROVED);
		assertThat(r.status()).isEqualTo("APPROVED");
		assertThat(r.discount()).as("출발 45일 전 신청이라 할인 없음").isFalse();
		long res = r.reservationId();
		assertThat(str("SELECT source FROM reservation WHERE reservation_id = ?", res)).isEqualTo("GROUP");
		assertThat(str("SELECT status FROM reservation WHERE reservation_id = ?", res)).isEqualTo("HOLD");
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ? AND status = 'HOLD'", res)).isEqualTo(11);
		assertThat(count("SELECT COUNT(*) FROM group_passenger WHERE group_id = ? AND seat_id IS NOT NULL", r.groupId())).isEqualTo(11);
		assertThat(count("SELECT COUNT(*) FROM purchase_counter WHERE ci_hash = ?", rep.ciHash())).as("개인 20매 카운터 안 씀").isZero();
		assertThat(str("SELECT name_enc FROM group_passenger WHERE group_id = ? AND passenger_no = 1", r.groupId()))
				.as("이름은 암호문").doesNotContain("대표");
		// 결제 기한 = 지금 + 3일, 좌석 키는 예약 ID
		assertThat(r.payDeadlineAt()).isAfter(java.time.LocalDateTime.now(clock).plusDays(2));
		long seat = jdbc.queryForObject("SELECT MIN(seat_id) FROM seat_inventory WHERE reservation_id = ?", Long.class, res);
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, seat, 0))).isEqualTo(Long.toString(res));
		assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'SEAT_HELD'",
				Long.toString(res))).isEqualTo(1);
	}

	@Test
	void 대표자_번호가_본인인증_번호와_다르면_거절() {
		Rep rep = newRep();
		ApplyCommand cmd = command(new Rep(rep.memberId(), rep.ciHash(), "01099998888"), 11, 0);

		assertThatThrownBy(() -> apply.apply(rep.memberId(), cmd)).isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(GroupErrorCode.GROUP_REP_PHONE_MISMATCH);
		assertThat(count("SELECT COUNT(*) FROM group_booking WHERE rep_member_id = ?", rep.memberId())).isZero();
	}

	@Test
	void 휴대폰_없음이_30퍼센트를_넘거나_다른_단체와_번호가_겹치면_경고만_붙인다() {
		makeQuota(40);
		Rep a = newRep();
		ApplyCommand first = command(a, 11, 4); // 4/11 = 36%
		ApplyResult ra = apply.apply(a.memberId(), first);
		assertThat(ra.warnNoPhone()).isTrue();
		assertThat(ra.warnDupPhone()).isFalse();

		Rep b = newRep();
		List<PassengerInput> pax = new ArrayList<>(command(b, 11, 0).passengers());
		pax.set(1, first.passengers().get(5)); // a 단체의 탑승자 번호(휴대폰 있음)를 그대로 씀
		ApplyResult rb = apply.apply(b.memberId(), new ApplyCommand(RUN, FROM, TO, true, pax));

		assertThat(rb.warnDupPhone()).isTrue();
		assertThat(rb.outcome()).as("경고는 막지 않음").isEqualTo(Outcome.APPROVED);
	}

	// ------------------------------------------------------------ 승인 (§18.3, §18.3a)

	@Test
	void G5_할당석이_모자라면_일반_좌석에서_이어_받는다() {
		makeQuota(5);
		long g = request(newRep(), 11);

		Result r = allocator.approve(g, null, false, false);

		assertThat(r.outcome()).isEqualTo(Outcome.APPROVED);
		long res = r.reservationId();
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ?", res)).as("K = 인원").isEqualTo(11);
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE run_id = ? AND status = 'BLOCKED'", RUN)).as("할당석 전부 사용").isZero();
		assertThat(count("SELECT COUNT(*) FROM reservation_seat WHERE reservation_id = ?", res)).isEqualTo(11);
		assertThat(str("SELECT status FROM group_booking WHERE group_id = ?", g)).isEqualTo("APPROVED");
	}

	@Test
	void G5d_대기열에_사람이_있으면_미루고_비면_스캐너가_승인한다() {
		makeQuota(5);
		redis.opsForZSet().add(QueueKeys.queue(RUN), "someone", 1);
		long g = request(newRep(), 11);

		assertThat(allocator.approve(g, null, false, false).outcome()).isEqualTo(Outcome.DEFERRED);
		assertThat(str("SELECT status FROM group_booking WHERE group_id = ?", g)).isEqualTo("REQUESTED");
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE run_id = ? AND status = 'HOLD'", RUN)).as("일반 좌석 변화 없음").isZero();
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE run_id = ? AND status = 'BLOCKED'", RUN)).isEqualTo(5);

		// 입장해 좌석을 고르는 사람이 있어도 미룸
		redis.delete(QueueKeys.queue(RUN));
		redis.opsForZSet().add(QueueKeys.active(RUN), "someone", clock.millis() + 60_000);
		assertThat(scanner.retry()).isZero();

		redis.delete(QueueKeys.active(RUN));
		assertThat(scanner.retry()).isEqualTo(1);
		assertThat(str("SELECT status FROM group_booking WHERE group_id = ?", g)).isEqualTo("APPROVED");
	}

	@Test
	void G5e_할당석만으로_되면_대기열과_상관없이_승인하고_앞_신청이_미뤄져도_뒤는_할당석으로() {
		makeQuota(15);
		redis.opsForZSet().add(QueueKeys.queue(RUN), "someone", 1);
		long big = request(newRep(), 20);   // 앞: 할당석 15 < 20 → 미룸
		long small = request(newRep(), 11); // 뒤: 할당석으로 됨

		assertThat(scanner.retry()).isEqualTo(1);

		assertThat(str("SELECT status FROM group_booking WHERE group_id = ?", big)).isEqualTo("REQUESTED");
		assertThat(str("SELECT status FROM group_booking WHERE group_id = ?", small)).isEqualTo("APPROVED");
		long res = jdbc.queryForObject("SELECT reservation_id FROM group_booking WHERE group_id = ?", Long.class, small);
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE run_id = ? AND status = 'HOLD' AND reservation_id <> ?", RUN, res))
				.as("일반 좌석 안 씀").isZero();
	}

	@Test
	void G5c_대기자에게_제안_중인_좌석은_고르지_않는다() {
		long first = jdbc.queryForObject("""
				SELECT seat_id FROM seat WHERE train_id = ? AND seat_grade = 'GENERAL' ORDER BY car_no, seat_id
				 FETCH FIRST 1 ROWS ONLY""", Long.class, TRAIN);
		redis.opsForValue().set(SeatKeys.hold(RUN, first, 0), SeatKeys.reoffer(777), Duration.ofMinutes(1));
		long g = request(newRep(), 11);

		Result r = allocator.approve(g, null, false, false);

		assertThat(r.outcome()).isEqualTo(Outcome.APPROVED);
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ? AND seat_id = ?", r.reservationId(), first)).isZero();
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, first, 0))).isEqualTo(SeatKeys.reoffer(777));
	}

	@Test
	void G3_같은_단체를_동시에_두_번_승인해도_예약은_하나() throws Exception {
		makeQuota(20);
		long g = request(newRep(), 11);
		CountDownLatch go = new CountDownLatch(1);
		List<CompletableFuture<Outcome>> fs = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			long admin = i == 0 ? 0 : 1;
			fs.add(CompletableFuture.supplyAsync(() -> {
				await(go);
				return allocator.approve(g, null, false, admin == 1).outcome();
			}));
		}
		go.countDown();
		List<Outcome> got = fs.stream().map(CompletableFuture::join).toList();

		assertThat(got).containsOnlyOnce(Outcome.APPROVED);
		assertThat(count("SELECT COUNT(*) FROM reservation WHERE idempotency_key = ?", "grp:" + g)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE run_id = ? AND status = 'BLOCKED'", RUN)).as("진 쪽 키·행은 되돌림").isEqualTo(9);
		long groupKeys = blockedSeats().stream().filter(s -> SeatKeys.GROUP.equals(redis.opsForValue().get(SeatKeys.hold(RUN, s, 0)))).count();
		assertThat(groupKeys).isEqualTo(9);
	}

	@Test
	void G4_두_단체가_같은_할당석을_동시에_노려도_좌석은_겹치지_않는다() throws Exception {
		makeQuota(15);
		redis.opsForZSet().add(QueueKeys.queue(RUN), "someone", 1); // 일반 좌석 금지 → 할당석만 겨룸
		long g1 = request(newRep(), 11);
		long g2 = request(newRep(), 11);
		CountDownLatch go = new CountDownLatch(1);
		CompletableFuture<Outcome> f1 = CompletableFuture.supplyAsync(() -> {
			await(go);
			return allocator.approve(g1, null, false, false).outcome();
		});
		CompletableFuture<Outcome> f2 = CompletableFuture.supplyAsync(() -> {
			await(go);
			return allocator.approve(g2, null, false, false).outcome();
		});
		go.countDown();

		assertThat(List.of(f1.join(), f2.join())).containsExactlyInAnyOrder(Outcome.APPROVED, Outcome.DEFERRED);
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE run_id = ? AND status = 'HOLD'", RUN)).isEqualTo(11);
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE run_id = ? AND status = 'BLOCKED'", RUN)).isEqualTo(4);
	}

	@Test
	void 철회한_신청은_승인되지_않는다() {
		makeQuota(20);
		Rep rep = newRep();
		long g = request(rep, 11);
		apply.withdraw(rep.memberId(), g);

		assertThat(allocator.approve(g, null, false, false).outcome()).isEqualTo(Outcome.NOT_REQUESTED);
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE run_id = ? AND status = 'BLOCKED'", RUN)).isEqualTo(20);
	}

	// ------------------------------------------------------------ 도우미

	record Rep(long memberId, String ciHash, String phone) {
	}

	private Rep newRep() {
		long id = NEXT.getAndIncrement();
		String phone = "010" + PHONE.getAndIncrement();
		String ci = "grpb-ci-" + id;
		jdbc.update("""
				INSERT INTO member (member_id, email, ci_hash, name, phone_hash, status)
				VALUES (?, ?, ?, ?, ?, 'ACTIVE')""", id, "grpb" + id + "@infra-test.jejari.dev", ci, "대표", pseudonymizer.phone(phone));
		return new Rep(id, ci, phone);
	}

	/** 대표자 1명 + 나머지. noPhone명은 휴대폰 없음 */
	private ApplyCommand command(Rep rep, int n, int noPhone) {
		List<PassengerInput> pax = new ArrayList<>();
		pax.add(new PassengerInput("대표자", rep.phone(), false, "ADULT", true));
		for (int i = 1; i < n; i++) {
			boolean none = i <= noPhone;
			pax.add(new PassengerInput("탑승자", none ? null : "010" + PHONE.getAndIncrement(), none,
					i % 4 == 0 ? "CHILD" : "ADULT", false));
		}
		return new ApplyCommand(RUN, FROM, TO, true, pax);
	}

	/** 승인 시도 없이 신청만 (승인 절차를 따로 확인할 때) */
	private long request(Rep rep, int n) {
		long g = jdbc.queryForObject("SELECT reservation_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO group_booking (group_id, rep_member_id, run_id, from_station_id, to_station_id, passenger_count,
				       status, discount_yn, consent_at, warn_no_phone_yn, warn_dup_phone_yn)
				VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', 'N', SYSTIMESTAMP, 'N', 'N')""", g, rep.memberId(), RUN, FROM, TO, n);
		for (int i = 1; i <= n; i++) {
			jdbc.update("""
					INSERT INTO group_passenger (group_id, passenger_no, name_enc, enc_key_id, no_phone_yn, passenger_type, rep_yn, status)
					VALUES (?, ?, 'x', 'k1', 'Y', 'ADULT', ?, 'ACTIVE')""", g, i, i == 1 ? "Y" : "N");
		}
		jdbc.update("UPDATE group_booking SET created_at = SYSTIMESTAMP + NUMTODSINTERVAL(?, 'SECOND') WHERE group_id = ?",
				NEXT.get(), g); // 신청 순서 = 만든 순서
		return g;
	}

	/** §18.1과 같은 모양: 일반실 객차 하나의 뒤쪽 좌석 q개를 BLOCKED + GROUP 키 */
	private void makeQuota(int q) {
		List<Long> seats = jdbc.queryForList("""
				SELECT seat_id FROM seat WHERE train_id = ? AND seat_grade = 'GENERAL'
				 AND car_no = (SELECT MAX(car_no) FROM seat WHERE train_id = ? AND seat_grade = 'GENERAL')
				 ORDER BY seat_id DESC FETCH FIRST ? ROWS ONLY""", Long.class, TRAIN, TRAIN, q);
		assertThat(seats).hasSize(q);
		for (long s : seats) {
			jdbc.update("""
					INSERT INTO seat_inventory (run_id, train_id, seat_id, segment_no, reservation_id, status, hold_expires_at)
					VALUES (?, ?, ?, 0, NULL, 'BLOCKED', NULL)""", RUN, TRAIN, s);
			redis.opsForValue().set(SeatKeys.hold(RUN, s, 0), SeatKeys.GROUP, Duration.ofDays(10));
		}
	}

	private List<Long> blockedSeats() {
		return jdbc.queryForList("SELECT seat_id FROM seat_inventory WHERE run_id = ? AND status = 'BLOCKED'", Long.class, RUN);
	}

	private static void await(CountDownLatch l) {
		try {
			l.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
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
		String groups = "SELECT group_id FROM group_booking WHERE rep_member_id BETWEEN " + lo + " AND " + hi;
		jdbc.update("DELETE FROM outbox_event WHERE aggregate_type = 'RESERVATION' AND aggregate_id IN (SELECT TO_CHAR(reservation_id) FROM reservation WHERE member_id BETWEEN ? AND ?)", lo, hi);
		jdbc.update("DELETE FROM group_passenger WHERE group_id IN (" + groups + ")");
		jdbc.update("DELETE FROM group_booking WHERE rep_member_id BETWEEN ? AND ?", lo, hi);
		jdbc.update("DELETE FROM reservation_history WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM saga_instance WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation_seat WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_inventory WHERE run_id = ? AND status = 'BLOCKED'", RUN);
		jdbc.update("DELETE FROM reservation WHERE member_id BETWEEN ? AND ?", lo, hi);
		jdbc.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?", lo, hi);
	}
}
