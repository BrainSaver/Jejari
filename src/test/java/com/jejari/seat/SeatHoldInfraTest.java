package com.jejari.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jejari.auth.ActorType;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.common.error.BusinessException;
import com.jejari.seat.SeatHoldService.HoldCommand;
import com.jejari.seat.SeatHoldService.HoldResult;
import com.jejari.seat.SeatHoldService.SeatRequest;

/**
 * 좌석 선점 RSV-02를 실제 Oracle·Redis로 확인한다 (concurrency.md §16 T1·T2·T3·T5, 매수 한도).
 * {@code docker compose up -d} 뒤 {@code ./gradlew infraTest}. Redis는 15번 DB(매번 비움),
 * Oracle은 이 테스트가 만든 회원(ID 990001~)과 그 예약만 지운다.
 *
 * <p>운행 9001(시드의 상시 ON_SALE 운행): 상시 정책 1회 9매 · 열차당 10매 · 1일 20매.
 */
@Tag("infra")
@SpringBootTest(properties = {
		"spring.data.redis.database=15",
		"jejari.queue.scheduler-enabled=false",
		"jejari.risk.cache-resync-enabled=false",
		"jejari.seat.expiry-scanner-enabled=false"})
@AutoConfigureMockMvc
class SeatHoldInfraTest {

	static final long RUN = 9001;
	static final String FROM = "3900023";
	static final String TO = "3900114";
	static final long MEMBER_BASE = 990000;
	static final AtomicLong NEXT_MEMBER = new AtomicLong(MEMBER_BASE + 1);

	@Autowired
	SeatHoldService service;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	MockMvc mvc;
	@Autowired
	AccessTokenIssuer tokens;

	List<Long> seatIds;

	@BeforeEach
	void setUp() {
		cleanDb();
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		seatIds = jdbc.queryForList("SELECT seat_id FROM seat WHERE train_id = 1 ORDER BY seat_id", Long.class);
	}

	@AfterEach
	void tearDown() {
		cleanDb();
	}

	@Test
	void T1_같은_좌석_동시_100건이면_1건만_성공하고_진_요청은_DB에_쓰지_않는다() throws Exception {
		long seat = seatIds.get(0);
		List<Long> members = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			members.add(newMember());
		}
		ExecutorService pool = Executors.newFixedThreadPool(32);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> results = new ArrayList<>();
		for (long m : members) {
			results.add(pool.submit((Callable<String>) () -> {
				start.await();
				try {
					service.hold(m, cmd(UUID.randomUUID().toString(), seat));
					return "OK";
				} catch (BusinessException e) {
					return e.getErrorCode().code();
				}
			}));
		}
		start.countDown();
		List<String> outcome = new ArrayList<>();
		for (Future<String> f : results) {
			outcome.add(f.get());
		}
		pool.shutdown();

