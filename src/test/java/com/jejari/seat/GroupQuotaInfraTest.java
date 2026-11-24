package com.jejari.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
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

import com.jejari.common.config.AppPolicy;
import com.jejari.common.error.BusinessException;
import com.jejari.seat.SeatHoldService.HoldCommand;
import com.jejari.seat.SeatHoldService.SeatRequest;
import com.jejari.support.NoRunningApp;

/**
 * 단체 할당석 (concurrency.md §18, ERD v36)을 실제 Oracle·Redis로 확인한다.
 *
 * <ul>
 * <li>G1·G2: BLOCKED 좌석은 일반 선점이 진다 (Redis 키가 있을 때, 키가 먼저 사라졌을 때)</li>
 * <li>G6: 반납은 BLOCKED만 지우고 단체·개인이 잡은 좌석은 남긴다</li>
 * <li>G7·G8: 단체 예약(source = GROUP)은 만료·재선점돼도 대표자 개인 카운터가 줄지 않는다</li>
 * </ul>
 *
 * 단체 승인 API(길동현)는 아직 없어서 G7·G8의 단체 예약은 일반 선점으로 만든 뒤 source만 GROUP으로 바꿔 흉내 낸다.
 * 이때 카운터는 선점 때 이미 1로 올라 있으므로 "해제 뒤에도 1 그대로"가 감소를 건너뛰었다는 뜻이다.
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
		"jejari.train.sale-open-scheduler-enabled=false"})
class GroupQuotaInfraTest {

	static final long RUN = 9001;
	static final long TRAIN = 1;
	static final String FROM = "3900023";
	static final String TO = "3900114";
	static final long MEMBER_BASE = 985000;
	static final AtomicLong NEXT_MEMBER = new AtomicLong(MEMBER_BASE + 1);

	@Autowired
	SeatHoldService hold;
	@Autowired
	SeatReleaseService release;
	@Autowired
	SeatMapService seatMap;
	@Autowired
	SeatScripts scripts;
	@Autowired
	AppPolicy policy;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	TransactionTemplate tx;
	@Autowired
	Clock clock;

	GroupQuotaScheduler quota;
	HoldExpiryScanner scanner;
	LocalDateTime farDeparture;

	@BeforeEach
	void setUp() {
		cleanDb();
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		quota = new GroupQuotaScheduler(jdbc, tx, redis, scripts, seatMap, policy, clock, true);
		scanner = new HoldExpiryScanner(jdbc, release, policy);
		farDeparture = LocalDateTime.now(clock).plusDays(60); // 반납 시각(출발 30일 전)이 아직 멀다
	}

	@AfterEach
	void tearDown() {
		cleanDb();
	}

	// ------------------------------------------------------------ §18.1 만들기

	@Test
	void 할당석을_만들면_좌석의_비율만큼_BLOCKED_행과_GROUP_키가_생긴다() {
		long total = count("SELECT COUNT(*) FROM seat WHERE train_id = ?", TRAIN);

		int made = quota.create(RUN, TRAIN, new BigDecimal("0.05"), farDeparture);

		assertThat(made).isEqualTo((int) Math.floor(total * 0.05)).isPositive();
		assertThat(blocked()).isEqualTo(made);
		assertThat(count("""
				SELECT COUNT(*) FROM seat_inventory i JOIN seat s ON s.seat_id = i.seat_id
				 WHERE i.run_id = ? AND i.status = 'BLOCKED' AND s.seat_grade <> 'GENERAL'""", RUN))
				.as("할당석은 일반실에서만").isZero();
		for (long seat : blockedSeats()) {
			assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, seat, 0))).isEqualTo(SeatKeys.GROUP);
		}
		// 좌석 배치도에서 선택 불가
		assertThat(seatMap.states(RUN, TRAIN).get(blockedSeats().get(0))).isEqualTo(SeatMapService.State.HELD);
	}

	@Test
	void 비율이_0이거나_반납_시각이_지났으면_만들지_않는다() {
		assertThat(quota.create(RUN, TRAIN, BigDecimal.ZERO, farDeparture)).isZero(); // 명절 행사에서 끈 경우
		assertThat(quota.create(RUN, TRAIN, new BigDecimal("0.05"), LocalDateTime.now(clock).plusDays(10))).isZero();
		assertThat(blocked()).isZero();
	}

	// ------------------------------------------------------------ §18.2 일반 선점 (G1·G2)

	@Test
	void G1_할당석에_일반_선점하면_진다_DB_쓰기_0건() {
		quota.create(RUN, TRAIN, new BigDecimal("0.05"), farDeparture);
		long seat = blockedSeats().get(0);
		long m = newMember();

		assertThatThrownBy(() -> hold.hold(m, cmd(seat))).isInstanceOf(BusinessException.class);

		assertThat(count("SELECT COUNT(*) FROM reservation WHERE member_id = ?", m)).isZero();
		assertThat(perRun(m)).isZero();
	}

	@Test
	void G2_Redis_키가_먼저_사라져도_DB의_BLOCKED_행이_거절한다() {
		quota.create(RUN, TRAIN, new BigDecimal("0.05"), farDeparture);
		long seat = blockedSeats().get(0);
		redis.delete(SeatKeys.hold(RUN, seat, 0)); // 반납 시각에 TTL로 사라졌는데 반납 스캐너가 아직 안 돈 사이
		long m = newMember();

		assertThatThrownBy(() -> hold.hold(m, cmd(seat))).isInstanceOf(BusinessException.class);

		assertThat(count("SELECT COUNT(*) FROM reservation WHERE member_id = ?", m)).as("롤백").isZero();
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE run_id = ? AND seat_id = ? AND status = 'BLOCKED'", RUN, seat))
				.as("할당석은 그대로").isEqualTo(1);
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, seat, 0))).as("진 쪽의 키는 되돌림").isNull();
	}

	// ------------------------------------------------------------ §18.4 반납 (G6)

	@Test
	void G6_반납은_BLOCKED만_지우고_다른_예약의_좌석과_키는_남긴다() {
		quota.create(RUN, TRAIN, new BigDecimal("0.05"), farDeparture);
		List<Long> blockedBefore = blockedSeats();
		long other = otherSeat(blockedBefore);
		long r = hold.hold(newMember(), cmd(other)).reservationId();

		int deleted = quota.release(RUN);

		assertThat(deleted).isEqualTo(blockedBefore.size());
		assertThat(blocked()).isZero();
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ? AND status = 'HOLD'", r)).isEqualTo(1);
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, other, 0))).isEqualTo(Long.toString(r));
		for (long seat : blockedBefore) {
			assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, seat, 0))).isNull();
		}
		// 풀린 좌석은 일반 판매로
		assertThat(hold.hold(newMember(), cmd(blockedBefore.get(0))).status()).isEqualTo("HOLD");
	}

	// ------------------------------------------------------------ §18.5 단체 예약은 카운터를 건드리지 않음 (G7·G8)

	@Test
	void G7_단체_예약이_결제_기한을_넘겨_만료돼도_대표자_개인_카운터는_그대로() {
		long m = newMember();
		long seat = otherSeat(List.of());
		long r = hold.hold(m, cmd(seat)).reservationId();
		asGroup(r);
		pastDeadline(r, seat);

		assertThat(scanner.scanOnce()).isEqualTo(1);

		assertThat(status(r)).isEqualTo("EXPIRED");
		assertThat(perRun(m)).as("감소를 건너뜀").isEqualTo(1);
		assertThat(redis.opsForValue().get(SeatKeys.limitRun(RUN, ci(m)))).as("Redis 한도도 그대로").isEqualTo("1");
	}

	@Test
	void G8_만료된_단체_HOLD를_다른_사람이_재선점해도_대표자_개인_카운터는_그대로() {
		long m = newMember();
		long seat = otherSeat(List.of());
		long r = hold.hold(m, cmd(seat)).reservationId();
		asGroup(r);
		pastDeadline(r, seat);
		long other = newMember();

		assertThat(hold.hold(other, cmd(seat)).status()).isEqualTo("HOLD"); // §3 재선점

		assertThat(status(r)).isEqualTo("EXPIRED");
		assertThat(perRun(m)).as("이전(단체) 예약 감소를 건너뜀").isEqualTo(1);
		assertThat(redis.opsForValue().get(SeatKeys.limitRun(RUN, ci(m)))).isEqualTo("1");
		assertThat(perRun(other)).isEqualTo(1);
	}

	@Test
	void 일반_예약의_만료는_예전처럼_카운터를_줄인다() {
		long m = newMember();
		long seat = otherSeat(List.of());
		long r = hold.hold(m, cmd(seat)).reservationId();
		pastDeadline(r, seat);

		assertThat(scanner.scanOnce()).isEqualTo(1);

		assertThat(perRun(m)).isZero();
	}

	// ------------------------------------------------------------ 도우미

	private void asGroup(long r) {
		jdbc.update("UPDATE reservation SET source = 'GROUP', updated_at = SYSTIMESTAMP WHERE reservation_id = ?", r);
	}

	/** 결제 기한이 지나고 유예까지 끝나 Redis 키가 사라진 것처럼 */
	private void pastDeadline(long r, long seat) {
		jdbc.update("UPDATE reservation SET pay_deadline_at = SYSTIMESTAMP - INTERVAL '1' MINUTE WHERE reservation_id = ?", r);
		jdbc.update("UPDATE seat_inventory SET hold_expires_at = SYSTIMESTAMP - INTERVAL '1' MINUTE WHERE reservation_id = ?", r);
		redis.delete(SeatKeys.hold(RUN, seat, 0));
	}

	/** 할당석이 아닌 일반실 좌석 하나 (할당석은 객차 뒤쪽부터라 앞쪽 좌석) */
	private long otherSeat(List<Long> exclude) {
		return jdbc.queryForList("SELECT seat_id FROM seat WHERE train_id = ? AND seat_grade = 'GENERAL' ORDER BY car_no, seat_id",
				Long.class, TRAIN).stream().filter(s -> !exclude.contains(s)).findFirst().orElseThrow();
	}

	private List<Long> blockedSeats() {
		return jdbc.queryForList("SELECT seat_id FROM seat_inventory WHERE run_id = ? AND status = 'BLOCKED' ORDER BY seat_id",
				Long.class, RUN);
	}

	private long blocked() {
		return count("SELECT COUNT(*) FROM seat_inventory WHERE run_id = ? AND status = 'BLOCKED'", RUN);
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
				id, "grp" + id + "@infra-test.jejari.dev", ci(id), "테스트");
		return id;
	}

	private static String ci(long memberId) {
		return "grp-ci-" + memberId;
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
		jdbc.update("DELETE FROM seat_inventory WHERE run_id = ? AND status = 'BLOCKED'", RUN);
		jdbc.update("DELETE FROM reservation WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
		jdbc.update("DELETE FROM purchase_counter WHERE ci_hash LIKE 'grp-ci-%'");
		jdbc.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?", MEMBER_BASE + 1, MEMBER_BASE + 9999);
	}
}
