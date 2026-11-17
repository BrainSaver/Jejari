package com.jejari.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
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
import com.jejari.waitlist.WaitlistService.ApplyCommand;
import com.jejari.waitlist.WaitlistService.Entry;
import com.jejari.waitlist.offer.WaitlistOfferTx;

/**
 * WL-01 신청 · WL-02 조회·취소 · WL-03 제안을 실제 Oracle·Redis로 확인한다 (concurrency.md §9).
 *
 * <p>Kafka 소비자({@code SeatReleaseConsumer})는 띄우지 않고 그 안의 트랜잭션
 * {@link WaitlistOfferTx#offer}를 직접 부른다. 메시지가 언제 오는지에 기대면 테스트가 흔들린다.
 *
 * <p>운행 9001은 좌석이 1,050석이라 "매진"을 만들 수 없다. 그래서 매진 검사가 필요한 신청은
 * 정책 검사 순서를 이용해 확인하고, 제안 흐름은 대기 행을 직접 넣어 확인한다.
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
class WaitlistInfraTest {

	static final long RUN = 9001;
	static final String SEOUL = "3900023";
	static final String BUSAN = "3900114";
	static final String DAEJEON = "3900073";
	static final long MEMBER_BASE = 940000;
	static final AtomicLong NEXT_MEMBER = new AtomicLong(MEMBER_BASE + 1);

	@Autowired
	WaitlistService waitlists;
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

	// ─────────────────────────────── WL-01 신청 ───────────────────────────────

	@Test
	void 남은_좌석이_있으면_대기가_아니라_바로_사라고_한다() {
		assertThatThrownBy(() -> waitlists.apply(newMember(), apply(2)))
				.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(WaitlistErrorCode.WAITLIST_NOT_SOLD_OUT);
	}

	@Test
	void 이용이_제한된_계정은_신청할_수_없다() {
		long m = newMember();
		jdbc.update("UPDATE member SET status = 'RESTRICTED' WHERE member_id = ?", m);

		// RESTRICTED 검사가 매진 검사보다 먼저다 (§9.3-0)
		assertThatThrownBy(() -> waitlists.apply(m, apply(2)))
				.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(WaitlistErrorCode.WAITLIST_RESTRICTED);
	}

	@Test
	void 한_번에_살_수_있는_매수를_넘으면_거절() {
		assertThatThrownBy(() -> waitlists.apply(newMember(), apply(99)))
				.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(com.jejari.seat.SeatErrorCode.LIMIT_PER_RESERVATION);
	}

	@Test
	void 잘못된_등급과_구간은_거절() {
		long m = newMember();
		assertThatThrownBy(() -> waitlists.apply(m,
				new ApplyCommand(RUN, SEOUL, BUSAN, "SUITE", 1)))
				.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(com.jejari.common.error.CommonErrorCode.INVALID_REQUEST);
		// 부산 → 서울은 이 열차의 정차 순서와 반대다
		assertThatThrownBy(() -> waitlists.apply(m,
				new ApplyCommand(RUN, BUSAN, SEOUL, "GENERAL", 1)))
				.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(com.jejari.common.error.CommonErrorCode.INVALID_REQUEST);
	}

	@Test
	void 위험_점수가_높으면_대기_순서가_뒤로_밀린다() {
		long clean = insertWaiting(newMember(), SEOUL, BUSAN, "GENERAL", 1, 0);
		long risky = insertWaiting(newMember(), SEOUL, BUSAN, "GENERAL", 1, 80 * 180_000L);

		List<Long> order = jdbc.queryForList("""
				SELECT waitlist_id FROM waitlist WHERE run_id = ? AND status = 'WAITING'
				 ORDER BY priority_at, waitlist_id""", Long.class, RUN);

		// 80점이면 4시간 뒤로 (1점 = 3분). 나중에 신청해도 패널티 없는 사람이 앞이다
		assertThat(order).containsExactly(clean, risky);
	}

	// ─────────────────────────── WL-02 조회·대기 취소 ───────────────────────────

	@Test
	void 내_대기_목록에_순번이_함께_나온다() {
		long me = newMember();
		insertWaiting(newMember(), SEOUL, BUSAN, "GENERAL", 1, 0);   // 내 앞사람
		long mine = insertWaiting(me, SEOUL, BUSAN, "GENERAL", 1, 60_000L);

		List<Entry> list = waitlists.myList(me);

		assertThat(list).hasSize(1);
		assertThat(list.get(0).waitlistId()).isEqualTo(mine);
		assertThat(list.get(0).rank()).isEqualTo(2);
		assertThat(list.get(0).penaltyMs()).isEqualTo(60_000L);
	}

	@Test
	void 대기_취소는_기다리는_중일_때만_되고_두_번_해도_같다() {
		long me = newMember();
		long w = insertWaiting(me, SEOUL, BUSAN, "GENERAL", 1, 0);

		assertThat(waitlists.cancel(me, w).status()).isEqualTo("CANCELED");
		assertThat(waitlists.cancel(me, w).status()).isEqualTo("CANCELED");   // 멱등
	}

	@Test
	void 남의_대기는_없는_대기와_같게_404() {
		long w = insertWaiting(newMember(), SEOUL, BUSAN, "GENERAL", 1, 0);

		assertThatThrownBy(() -> waitlists.cancel(newMember(), w))
				.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(WaitlistErrorCode.WAITLIST_NOT_FOUND);
	}

	@Test
	void 이미_제안받은_대기는_대기_취소로_되돌릴_수_없다() {
		long me = newMember();
		long w = insertWaiting(me, SEOUL, BUSAN, "GENERAL", 1, 0);
		jdbc.update("UPDATE waitlist SET status = 'OFFERED' WHERE waitlist_id = ?", w);

		assertThatThrownBy(() -> waitlists.cancel(me, w)).extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(WaitlistErrorCode.WAITLIST_NOT_WAITING);
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

	// ─────────────────────────────── 도우미 ───────────────────────────────

	private ApplyCommand apply(int seatCount) {
		return new ApplyCommand(RUN, SEOUL, BUSAN, "GENERAL", seatCount);
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
