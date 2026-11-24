package com.jejari.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jejari.common.error.BusinessException;
import com.jejari.seat.SeatHoldService;
import com.jejari.seat.SeatHoldService.HoldCommand;
import com.jejari.seat.SeatHoldService.SeatRequest;
import com.jejari.support.NoRunningApp;

/**
 * 도용 카드 — 카드 한 장에 붙는 서로 다른 명의 수 상한 (concurrency.md §4.3, ERD v37 card_lock).
 *
 * <p>상한은 기본 끔(0)이다. 시연 버튼·k6·대회 안내가 같은 가상 카드번호를 써서 켜면 막히기 때문이다.
 * 그래서 이 테스트가 켜고(app_policy) 끝나면 끈다. 카드번호는 테스트마다 새로 만들어, 다른 테스트가 남긴
 * 같은 카드 연결과 섞이지 않게 한다 (모의 PG는 끝자리 1이면 승인).
 */
@Tag("infra")
@ExtendWith(NoRunningApp.class)
@SpringBootTest(properties = {
		"spring.data.redis.database=15",
		"jejari.queue.scheduler-enabled=false",
		"jejari.risk.cache-resync-enabled=false",
		"jejari.seat.expiry-scanner-enabled=false",
		"jejari.payment.recheck-scanner-enabled=false",
		"jejari.event.relay.enabled=false"})
class CardLinkLimitInfraTest {

	static final long RUN = 9001;
	static final String FROM = "3900023";
	static final String TO = "3900114";
	/** 다른 통합 테스트와 겹치지 않는 회원 대역 (915001~915999) */
	static final long MEMBER_BASE = 915_000;
	static final AtomicLong NEXT_MEMBER = new AtomicLong(MEMBER_BASE + 1);
	static final String KEY = PaymentPolicy.MAX_CI_PER_CARD;

	@Autowired
	PaymentService payments;
	@Autowired
	PaymentPolicy policy;
	@Autowired
	SeatHoldService hold;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	DataSource dataSource;
	@Autowired
	StringRedisTemplate redis;

	List<Long> seatIds;
	int seatCursor;

	@BeforeEach
	void setUp() {
		cleanDb();
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		// 다른 결제 테스트는 앞·가운데 좌석을 쓴다. 겹치지 않게 뒤에서부터
		seatIds = jdbc.queryForList("SELECT seat_id FROM seat WHERE train_id = 1 ORDER BY seat_id DESC", Long.class);
		seatCursor = 0;
		setMax(3);
	}

	@AfterEach
	void tearDown() {
		setMax(0);
		cleanDb();
	}

	// ------------------------------------------------------------ 기본 동작

	@Test
	void 끄면_같은_카드를_명의_몇_개든_붙일_수_있다() {
		setMax(0);
		String card = newCard();
		for (int i = 0; i < 5; i++) {
			long m = newMember();
			assertThat(payments.start(m, holdSeat(m), key(), card)).isNotNull();
		}
		assertThat(linkedCis(card)).isEqualTo(5);
	}

	@Test
	void 켜면_서로_다른_명의_세_개까지_붙고_네_번째는_거절된다() {
		String card = newCard();
		for (int i = 0; i < 3; i++) {
			long m = newMember();
			assertThat(payments.start(m, holdSeat(m), key(), card)).isNotNull();
		}

		long fourth = newMember();
		long r = holdSeat(fourth);
		assertThat(code(() -> payments.start(fourth, r, key(), card))).isEqualTo("PAYMENT_CARD_LINK_LIMIT");

		// 결제 시작 전체가 되돌아가 예약은 HOLD 그대로다 → 다른 카드로 다시 결제할 수 있다
		assertThat(status(r)).isEqualTo("HOLD");
		assertThat(linkedCis(card)).isEqualTo(3);
		assertThat(payments.start(fourth, r, key(), newCard())).isNotNull();
		assertThat(status(r)).isIn("PAYMENT_PENDING", "CONFIRMED");
	}

	// ------------------------------------------------------------ 계정이 아니라 명의로 센다

	@Test
	void 같은_명의의_다른_계정은_한_명으로_센다() {
		// 한 사람이 계정을 여러 개 갖는 것은 막지 않는다 (member.ci_hash는 유일하지 않음). 본인 카드를 본인 계정들에 쓰는 것은 정상
		String card = newCard();
		String sameCi = "card-ci-" + UUID.randomUUID();
		for (int i = 0; i < 3; i++) {
			long m = newMember(sameCi);
			assertThat(payments.start(m, holdSeat(m), key(), card)).isNotNull();
		}
		long other1 = newMember();
		long other2 = newMember();
		assertThat(payments.start(other1, holdSeat(other1), key(), card)).isNotNull();
		assertThat(payments.start(other2, holdSeat(other2), key(), card)).isNotNull();

		assertThat(linkedCis(card)).isEqualTo(3); // 계정 5개, 명의 3개
	}

