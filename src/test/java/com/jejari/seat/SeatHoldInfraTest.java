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
import org.junit.jupiter.api.extension.ExtendWith;
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
import com.jejari.support.NoRunningApp;

/**
 * 좌석 선점 RSV-02를 실제 Oracle·Redis로 확인한다 (concurrency.md §16 T1·T2·T3·T5, 매수 한도).
 * {@code docker compose up -d} 뒤 {@code ./gradlew infraTest}. Redis는 15번 DB(매번 비움),
 * Oracle은 이 테스트가 만든 회원(ID 990001~)과 그 예약만 지운다.
 *
 * <p>운행 9001(시드의 상시 ON_SALE 운행): 상시 정책 1회 9매 · 열차당 10매 · 1일 20매.
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

	/**
	 * T4 (§3-2): 만료된 2석 예약 R1의 두 좌석을 두 사람이 <b>동시에</b> 재선점. 먼저 잠근 쪽이 R1을 EXPIRED로 바꾸고
	 * 남은 HOLD 행·카운터를 정리하고, 뒤 쪽은 이미 EXPIRED라 감소 없이 넘어가야 한다 → 둘 다 성공, R1 카운터는 한 번만 감소.
	 */
	@Test
	void T4_만료된_2석을_두_사람이_동시에_재선점해도_둘_다_성공하고_카운터는_한_번만_준다() throws Exception {
		long a = newMember();
		long b = newMember();
		long c = newMember();
		long s1 = seatIds.get(60);
		long s2 = seatIds.get(61);
		long r1 = service.hold(a, cmd(UUID.randomUUID().toString(), s1, s2)).reservationId();
		assertThat(perRun(a)).isEqualTo(2);
		expire(r1, s1, s2);

		List<String> outcome = race(List.of(
				() -> holdOutcome(b, s1),
				() -> holdOutcome(c, s2)));

		assertThat(outcome).containsOnly("OK");
		assertThat(statusOf(r1)).isEqualTo("EXPIRED");
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id = ?", r1)).isZero();
		assertThat(perRun(a)).isZero(); // 2 → 0 한 번 (두 번 깎였으면 CHECK(seat_count >= 0) 위반으로 한쪽이 실패했을 것)
		assertThat(perRun(b)).isEqualTo(1);
		assertThat(perRun(c)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE run_id = ? AND seat_id IN (?, ?) AND status = 'HOLD'", RUN, s1, s2))
				.isEqualTo(2);
		// R1 만료 이벤트·보상은 한 번만 (§3-5)
		assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'RESERVATION_EXPIRED'",
				Long.toString(r1))).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ? AND to_status = 'EXPIRED'", r1))
				.isEqualTo(1);
	}

	/**
	 * T11 (§10.1): 같은 CI의 <b>첫</b> 예약들이 동시에 들어와 카운터 행이 아직 없다. 여러 요청이 동시에 INSERT하면
	 * ORA-00001이 나고 UPDATE부터 다시 해야 한다 → 한도(열차당 10)까지만 성공, 카운터 = 성공한 좌석 수, scope마다 행 하나.
	 */
	@Test
	void T11_같은_CI_첫_예약이_동시에_와도_카운터_행은_하나이고_한도를_넘지_않는다() throws Exception {
		long m = newMember();
		List<Callable<String>> calls = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			long seat = seatIds.get(70 + i);
			calls.add(() -> holdOutcome(m, seat));
		}

		List<String> outcome = race(calls);

		assertThat(outcome).filteredOn("OK"::equals).hasSize(10);
		assertThat(outcome).filteredOn(o -> !"OK".equals(o)).containsOnly(SeatErrorCode.LIMIT_PER_RUN.code());
		assertThat(perRun(m)).isEqualTo(10);
		assertThat(count("SELECT COUNT(*) FROM purchase_counter WHERE ci_hash = ? AND scope = 'PER_RUN'", ci(m))).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM purchase_counter WHERE ci_hash = ? AND scope = 'PER_DAY'", ci(m))).isEqualTo(1);
		assertThat(count("SELECT seat_count FROM purchase_counter WHERE ci_hash = ? AND scope = 'PER_DAY'", ci(m))).isEqualTo(10);
		assertThat(count("SELECT COUNT(*) FROM reservation WHERE member_id = ? AND status = 'HOLD'", m)).isEqualTo(10);
		// 진 요청 2건은 좌석 키를 되돌렸다 — 잡힌 좌석 키는 성공한 10개뿐
		long heldKeys = seatIds.subList(70, 82).stream().filter(s -> redis.opsForValue().get(SeatKeys.hold(RUN, s, 0)) != null).count();
		assertThat(heldKeys).isEqualTo(10);
	}

	/**
	 * T12 (§3-1·§10.3): 서로 다른 CI의 재선점 2건이 <b>같은 이전 예약 둘</b>을 서로 반대로 가져간다.
	 * C는 RA의 좌석·RB의 좌석, D는 RA의 다른 좌석·RB의 다른 좌석. 이전 예약 잠금(ID 오름차순)과 카운터 갱신
	 * (scope·ref·ci 오름차순) 순서가 고정이 아니면 데드락(ORA-00060)이 난다 → 5라운드 모두 둘 다 성공해야 한다.
	 */
	@Test
	void T12_서로_다른_CI_재선점이_이전_예약을_교차해_가져가도_데드락_없이_둘_다_성공한다() throws Exception {
		for (int round = 0; round < 5; round++) {
			int base = 90 + round * 4;
			long a = newMember();
			long b = newMember();
			long c = newMember();
			long d = newMember();
			long a1 = seatIds.get(base);
			long a2 = seatIds.get(base + 1);
			long b1 = seatIds.get(base + 2);
			long b2 = seatIds.get(base + 3);
			long ra = service.hold(a, cmd(UUID.randomUUID().toString(), a1, a2)).reservationId();
			long rb = service.hold(b, cmd(UUID.randomUUID().toString(), b1, b2)).reservationId();
			expire(ra, a1, a2);
			expire(rb, b1, b2);

			// C는 (RB, RA) 순서로, D는 (RA, RB) 순서로 좌석을 고른다 — 고른 순서대로 잠그면 서로를 기다리는 모양
			List<String> outcome = race(List.of(
					() -> holdOutcome(c, b1, a1),
					() -> holdOutcome(d, a2, b2)));

			assertThat(outcome).as("라운드 %d", round).containsOnly("OK");
			assertThat(statusOf(ra)).isEqualTo("EXPIRED");
			assertThat(statusOf(rb)).isEqualTo("EXPIRED");
			assertThat(perRun(a)).isZero();
			assertThat(perRun(b)).isZero();
			assertThat(perRun(c)).isEqualTo(2);
			assertThat(perRun(d)).isEqualTo(2);
			assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE reservation_id IN (?, ?)", ra, rb)).isZero();
		}
	}

	/**
	 * T14 (§10.4): Redis가 재시작돼 키를 전부 잃었다 (단일 노드 SPOF). 한도 키는 다음 요청이 purchase_counter 값으로
	 * SET NX 복원한 뒤 더하므로, 동시에 여러 요청이 복원해도 한 값만 들어간다. 좌석 키를 잃어도 DB 점유가 최종이다.
	 */
	@Test
	void T14_Redis를_잃어도_한도는_DB_카운터로_복원되고_좌석은_DB가_지킨다() throws Exception {
		long m = newMember();
		long other = newMember();
		long[] seven = seatIds.subList(120, 127).stream().mapToLong(Long::longValue).toArray();
		service.hold(m, cmd(UUID.randomUUID().toString(), seven));
		assertThat(perRun(m)).isEqualTo(7);

		// Redis 재시작 흉내: 데이터를 전부 잃음 (AOF everysec이면 실제로는 최대 1초. 여기서는 최악으로 전부)
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		assertThat(redis.keys("limit:*")).isEmpty();

		// 1) 같은 CI가 1석씩 5건 동시 → 각 요청이 DB 7을 복원값으로 들고 오지만 SET NX라 한 번만 채움 → 3건만 통과
		List<Callable<String>> calls = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			long seat = seatIds.get(130 + i);
			calls.add(() -> holdOutcome(m, seat));
		}
		List<String> outcome = race(calls);
		assertThat(outcome).filteredOn("OK"::equals).hasSize(3);
		assertThat(outcome).filteredOn(o -> !"OK".equals(o)).containsOnly(SeatErrorCode.LIMIT_PER_RUN.code());
		assertThat(perRun(m)).isEqualTo(10);
		// Redis 값은 정확히 10이 아닐 수 있다: 3건이 커밋하기 전에 진 요청이 §10.7 자가 복구로 DB 값(7)을 SET해
		// 진행 중인 증가분을 덮어쓴다 (concurrency.md §10.7에 적힌 허용 동작). Redis는 DB보다 크게만 안 남으면 된다(§0.1)
		assertThat(Integer.parseInt(redis.opsForValue().get(SeatKeys.limitRun(RUN, ci(m))))).isBetween(7, 10);
		// Redis가 낮아져 1차를 통과해도 DB 카운터가 최종이라 한도를 넘지 않는다
		assertThat(holdOutcome(m, seatIds.get(135))).isEqualTo(SeatErrorCode.LIMIT_PER_RUN.code());
		assertThat(perRun(m)).isEqualTo(10);

		// 2) 좌석 키를 잃어 Redis 1차(hold.lua)는 통과하지만, DB에 살아 있는 HOLD 행이 있어 거절 (§2.2-1, DB가 최종)
		assertThat(holdOutcome(other, seatIds.get(120))).isEqualTo(SeatErrorCode.SEAT_ALREADY_TAKEN.code());
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, seatIds.get(120), 0))).isNull(); // 진 요청이 키를 되돌림
		assertThat(perRun(other)).isZero();
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE run_id = ? AND seat_id = ? AND status = 'HOLD'", RUN,
				seatIds.get(120))).isEqualTo(1);
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

	// concurrency.md §16 T13 일부 (Redis 1차 거절 경로. DB가 거절할 때의 Redis 원복은 아님)
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

		// RSV-05: 같은 예약을 API로 결제 전 취소
		long r = jdbc.queryForObject("SELECT reservation_id FROM reservation WHERE member_id = ?", Long.class, m);
		mvc.perform(post("/api/v1/reservations/" + r + "/cancel").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELED"))
				.andExpect(jsonPath("$.seatCount").value(1));
		mvc.perform(post("/api/v1/reservations/" + r + "/cancel")
						.header("Authorization", "Bearer " + tokens.issue(ActorType.MEMBER, newMember(), UUID.randomUUID().toString()).value()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
	}

	/**
	 * T13 (§2.3): Redis 1차는 통과했는데 <b>DB 카운터가</b> 한도로 거절한다 (Redis가 DB보다 낮게 남은 경우 — T14처럼
	 * 자가 복구가 진행 중인 증가분을 덮어쓰거나, 재시작으로 키를 잃은 뒤). DB 트랜잭션이 롤백되면 이번 요청이 Redis에 남긴
	 * 좌석 키·한도 증가·멱등 키를 전부 되돌려야 한다.
	 */
	@Test
	void T13_DB가_한도로_거절하면_Redis_좌석_키_한도_멱등_키를_되돌린다() {
		long m = newMember();
		long[] nine = seatIds.subList(140, 149).stream().mapToLong(Long::longValue).toArray();
		service.hold(m, cmd(UUID.randomUUID().toString(), nine));
		long purchaseDay = jdbc.queryForObject(
				"SELECT scope_ref_id FROM purchase_counter WHERE scope = 'PER_DAY' AND ci_hash = ?", Long.class, ci(m));
		// Redis 열차당 값이 DB(9)보다 낮게 남은 상태 → 2매가 Redis 1차(0+2 ≤ 10)를 통과한다
		redis.opsForValue().set(SeatKeys.limitRun(RUN, ci(m)), "0");
		long s1 = seatIds.get(149);
		long s2 = seatIds.get(150);
		String key = UUID.randomUUID().toString();

		assertThatThrownBy(() -> service.hold(m, cmd(key, s1, s2)))
				.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(SeatErrorCode.LIMIT_PER_RUN);

		// DB: 9 그대로, 새 예약 없음
		assertThat(perRun(m)).isEqualTo(9);
		assertThat(count("SELECT COUNT(*) FROM reservation WHERE member_id = ?", m)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM seat_inventory WHERE run_id = ? AND seat_id IN (?, ?)", RUN, s1, s2)).isZero();
		// Redis: 이긴 좌석 키 2개 지움 · 한도 증가분 되돌림(열차당 0, 1일 9) · 이 요청의 멱등 키 지움
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s1, 0))).isNull();
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, s2, 0))).isNull();
		assertThat(redis.opsForValue().get(SeatKeys.limitRun(RUN, ci(m)))).isEqualTo("0");
		assertThat(redis.opsForValue().get(SeatKeys.limitDay(purchaseDay, ci(m)))).isEqualTo("9");
		assertThat(redis.hasKey(SeatKeys.idem(m, key))).isFalse();
		// 좌석은 바로 다른 사람이 살 수 있다
		assertThat(holdOutcome(newMember(), s1, s2)).isEqualTo("OK");
	}

	/**
	 * T31 (§1-2): 멱등 키(idem:*)의 TTL이 지난 뒤 같은 Idempotency-Key로 다시 선점 요청. Redis는 처음 보는 키라 통과시키지만
	 * DB의 UNIQUE(member_id, idempotency_key)가 막는다 → 기존 예약을 돌려주고, 새 요청이 Redis에 남긴 좌석 키·한도는 되돌린다.
	 * (같은 좌석으로 다시 오면 좌석 키가 아직 기존 예약 것이라 Redis에서 SEAT_ALREADY_TAKEN으로 먼저 끝난다. 여기서는 DB까지
	 * 가는 경우 — 좌석을 바꿔 보낸 재시도 — 를 본다)
	 */
	@Test
	void T31_멱등_키가_만료된_뒤_같은_키로_오면_기존_예약을_돌려주고_새_요청의_Redis를_되돌린다() {
		long m = newMember();
		String key = UUID.randomUUID().toString();
		long first = service.hold(m, cmd(key, seatIds.get(160))).reservationId();
		redis.delete(SeatKeys.idem(m, key)); // TTL 만료 흉내
		long other = seatIds.get(161);

		HoldResult again = service.hold(m, cmd(key, other));

		assertThat(again.reservationId()).isEqualTo(first);
		assertThat(again.replay()).isTrue();
		assertThat(count("SELECT COUNT(*) FROM reservation WHERE member_id = ?", m)).isEqualTo(1);
		assertThat(perRun(m)).isEqualTo(1);
		assertThat(redis.opsForValue().get(SeatKeys.hold(RUN, other, 0))).isNull();      // 새 요청이 잡았던 좌석 키를 되돌림
		assertThat(redis.opsForValue().get(SeatKeys.limitRun(RUN, ci(m)))).isEqualTo("1"); // 새 요청의 +1을 되돌림
		assertThat(redis.opsForValue().get(SeatKeys.idem(m, key))).isEqualTo(Long.toString(first)); // 다음 재시도는 Redis에서 바로
		assertThat(holdOutcome(newMember(), other)).isEqualTo("OK");
	}

	/**
	 * T36 (§10.1·§10.6): 같은 CI가 하루에 여러 운행을 산다. 1일 한도(상시 20매)는 운행이 달라도 합산 → 9001 10매 + 9002 10매
	 * = 20매까지 성공하고, 세 번째 운행의 1매는 열차당 한도(0 + 1)는 괜찮아도 LIMIT_PER_DAY로 거절. Redis를 잃어도
	 * DB 카운터로 복원해 같은 판정. 시드의 상시 판매 운행은 둘뿐이라(열차당 10 × 2 = 20) 9001을 복사해 세 번째 운행을 잠깐 만든다.
	 */
	@Test
	void T36_운행이_달라도_1일_한도는_합산되고_넘으면_거절한다() {
		long third = createTempRun();
		try {
			long m = newMember();
			List<Long> train2 = jdbc.queryForList("SELECT seat_id FROM seat WHERE train_id = 2 ORDER BY seat_id", Long.class);
			service.hold(m, cmdOn(RUN, seatIds.subList(200, 209)));
			service.hold(m, cmdOn(RUN, seatIds.subList(209, 210)));
			service.hold(m, cmdOn9002(train2.subList(200, 209))); // 9002는 부산 → 서울
			service.hold(m, cmdOn9002(train2.subList(209, 210)));
			assertThat(perRun(m)).isEqualTo(10);
			assertThat(count("SELECT seat_count FROM purchase_counter WHERE scope = 'PER_DAY' AND ci_hash = ?", ci(m))).isEqualTo(20);

			assertThat(holdOutcomeOn(third, m, seatIds.get(210))).isEqualTo(SeatErrorCode.LIMIT_PER_DAY.code());
			assertThat(count("SELECT COUNT(*) FROM reservation WHERE member_id = ? AND run_id = ?", m, third)).isZero();
			assertThat(redis.opsForValue().get(SeatKeys.limitRun(third, ci(m)))).isEqualTo("0"); // 먼저 올린 열차당 +1도 되돌림
			assertThat(redis.opsForValue().get(SeatKeys.hold(third, seatIds.get(210), 0))).isNull(); // 좌석 경쟁 전에 걸림

			// Redis를 잃어도 DB 카운터(20)로 복원해 같은 판정 (§10.4)
			redis.execute((RedisCallback<Object>) c -> {
				c.serverCommands().flushDb();
				return null;
			});
			assertThat(holdOutcomeOn(third, m, seatIds.get(210))).isEqualTo(SeatErrorCode.LIMIT_PER_DAY.code());
			assertThat(count("SELECT seat_count FROM purchase_counter WHERE scope = 'PER_DAY' AND ci_hash = ?", ci(m))).isEqualTo(20);
			// 다른 사람은 그 운행을 산다 (거절이 운행이 아니라 이 CI의 하루 합 때문)
			assertThat(holdOutcomeOn(third, newMember(), seatIds.get(210))).isEqualTo("OK");
		} finally {
			cleanDb();
			dropTempRun(third);
		}
	}

	// ------------------------------------------------------------ 도우미

	static final long TEMP_RUN = 9901;

	/** 9001과 같은 열차·정차역으로 상시 판매 운행 하나를 만든다. 날짜는 그 열차가 비어 있는 날 (UQ(train_id, run_date)) */
	private long createTempRun() {
		dropTempRun(TEMP_RUN);
		for (int d = 1; d <= 120; d++) {
			long taken = count("""
					SELECT COUNT(*) FROM train_run t, train_run b
					 WHERE b.run_id = ? AND t.train_id = b.train_id AND t.run_date = b.run_date + ?""", RUN, d);
			if (taken > 0) {
				continue;
			}
			jdbc.update("""
					INSERT INTO train_run (run_id, train_id, sale_event_id, run_date, sale_open_at, sale_close_at, status)
					SELECT ?, train_id, NULL, run_date + ?, sale_open_at, sale_close_at + NUMTODSINTERVAL(?, 'DAY'), 'ON_SALE'
					  FROM train_run WHERE run_id = ?""", TEMP_RUN, d, d, RUN);
			jdbc.update("""
					INSERT INTO train_stop (run_id, station_id, stop_order, arrival_time, departure_time)
					SELECT ?, station_id, stop_order, arrival_time + NUMTODSINTERVAL(?, 'DAY'), departure_time + NUMTODSINTERVAL(?, 'DAY')
					  FROM train_stop WHERE run_id = ?""", TEMP_RUN, d, d, RUN);
			return TEMP_RUN;
		}
		throw new IllegalStateException("9001 열차에 빈 날짜가 없습니다");
	}

	private void dropTempRun(long runId) {
		jdbc.update("DELETE FROM train_stop WHERE run_id = ?", runId);
		jdbc.update("DELETE FROM train_run WHERE run_id = ?", runId);
	}

	private static HoldCommand cmdOn(long runId, List<Long> seats) {
		return new HoldCommand(runId, FROM, TO, seats.stream().map(s -> new SeatRequest(s, "ADULT")).toList(), "WEB",
				UUID.randomUUID().toString());
	}

	private static HoldCommand cmdOn9002(List<Long> seats) {
		return new HoldCommand(9002, TO, FROM, seats.stream().map(s -> new SeatRequest(s, "ADULT")).toList(), "WEB",
				UUID.randomUUID().toString());
	}

	private String holdOutcomeOn(long runId, long memberId, long seat) {
		try {
			service.hold(memberId, cmdOn(runId, List.of(seat)));
			return "OK";
		} catch (BusinessException e) {
			return e.getErrorCode().code();
		}
	}

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

	/** 선점 한 건: 성공이면 "OK", 업무 거절이면 오류 코드, 그 밖의 예외(데드락 ORA-00060 등)는 예외 이름과 메시지 */
	private String holdOutcome(long memberId, long... seats) {
		try {
			service.hold(memberId, cmd(UUID.randomUUID().toString(), seats));
			return "OK";
		} catch (BusinessException e) {
			return e.getErrorCode().code();
		} catch (RuntimeException e) {
			return e.getClass().getSimpleName() + ": " + e.getMessage();
		}
	}

	/** 모든 호출을 같은 순간에 출발시키고 결과를 모은다 (30초 안에 끝나지 않으면 실패 — 데드락이 풀리지 않는 경우) */
	private static List<String> race(List<Callable<String>> calls) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(calls.size());
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> futures = new ArrayList<>();
		for (Callable<String> call : calls) {
			futures.add(pool.submit(() -> {
				start.await();
				return call.call();
			}));
		}
		start.countDown();
		List<String> out = new ArrayList<>();
		try {
			for (Future<String> f : futures) {
				out.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
			}
		} finally {
			pool.shutdownNow();
		}
		return out;
	}

	private String statusOf(long reservationId) {
		return jdbc.queryForObject("SELECT status FROM reservation WHERE reservation_id = ?", String.class, reservationId);
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