		assertThat(outcome).filteredOn("OK"::equals).hasSize(1);
		assertThat(outcome).filteredOn("SEAT_ALREADY_TAKEN"::equals).hasSize(99);
		assertThat(count("SELECT COUNT(*) FROM reservation WHERE member_id > ?", MEMBER_BASE)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE run_id = ? AND seat_id = ?", RUN, seat)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM purchase_counter WHERE ci_hash LIKE 'infra-ci-%'")).isEqualTo(2); // 승자의 PER_RUN·PER_DAY
		// 진 요청은 멱등 키·한도를 되돌렸다 (§2.1-4)
		assertThat(redis.keys("idem:*")).hasSize(1);
		long nonZeroLimits = redis.keys("limit:*").stream().filter(k -> !k.startsWith("limit:dec:"))
				.filter(k -> !"0".equals(redis.opsForValue().get(k))).count();
		assertThat(nonZeroLimits).isEqualTo(2);
	}

	@Test
	void T2_같은_멱등_키로_다시_보내면_같은_예약을_돌려준다() {
		long m = newMember();
		String key = UUID.randomUUID().toString();
		HoldResult first = service.hold(m, cmd(key, seatIds.get(1)));
		HoldResult again = service.hold(m, cmd(key, seatIds.get(1)));

		assertThat(again.reservationId()).isEqualTo(first.reservationId());
		assertThat(again.replay()).isTrue();
		assertThat(again.status()).isEqualTo("HOLD");
		assertThat(count("SELECT COUNT(*) FROM reservation WHERE member_id = ?", m)).isEqualTo(1);
		assertThat(perRun(m)).isEqualTo(1);
	}

	@Test
	void 선점하면_예약_재고_이력_Saga_outbox가_한_트랜잭션에_생긴다() {
		long m = newMember();
		HoldResult r = service.hold(m, cmd(UUID.randomUUID().toString(), seatIds.get(2), seatIds.get(3)));
		long id = r.reservationId();

		assertThat(r.totalAmount()).isPositive();
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ? AND status = 'HOLD'", id)).isEqualTo(2);
		assertThat(count("SELECT COUNT(*) FROM reservation_seat WHERE reservation_id = ?", id)).isEqualTo(2);
		assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ? AND to_status = 'HOLD'", id)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM saga_instance WHERE reservation_id = ? AND status = 'RUNNING'", id)).isEqualTo(1);
		// partition_key는 문자열, shard_no는 ORA_HASH(TO_CHAR(R), 15) (T32)
		assertThat(count("""
				SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'SEAT_HELD'
				   AND partition_key = ? AND shard_no = ORA_HASH(TO_CHAR(?), 15)""", Long.toString(id), Long.toString(id), id))
				.isEqualTo(1);
		// 마감 원천과 복사본이 같다 (§0.2)
		assertThat(count("""
				SELECT COUNT(*) FROM reservation r JOIN seat_inventory s ON s.reservation_id = r.reservation_id
				  JOIN saga_instance g ON g.reservation_id = r.reservation_id
				 WHERE r.reservation_id = ? AND s.hold_expires_at = r.pay_deadline_at AND g.timeout_at = r.pay_deadline_at""", id))
				.isEqualTo(2);
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, seatIds.get(2), 0))).isEqualTo(Long.toString(id));
		assertThat(redis.getExpire(SeatKeys.hold(RUN, seatIds.get(2), 0))).isPositive();
	}

	@Test
	void T3_만료된_2석_예약의_1석을_다른_사람이_가져가면_이전_예약은_전부_정리된다() {
		long a = newMember();
		long b = newMember();
		long s1 = seatIds.get(4);
		long s2 = seatIds.get(5);
		long ra = service.hold(a, cmd(UUID.randomUUID().toString(), s1, s2)).reservationId();
		expire(ra, s1, s2);

		long rb = service.hold(b, cmd(UUID.randomUUID().toString(), s1)).reservationId();

		assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE reservation_id = ?", String.class, ra))
				.isEqualTo("EXPIRED");
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ?", ra)).isZero(); // s2까지 (고아 행 없음)
		assertThat(perRun(a)).isZero(); // 2 감소
		assertThat(perRun(b)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ? AND seat_id = ?", rb, s1)).isEqualTo(1);
		assertThat(count("""
				SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'RESERVATION_EXPIRED'
				   AND DBMS_LOB.INSTR(payload, 'PREEMPTED') > 0""", Long.toString(ra))).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM saga_instance WHERE reservation_id = ? AND status = 'COMPENSATED'", ra))
				.isEqualTo(1);
	}

	@Test
	void T5_결제_중인_좌석은_마감이_지나도_재선점할_수_없다() {
		long a = newMember();
		long b = newMember();
		long s = seatIds.get(6);
		long ra = service.hold(a, cmd(UUID.randomUUID().toString(), s)).reservationId();
		expire(ra, s);
		jdbc.update("UPDATE reservation SET status = 'PAYMENT_PENDING' WHERE reservation_id = ?", ra);

		assertThatThrownBy(() -> service.hold(b, cmd(UUID.randomUUID().toString(), s)))
				.isInstanceOf(BusinessException.class).hasMessageContaining("다른 사람");
		assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE reservation_id = ?", String.class, ra))
				.isEqualTo("PAYMENT_PENDING");
		assertThat(count("SELECT COUNT(*) FROM reservation WHERE member_id = ?", b)).isZero();
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s, 0))).isNull(); // B가 잡았던 키를 되돌림
		assertThat(perRun(b)).isZero();
	}

	@Test
	void 한_번에_9매를_넘으면_거절한다() {
		long m = newMember();
		long[] ten = seatIds.subList(10, 20).stream().mapToLong(Long::longValue).toArray();
		assertThatThrownBy(() -> service.hold(m, cmd(UUID.randomUUID().toString(), ten)))
				.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(SeatErrorCode.LIMIT_PER_RESERVATION);
	}

	@Test
	void 열차당_10매를_넘으면_거절하고_카운터는_그대로다() {
		long m = newMember();
		long[] nine = seatIds.subList(20, 29).stream().mapToLong(Long::longValue).toArray();
		service.hold(m, cmd(UUID.randomUUID().toString(), nine));
		long[] two = seatIds.subList(29, 31).stream().mapToLong(Long::longValue).toArray();

		assertThatThrownBy(() -> service.hold(m, cmd(UUID.randomUUID().toString(), two)))
				.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(SeatErrorCode.LIMIT_PER_RUN);
		assertThat(perRun(m)).isEqualTo(9);
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, seatIds.get(29), 0))).isNull(); // 좌석 경쟁 전에 걸림
	}

	@Test
	void 한도_키가_DB보다_크게_남아도_자가_복구한다_T33() {
		long m = newMember();
		String ci = ci(m);
		redis.opsForValue().set(SeatKeys.limitRun(RUN, ci), "10"); // 보상 유실 흉내 (DB는 0)

		HoldResult r = service.hold(m, cmd(UUID.randomUUID().toString(), seatIds.get(40)));
		assertThat(r.status()).isEqualTo("HOLD");
		assertThat(redis.opsForValue().get(SeatKeys.limitRun(RUN, ci))).isEqualTo("1");
	}

	@Test
	void 입장_토큰_없이_좌석_API를_부르면_403() throws Exception {
		long m = newMember();
		String access = tokens.issue(ActorType.MEMBER, m, UUID.randomUUID().toString()).value();
		mvc.perform(post("/api/v1/reservations")
						.header("Authorization", "Bearer " + access)
						.header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"runId\":9001,\"fromStationId\":\"" + FROM + "\",\"toStationId\":\"" + TO
								+ "\",\"seats\":[{\"seatId\":" + seatIds.get(50) + "}]}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("QUEUE_TOKEN_REQUIRED"));
		assertThat(count("SELECT COUNT(*) FROM reservation WHERE member_id = ?", m)).isZero();
	}

	@Test
	void 입장_토큰이_있으면_API로_선점된다() throws Exception {
		long m = newMember();
		String access = tokens.issue(ActorType.MEMBER, m, UUID.randomUUID().toString()).value();
		redis.opsForValue().set("queue:pass:" + RUN + ":" + m, "pass-token-1");
		mvc.perform(post("/api/v1/reservations")
						.header("Authorization", "Bearer " + access)
						.header("X-Queue-Token", "pass-token-1")
						.header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"runId\":9001,\"fromStationId\":\"" + FROM + "\",\"toStationId\":\"" + TO
								+ "\",\"seats\":[{\"seatId\":" + seatIds.get(51) + ",\"passengerType\":\"ADULT\"}]}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("HOLD"));
	}

	// ------------------------------------------------------------ 도우미

	private static HoldCommand cmd(String key, long... seats) {
		List<SeatRequest> list = new ArrayList<>();
		for (long s : seats) {
			list.add(new SeatRequest(s, "ADULT"));
		}
		return new HoldCommand(RUN, FROM, TO, list, "WEB", key);
	}

	private long newMember() {
		long id = NEXT_MEMBER.getAndIncrement();
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
				id, "seat" + id + "@infra-test.jejari.dev", "infra-ci-" + id, "테스트");
		return id;
	}

	private static String ci(long memberId) {
		return "infra-ci-" + memberId;
	}

	private long perRun(long memberId) {
		List<Long> v = jdbc.queryForList(
				"SELECT seat_count FROM purchase_counter WHERE scope = 'PER_RUN' AND scope_ref_id = ? AND ci_hash = ?",
				Long.class, RUN, ci(memberId));
		return v.isEmpty() ? 0 : v.get(0);
	}

	/** 결제 기한이 지난 것처럼 만든다: DB 마감을 과거로, Redis 좌석 키는 TTL로 사라진 것처럼 지움 */
	private void expire(long reservationId, long... seats) {
		jdbc.update("UPDATE reservation SET pay_deadline_at = SYSTIMESTAMP - INTERVAL '1' MINUTE WHERE reservation_id = ?",
				reservationId);
		jdbc.update("UPDATE seat_inventory SET hold_expires_at = SYSTIMESTAMP - INTERVAL '1' MINUTE WHERE reservation_id = ?",
				reservationId);
		for (long s : seats) {
			redis.delete(SeatKeys.hold(RUN, s, 0));
		}
	}

	private long count(String sql, Object... args) {
		Long v = jdbc.queryForObject(sql, Long.class, args);
		return v == null ? 0 : v;
	}

	private void cleanDb() {
		String mine = "SELECT reservation_id FROM reservation WHERE member_id > " + MEMBER_BASE;
		jdbc.update("DELETE FROM outbox_event WHERE aggregate_type = 'RESERVATION' AND aggregate_id IN (SELECT TO_CHAR(reservation_id) FROM reservation WHERE member_id > ?)", MEMBER_BASE);
		jdbc.update("DELETE FROM reservation_history WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM saga_instance WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation_seat WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation WHERE member_id > ?", MEMBER_BASE);
		jdbc.update("DELETE FROM purchase_counter WHERE ci_hash LIKE 'infra-ci-%'");
		jdbc.update("DELETE FROM member WHERE member_id > ?", MEMBER_BASE);
	}
}