	@Test
	void 이미_붙은_명의는_상한이_차도_다른_계정으로_다시_쓴다() {
		String card = newCard();
		String ci = "card-ci-" + UUID.randomUUID();
		long first = newMember(ci);
		assertThat(payments.start(first, holdSeat(first), key(), card)).isNotNull();
		for (int i = 0; i < 2; i++) {
			long m = newMember();
			assertThat(payments.start(m, holdSeat(m), key(), card)).isNotNull();
		}
		// 상한(3)이 찼다. 그래도 이미 붙은 명의의 새 계정은 통과한다
		long sameCiNewAccount = newMember(ci);
		assertThat(payments.start(sameCiNewAccount, holdSeat(sameCiNewAccount), key(), card)).isNotNull();
	}

	// ------------------------------------------------------------ 동시 요청

	@Test
	void 처음_보는_카드로_명의_열_개가_동시에_결제를_시작해도_세_개만_붙는다() throws Exception {
		// 이 기능이 막으려는 바로 그 모양: 새로 훔친 카드로 계정 여러 개가 동시에 결제를 시작한다.
		// 잠금 행(card_lock) 없이 "센 뒤 넣기"만 하면 모두 0명으로 세고 전부 통과한다
		String card = newCard();
		int n = 10;
		long[] members = new long[n];
		long[] reservations = new long[n];
		for (int i = 0; i < n; i++) {
			members[i] = newMember();
			reservations[i] = holdSeat(members[i]);
		}
		warmPool(n); // 테스트 풀 최대가 10이라 딱 n개

		List<Object> results = concurrently(n, i -> payments.start(members[i], reservations[i], key(), card));

		long ok = results.stream().filter(o -> !(o instanceof String)).count();
		long refused = results.stream().filter("PAYMENT_CARD_LINK_LIMIT"::equals).count();
		assertThat(ok).isEqualTo(3);
		assertThat(refused).isEqualTo(n - 3);
		assertThat(linkedCis(card)).isEqualTo(3);
	}

	// ------------------------------------------------------------ 도우미

	/**
	 * DB 연결을 n개 미리 열어 둔다 — <b>안 하면 이 시험이 동시성을 못 만든다.</b>
	 *
	 * <p>테스트는 커넥션 풀이 최대 10·최소 유휴 2다 (build.gradle — 컨텍스트가 20여 개라 운영 값 20이면
	 * Oracle Free 연결 한도를 넘김). 평소 2~3개만 열려 있어, 요청 10개를 쏘면 3개만 동시에 트랜잭션에 들어가고
	 * 나머지는 새 연결이 생기길 기다리다 앞의 3개가 커밋한 뒤에 들어온다. 그러면 잠금이 없어도 "3개만 통과"가
	 * 나와 시험이 늘 통과한다. 2026-11-24 돌연변이 시험으로 확인했다 — card_lock 줄 세우기를 없애도 5개 모두
	 * 통과했고, 계측해 보니 3개만 같은 순간에 셌다. 운영은 연결이 데워져 있어 요청이 진짜로 동시에 들어오므로
	 * 시험도 그 조건을 만든다.
	 *
	 * <p>한꺼번에 n개를 달라고 하면 Hikari가 하나씩 만드는 동안 대기 한도(connection-timeout 5초)를 넘긴다.
	 * 그래서 k개를 동시에 쥐는 일을 k = 2, 3, …, n으로 늘려 가며 한 단계에 새 연결 하나씩만 생기게 한다.
	 * 데운 연결은 idle-timeout(기본 10분)까지 남아 바로 뒤의 시험에 쓰인다.
	 */
	private void warmPool(int n) throws Exception {
		HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
		for (int k = 2; k <= n && pool.getTotalConnections() < n; k++) {
			CyclicBarrier together = new CyclicBarrier(k);
			concurrently(k, i -> jdbc.execute((ConnectionCallback<Object>) c -> {
				try {
					together.await(20, TimeUnit.SECONDS); // k개를 같은 순간에 쥐어야 풀이 k개로 는다
				} catch (Exception e) {
					throw new IllegalStateException("연결을 동시에 쥐지 못함", e);
				}
				return null;
			}));
		}
		if (pool.getTotalConnections() < n) {
			throw new IllegalStateException("커넥션 풀이 " + n + "개까지 차지 않음 (지금 " + pool.getTotalConnections() + ")");
		}
	}

	/** app_policy를 바꾸고, 정책 캐시(1초)가 새 값을 볼 때까지 기다린다 */
	private void setMax(long v) {
		jdbc.update("""
				MERGE INTO app_policy t USING (SELECT ? AS k, ? AS v FROM dual) s ON (t.policy_key = s.k)
				 WHEN MATCHED THEN UPDATE SET t.policy_value = s.v, t.version = t.version + 1, t.updated_at = SYSTIMESTAMP
				 WHEN NOT MATCHED THEN INSERT (policy_key, policy_value, version, value_type, description)
				      VALUES (s.k, s.v, 1, 'NUMBER', '카드 한 장에 붙을 수 있는 서로 다른 명의 수 (테스트가 만듦)')""",
				KEY, String.valueOf(v));
		long until = System.nanoTime() + 3_000_000_000L;
		while (policy.getLong(KEY) != v) {
			if (System.nanoTime() > until) {
				throw new AssertionError("정책 캐시가 " + v + "로 바뀌지 않음");
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	/** 이 카드에 붙은 서로 다른 명의 수 (테스트 회원만) */
	private int linkedCis(String card) {
		return jdbc.queryForObject("""
				SELECT COUNT(DISTINCT m.ci_hash) FROM payment_method pm JOIN member m ON m.member_id = pm.member_id
				 WHERE pm.card_last4 = ? AND m.member_id BETWEEN ? AND ?""",
				Integer.class, card.substring(card.length() - 4), MEMBER_BASE + 1, MEMBER_BASE + 999);
	}

	private String status(long reservationId) {
		return jdbc.queryForObject("SELECT status FROM reservation WHERE reservation_id = ?", String.class, reservationId);
	}

	/** 다른 테스트의 연결과 섞이지 않는 새 가상 카드. 끝 4자리도 테스트끼리 겹치지 않게 무작위 (끝자리 1 = 승인) */
	private static String newCard() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		return "4" + String.format("%011d", r.nextLong(100_000_000_000L)) + String.format("%03d", r.nextInt(1000)) + "1";
	}

	private long newMember() {
		return newMember("card-ci-" + UUID.randomUUID());
	}

	private long newMember(String ci) {
		long id = NEXT_MEMBER.getAndIncrement();
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
				id, "card" + id + "@infra-test.jejari.dev", ci, "테스트");
		return id;
	}

	private long holdSeat(long memberId) {
		long seatId = seatIds.get(seatCursor++);
		return hold.hold(memberId, new HoldCommand(RUN, FROM, TO, List.of(new SeatRequest(seatId, "ADULT")), "WEB",
				key())).reservationId();
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

	private interface Task {
		Object run(int i) throws Exception;
	}

	/** 동시에 출발시켜 결과(성공 값 또는 실패 코드)를 모은다 */
	private static List<Object> concurrently(int n, Task task) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(n);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<Object>> fs = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				int idx = i;
				Callable<Object> c = () -> {
					go.await();
					try {
						return task.run(idx);
					} catch (BusinessException e) {
						return e.getErrorCode().code();
					}
				};
				fs.add(pool.submit(c));
			}
			go.countDown();
			List<Object> out = new ArrayList<>();
			for (Future<Object> f : fs) {
				out.add(f.get());
			}
			return out;
		} finally {
			pool.shutdownNow();
		}
	}

	private static String code(Runnable call) {
		try {
			call.run();
		} catch (BusinessException e) {
			return e.getErrorCode().code();
		}
		throw new AssertionError("BusinessException이 나야 함");
	}

	private void cleanDb() {
		long lo = MEMBER_BASE + 1;
		long hi = MEMBER_BASE + 999;
		String mine = "SELECT reservation_id FROM reservation WHERE member_id BETWEEN " + lo + " AND " + hi;
		jdbc.update("DELETE FROM outbox_event WHERE aggregate_type = 'RESERVATION' AND aggregate_id IN (SELECT TO_CHAR(reservation_id) FROM reservation WHERE member_id BETWEEN ? AND ?)",
				lo, hi);
		jdbc.update("DELETE FROM cancellation WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM payment WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM payment_method WHERE member_id BETWEEN ? AND ?", lo, hi);
		jdbc.update("DELETE FROM reservation_history WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM saga_instance WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation_seat WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation WHERE member_id BETWEEN ? AND ?", lo, hi);
		jdbc.update("DELETE FROM purchase_counter WHERE ci_hash LIKE 'card-ci-%'");
		jdbc.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?", lo, hi);
		// 연결이 하나도 남지 않은 카드의 잠금 행은 뜻이 없다 (이 테스트가 만든 새 카드들)
		jdbc.update("DELETE FROM card_lock c WHERE NOT EXISTS (SELECT 1 FROM payment_method pm WHERE pm.method_hash = c.method_hash)");
	}
}
