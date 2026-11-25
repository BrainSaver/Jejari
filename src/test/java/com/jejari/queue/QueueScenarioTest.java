package com.jejari.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jejari.common.error.BusinessException;
import com.jejari.queue.domain.QueueAdmitter;
import com.jejari.queue.domain.QueuePassService;
import com.jejari.queue.domain.QueueService;
import com.jejari.queue.domain.QueueStatus;
import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;
import com.jejari.queue.port.QueueLogSink.QueueLogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 대기열 전체 시나리오(진입 → 번호표 → 입장 → 토큰 → 이탈)를 실제 Redis로 검증하는 확장 테스트.
 * {@code ./gradlew infraTest}로 돌립니다. 시간은 Redis 시계라서 패널티 단가를 크게 잡아 "항상 뒤"를 보장합니다.
 */
class QueueScenarioTest extends QueueRedisSupport {

	@Autowired QueueService queue;
	@Autowired QueueAdmitter admitter;
	@Autowired QueuePassService pass;

	// ---------- 도우미 ----------

	private void setRisk(String type, String hash, String score) {
		redis.opsForValue().set("risk:" + type + ":" + hash, score);
	}

	private void setPolicy(String key, String value) {
		redis.opsForHash().put("policy:cache", key, value);
	}

	private void enterAll(long from, long to) {
		for (long m = from; m <= to; m++) {
			queue.enter(m, RUN);
		}
	}

	private MemberIdentity register(long id, String status, String ci) {
		MemberIdentity m = new MemberIdentity(id, status, ci, List.of(), List.of());
		members.register(m);
		return m;
	}

	private String tokenOf(long memberId) {
		QueueStatus s = queue.status(memberId, RUN);
		assertThat(s.state()).isEqualTo(QueueStatus.State.ADMITTED);
		return s.passToken();
	}

	private long zcard(long run) {
		Long n = redis.opsForZSet().zCard("queue:" + run);
		return n == null ? 0 : n;
	}

	private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Object code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(code));
	}

	// =====================================================================
	@Nested
	@DisplayName("입장 인원과 순서")
	class AdmitCountAndOrder {

		@ParameterizedTest(name = "대기 {1}명, 초당 {0}명 → {2}명 입장")
		@CsvSource({"1,10,1", "5,10,5", "10,10,10", "50,10,10", "200,10,10", "3,1,1", "7,0,0", "1,500,1", "100,250,100", "200,200,200"})
		void 정책_인원만큼_입장한다(int perSec, int waiting, int expected) {
			setPolicy("queue.admit_per_sec", String.valueOf(perSec));
			if (waiting > 0) {
				enterAll(1, waiting);
			}
			assertThat(admitter.admit(RUN)).isEqualTo(expected);
			assertThat(zcard(RUN)).isEqualTo(waiting - expected);
			Long active = redis.opsForZSet().zCard("queue:active:" + RUN);
			assertThat(active == null ? 0 : active).isEqualTo(expected);
		}

		@Test
		void 맨_앞_사람부터_입장하고_뒤_사람은_순번이_당겨진다() {
			setPolicy("queue.admit_per_sec", "3");
			enterAll(1, 10);
			admitter.admit(RUN);

			for (long m = 1; m <= 3; m++) {
				assertThat(queue.status(m, RUN).state()).isEqualTo(QueueStatus.State.ADMITTED);
			}
			for (long m = 4; m <= 10; m++) {
				QueueStatus s = queue.status(m, RUN);
				assertThat(s.state()).isEqualTo(QueueStatus.State.WAITING);
				assertThat(s.rank()).isEqualTo(m - 3);
				assertThat(s.ahead()).isEqualTo(m - 4);
			}
		}

		@Test
		void 여러_번_돌리면_모두_한_번씩만_입장하고_이후는_0명이다() {
			setPolicy("queue.admit_per_sec", "4");
			enterAll(1, 10);
			assertThat(List.of(admitter.admit(RUN), admitter.admit(RUN), admitter.admit(RUN), admitter.admit(RUN)))
					.containsExactly(4, 4, 2, 0);
			assertThat(zcard(RUN)).isZero();
		}

		@Test
		void 빈_줄에서_입장_처리해도_오류_없이_0명이다() {
			assertThat(admitter.admit(RUN)).isZero();
			assertThat(admitter.admit(99999)).isZero();
		}

		@Test
		void 입장_처리는_초당_입장_수를_처리량_키에_남긴다() {
			setPolicy("queue.admit_per_sec", "17");
			admitter.admit(RUN);
			assertThat(redis.opsForValue().get("queue:throughput:" + RUN)).isEqualTo("17");
		}

		@Test
		void 입장한_순서대로_ADMITTED_기록이_남는다() {
			setPolicy("queue.admit_per_sec", "5");
			enterAll(1, 5);
			admitter.admit(RUN);
			List<Long> order = sink.events.stream().filter(e -> e.type() == QueueLogEvent.Type.ADMITTED)
					.map(QueueLogEvent::memberId).collect(Collectors.toList());
			assertThat(order).containsExactly(1L, 2L, 3L, 4L, 5L);
		}

		@Test
		void 운행마다_번호표와_줄이_따로다() {
			runs.open(2002);
			queue.enter(1, RUN);
			queue.enter(2, RUN);
			QueueStatus other = queue.enter(3, 2002);
			assertThat(other.ticketNo()).isEqualTo(1); // 운행 2002의 첫 번호
			assertThat(other.rank()).isEqualTo(1);
			assertThat(queue.status(2, RUN).ticketNo()).isEqualTo(2);
			assertThat(zcard(RUN)).isEqualTo(2);
			assertThat(zcard(2002)).isEqualTo(1);
		}

		@Test
		void 한_운행을_입장_처리해도_다른_운행은_그대로다() {
			runs.open(2002);
			enterAll(1, 3);
			queue.enter(10, 2002);
			setPolicy("queue.admit_per_sec", "10");
			admitter.admit(RUN);
			assertThat(queue.status(10, 2002).state()).isEqualTo(QueueStatus.State.WAITING);
			assertThat(zcard(2002)).isEqualTo(1);
		}
	}

	// =====================================================================
	@Nested
	@DisplayName("예상 대기 시간")
	class ExpectedWait {

		@Test
		void 처리량이_1이면_순번만큼_초가_걸리고_조회_간격은_1에서_5초다() {
			redis.opsForValue().set("queue:throughput:" + RUN, "1");
			enterAll(1, 60);
			long[][] expect = {{1, 1, 1}, {10, 10, 1}, {11, 11, 2}, {25, 25, 3}, {41, 41, 5}, {60, 60, 5}};
			for (long[] e : expect) {
				QueueStatus s = queue.status(e[0], RUN);
				assertThat(s.estimatedWaitSec()).as("rank %d eta", e[0]).isEqualTo(e[1]);
				assertThat(s.pollIntervalSec()).as("rank %d poll", e[0]).isEqualTo((int) e[2]);
			}
		}

		@ParameterizedTest(name = "처리량 {0}/초, 순번 {1} → {2}초")
		@CsvSource({"10,1,1", "10,10,1", "10,11,2", "10,25,3", "100,100,1", "100,101,2", "3,7,3", "7,50,8"})
		void 올림으로_계산한다(int perSec, int rank, int eta) {
			redis.opsForValue().set("queue:throughput:" + RUN, String.valueOf(perSec));
			enterAll(1, rank);
			assertThat(queue.status(rank, RUN).estimatedWaitSec()).isEqualTo(eta);
		}

		@Test
		void 처리량_기록이_없으면_정책값을_쓴다() {
			setPolicy("queue.admit_per_sec", "50");
			enterAll(1, 120);
			assertThat(queue.status(120, RUN).estimatedWaitSec()).isEqualTo(3);
		}

		@Test
		void 처리량이_0이거나_음수여도_0으로_나누지_않는다() {
			setPolicy("queue.admit_per_sec", "20");
			redis.opsForValue().set("queue:throughput:" + RUN, "0");
			enterAll(1, 40);
			assertThat(queue.status(40, RUN).estimatedWaitSec()).isEqualTo(2);
			redis.opsForValue().set("queue:throughput:" + RUN, "-5");
			assertThat(queue.status(40, RUN).estimatedWaitSec()).isEqualTo(2);
		}

		@Test
		void 진입_응답과_이후_조회가_같은_값을_준다() {
			redis.opsForValue().set("queue:throughput:" + RUN, "5");
			enterAll(1, 12);
			QueueStatus entered = queue.enter(13, RUN);
			QueueStatus looked = queue.status(13, RUN);
			assertThat(entered.estimatedWaitSec()).isEqualTo(looked.estimatedWaitSec());
			assertThat(entered.rank()).isEqualTo(looked.rank());
			assertThat(entered.pollIntervalSec()).isEqualTo(looked.pollIntervalSec());
		}
	}

	// =====================================================================
	@Nested
	@DisplayName("위험 점수와 순번")
	class RiskScoreAndRank {

		@ParameterizedTest(name = "점수 \"{0}\" → 패널티 {1}ms")
		@CsvSource({"0,0", "1,1000", "12.5,12500", "50,50000", "99,99000", "100,100000"})
		void 점수가_패널티_시간으로_기록된다(String score, long penaltyMs) {
			register(1, "ACTIVE", "ci-1");
			setRisk("CI", "ci-1", score);
			queue.enter(1, RUN);
			assertThat(sink.events.get(0).penaltyMs()).isEqualTo(penaltyMs);
		}

		@ParameterizedTest(name = "오염 값 \"{0}\" → 100점")
		@ValueSource(strings = {"abc", "NaN", "-3", "101", "1e2", "0x10", "80d", ""})
		void 오염된_점수는_100점으로_처리한다(String raw) {
			register(1, "ACTIVE", "ci-1");
			setRisk("CI", "ci-1", raw);
			queue.enter(1, RUN);
			assertThat(sink.events.get(0).riskScore()).isEqualTo(100.0);
			assertThat(sink.events.get(0).penaltyMs()).isEqualTo(100_000);
		}

		@Test
		void 위험_점수_순서대로_입장한다() {
			// 점수 차이(10점 = 10초)가 테스트 실행 시간보다 훨씬 커서 항상 점수 순서가 된다
			int[] scores = {70, 0, 40, 100, 10, 90, 20, 60, 30, 50};
			for (int i = 0; i < scores.length; i++) {
				long id = i + 1;
				register(id, "ACTIVE", "ci-" + id);
				setRisk("CI", "ci-" + id, String.valueOf(scores[i]));
				queue.enter(id, RUN);
			}
			setPolicy("queue.admit_per_sec", "10");
			admitter.admit(RUN);
			List<Long> admitted = sink.events.stream().filter(e -> e.type() == QueueLogEvent.Type.ADMITTED)
					.map(QueueLogEvent::memberId).collect(Collectors.toList());
			List<Long> expected = IntStream.range(0, scores.length).boxed()
					.sorted((a, b) -> Integer.compare(scores[a], scores[b])).map(i -> (long) i + 1).collect(Collectors.toList());
			assertThat(admitted).isEqualTo(expected);
		}

		@ParameterizedTest(name = "무작위 시드 {0}")
		@ValueSource(longs = {1, 2, 3, 42, 777})
		void 무작위_점수에서도_점수_오름차순_순번이다(long seed) {
			Random rnd = new Random(seed);
			int n = 25;
			int[] scores = new int[n + 1];
			Set<Integer> used = new HashSet<>();
			for (int id = 1; id <= n; id++) {
				int s;
				do {
					s = rnd.nextInt(101);
				} while (!used.add(s)); // 서로 다른 점수만 (동점은 따로 테스트)
				scores[id] = s;
				register(id, "ACTIVE", "ci-" + id);
				setRisk("CI", "ci-" + id, String.valueOf(s));
				queue.enter(id, RUN);
			}
			List<Integer> byRank = IntStream.rangeClosed(1, n).boxed()
					.sorted((a, b) -> Long.compare(queue.status(a, RUN).rank(), queue.status(b, RUN).rank()))
					.collect(Collectors.toList());
			for (int i = 1; i < byRank.size(); i++) {
				assertThat(scores[byRank.get(i)]).isGreaterThan(scores[byRank.get(i - 1)]);
			}
		}

		@Test
		void 점수가_같으면_먼저_온_사람이_먼저다() {
			for (long id = 1; id <= 8; id++) {
				register(id, "ACTIVE", "ci-" + id);
				setRisk("CI", "ci-" + id, "30");
				queue.enter(id, RUN);
			}
			for (long id = 1; id <= 8; id++) {
				assertThat(queue.status(id, RUN).rank()).isEqualTo(id);
			}
		}

		@Test
		void 결제수단_점수만_높아도_뒤로_밀린다() {
			members.register(new MemberIdentity(1, "ACTIVE", "ci-1", List.of("pay-1"), List.of()));
			setRisk("PAYMENT", "pay-1", "95");
			queue.enter(1, RUN);
			queue.enter(2, RUN);
			queue.enter(3, RUN);
			assertThat(queue.status(1, RUN).rank()).isEqualTo(3);
		}

		@Test
		void 기기_점수만_높아도_뒤로_밀린다() {
			members.register(new MemberIdentity(1, "ACTIVE", "ci-1", List.of(), List.of("dev-1")));
			setRisk("DEVICE", "dev-1", "95");
			queue.enter(1, RUN);
			queue.enter(2, RUN);
			assertThat(queue.status(1, RUN).rank()).isEqualTo(2);
		}

		@Test
		void 여러_기기와_결제수단_중_가장_높은_점수를_쓴다() {
			members.register(new MemberIdentity(1, "ACTIVE", "ci-1", List.of("p1", "p2", "p3"), List.of("d1", "d2")));
			setRisk("PAYMENT", "p2", "61");
			setRisk("DEVICE", "d1", "88.5");
			setRisk("DEVICE", "d2", "20");
			queue.enter(1, RUN);
			assertThat(sink.events.get(0).riskScore()).isEqualTo(88.5);
		}

		@Test
		void 점수가_없는_회원은_패널티가_없다() {
			queue.enter(1, RUN);
			assertThat(sink.events.get(0).penaltyMs()).isZero();
			assertThat(sink.events.get(0).riskScore()).isZero();
		}

		@Test
		void RESTRICTED도_줄에는_설_수_있지만_일반_회원_뒤다() {
			register(1, "RESTRICTED", "ci-1");
			queue.enter(1, RUN);
			enterAll(2, 6);
			assertThat(queue.status(1, RUN).rank()).isEqualTo(6);
		}

		@Test
		void 패널티_단가를_0으로_하면_모두_도착_순이다() {
			setPolicy("risk.penalty_ms_per_point", "0");
			register(1, "ACTIVE", "ci-1");
			setRisk("CI", "ci-1", "100");
			queue.enter(1, RUN);
			queue.enter(2, RUN);
			assertThat(queue.status(1, RUN).rank()).isEqualTo(1);
		}

		@Test
		void 동기화_표시_키가_있으면_원본_조회_대신_Redis_값을_쓴다() {
			register(1, "ACTIVE", "ci-1");
			redis.opsForValue().set("risk:meta:synced_at", "2026-11-20T00:00:00Z");
			setRisk("CI", "ci-1", "40");
			queue.enter(1, RUN);
			assertThat(sink.events.get(0).riskScore()).isEqualTo(40.0);
		}

		@Test
		void 진입_기록의_조정_전후_순번은_밀린_만큼_차이가_난다() {
			enterAll(1, 3);
			register(4, "ACTIVE", "ci-4");
			setRisk("CI", "ci-4", "100");
			queue.enter(4, RUN);
			QueueLogEvent e = sink.events.stream().filter(x -> x.memberId() == 4).findFirst().orElseThrow();
			assertThat(e.initialRank()).isEqualTo(4);
			assertThat(e.adjustedRank()).isEqualTo(4); // 100초 뒤 점수라 뒤에 올 사람도 앞서므로 지금은 맨 뒤
			enterAll(5, 8);
			assertThat(queue.status(4, RUN).rank()).isEqualTo(8);
		}
	}

	// =====================================================================
	@Nested
	@DisplayName("중복 진입 방어")
	class DuplicateEntryGuard {

		@Test
		void 같은_운행_재진입은_번호표를_새로_받지_않는다() {
			QueueStatus a = queue.enter(1, RUN);
			QueueStatus b = queue.enter(1, RUN);
			QueueStatus c = queue.enter(1, RUN);
			assertThat(List.of(b.ticketNo(), c.ticketNo())).containsOnly(a.ticketNo());
			assertThat(zcard(RUN)).isEqualTo(1);
			assertThat(sink.events.stream().filter(e -> e.type() == QueueLogEvent.Type.ENTERED)).hasSize(1);
		}

		@Test
		void 재진입해도_뒷사람_순번은_변하지_않는다() {
			queue.enter(1, RUN);
			queue.enter(2, RUN);
			queue.enter(1, RUN);
			assertThat(queue.status(2, RUN).rank()).isEqualTo(2);
		}

		@Test
		void 다른_운행_대기_중이면_QUEUE_DUPLICATED이고_기록이_남는다() {
			runs.open(2002);
			queue.enter(1, RUN);
			assertCode(() -> queue.enter(1, 2002), QueueErrorCode.QUEUE_DUPLICATED);
			assertThat(sink.events.stream().filter(e -> e.type() == QueueLogEvent.Type.REJECTED_DUPLICATE)).hasSize(1);
			assertThat(zcard(2002)).isZero();
		}

		@Test
		void 거절돼도_원래_줄의_순번은_그대로다() {
			runs.open(2002);
			queue.enter(1, RUN);
			queue.enter(2, RUN);
			assertCode(() -> queue.enter(1, 2002), QueueErrorCode.QUEUE_DUPLICATED);
			assertThat(queue.status(1, RUN).rank()).isEqualTo(1);
			assertThat(queue.status(2, RUN).rank()).isEqualTo(2);
		}

		@Test
		void 같은_CI의_다른_계정은_거절된다() {
			register(1, "ACTIVE", "same-ci");
			register(2, "ACTIVE", "same-ci");
			queue.enter(1, RUN);
			assertCode(() -> queue.enter(2, RUN), QueueErrorCode.QUEUE_DUPLICATED);
			assertThat(zcard(RUN)).isEqualTo(1);
		}

		@Test
		void 같은_CI의_다른_계정은_다른_운행으로도_못_들어간다() {
			runs.open(2002);
			register(1, "ACTIVE", "same-ci");
			register(2, "ACTIVE", "same-ci");
			queue.enter(1, RUN);
			assertCode(() -> queue.enter(2, 2002), QueueErrorCode.QUEUE_DUPLICATED);
		}

		@Test
		void 같은_CI_계정이_세_개여도_하나만_선다() {
			for (long id = 1; id <= 3; id++) {
				register(id, "ACTIVE", "same-ci");
			}
			queue.enter(1, RUN);
			assertCode(() -> queue.enter(2, RUN), QueueErrorCode.QUEUE_DUPLICATED);
			assertCode(() -> queue.enter(3, RUN), QueueErrorCode.QUEUE_DUPLICATED);
		}

		@Test
		void 이탈하면_같은_CI의_다른_계정이_들어갈_수_있다() {
			register(1, "ACTIVE", "same-ci");
			register(2, "ACTIVE", "same-ci");
			queue.enter(1, RUN);
			queue.leave(1, RUN);
			assertThat(queue.enter(2, RUN).rank()).isEqualTo(1);
		}

		@Test
		void 입장한_뒤에도_같은_CI의_다른_계정은_막힌다() {
			register(1, "ACTIVE", "same-ci");
			register(2, "ACTIVE", "same-ci");
			queue.enter(1, RUN);
			admitter.admit(RUN);
			assertCode(() -> queue.enter(2, RUN), QueueErrorCode.QUEUE_DUPLICATED);
		}

		@Test
		void 토큰이_만료되면_같은_CI의_다른_계정이_들어갈_수_있다() throws Exception {
			setPolicy("queue.pass_ttl_sec", "1");
			register(1, "ACTIVE", "same-ci");
			register(2, "ACTIVE", "same-ci");
			queue.enter(1, RUN);
			admitter.admit(RUN);
			Thread.sleep(1_300);
			assertThat(queue.enter(2, RUN).state()).isEqualTo(QueueStatus.State.WAITING);
		}

		@Test
		void CI가_다르면_동시에_여러_명이_설_수_있다() {
			enterAll(1, 50);
			assertThat(zcard(RUN)).isEqualTo(50);
		}

		@Test
		void 줄을_서본_회원은_이탈_후_다른_운행에_들어갈_수_있다() {
			runs.open(2002);
			queue.enter(1, RUN);
			queue.leave(1, RUN);
			assertThat(queue.enter(1, 2002).rank()).isEqualTo(1);
		}

		@Test
		void 입장한_회원이_다른_운행에_들어가려_해도_거절된다() {
			runs.open(2002);
			queue.enter(1, RUN);
			admitter.admit(RUN);
			assertCode(() -> queue.enter(1, 2002), QueueErrorCode.QUEUE_DUPLICATED);
		}
	}

	// =====================================================================
	@Nested
	@DisplayName("입장 토큰")
	class PassToken {

		@Test
		void 토큰은_입장한_회원만_운행별로_받는다() {
			enterAll(1, 3);
			setPolicy("queue.admit_per_sec", "2");
			admitter.admit(RUN);
			assertThat(redis.hasKey("queue:pass:" + RUN + ":1")).isTrue();
			assertThat(redis.hasKey("queue:pass:" + RUN + ":2")).isTrue();
			assertThat(redis.hasKey("queue:pass:" + RUN + ":3")).isFalse();
		}

		@Test
		void 입장한_모든_회원의_토큰은_서로_다르다() {
			enterAll(1, 200);
			admitter.admit(RUN);
			Set<String> tokens = new HashSet<>();
			for (long m = 1; m <= 200; m++) {
				tokens.add(tokenOf(m));
			}
			assertThat(tokens).hasSize(200);
		}

		@Test
		void 맞는_토큰만_통과한다() {
			queue.enter(1, RUN);
			admitter.admit(RUN);
			String t = tokenOf(1);
			pass.verify(RUN, 1, t);
			assertCode(() -> pass.verify(RUN, 1, t + "x"), QueueErrorCode.QUEUE_TOKEN_INVALID);
			assertCode(() -> pass.verify(RUN, 1, t.substring(1)), QueueErrorCode.QUEUE_TOKEN_INVALID);
			assertCode(() -> pass.verify(RUN, 1, t.toUpperCase()), QueueErrorCode.QUEUE_TOKEN_INVALID);
			assertCode(() -> pass.verify(RUN, 1, null), QueueErrorCode.QUEUE_TOKEN_REQUIRED);
			assertCode(() -> pass.verify(RUN, 1, ""), QueueErrorCode.QUEUE_TOKEN_REQUIRED);
			assertCode(() -> pass.verify(RUN, 1, "   "), QueueErrorCode.QUEUE_TOKEN_REQUIRED);
		}

		@Test
		void 남의_토큰은_쓸_수_없다() {
			enterAll(1, 2);
			admitter.admit(RUN);
			String t1 = tokenOf(1);
			assertCode(() -> pass.verify(RUN, 2, t1), QueueErrorCode.QUEUE_TOKEN_INVALID);
			assertCode(() -> pass.verify(RUN, 3, t1), QueueErrorCode.QUEUE_TOKEN_INVALID);
		}

		@Test
		void 다른_운행의_토큰은_쓸_수_없다() {
			runs.open(2002);
			queue.enter(1, RUN);
			admitter.admit(RUN);
			String t = tokenOf(1);
			assertCode(() -> pass.verify(2002, 1, t), QueueErrorCode.QUEUE_TOKEN_INVALID);
		}

		@Test
		void 대기_중인_사람은_어떤_토큰도_못_쓴다() {
			enterAll(1, 2);
			setPolicy("queue.admit_per_sec", "1");
			admitter.admit(RUN);
			String t1 = tokenOf(1);
			assertCode(() -> pass.verify(RUN, 2, t1), QueueErrorCode.QUEUE_TOKEN_INVALID);
			assertCode(() -> pass.verify(RUN, 2, "guess"), QueueErrorCode.QUEUE_TOKEN_INVALID);
		}

		@Test
		void 토큰은_정책_시간이_지나면_사라지고_모든_흔적이_정리된다() throws Exception {
			setPolicy("queue.pass_ttl_sec", "1");
			queue.enter(1, RUN);
			admitter.admit(RUN);
			String t = tokenOf(1);
			Thread.sleep(1_300);
			assertCode(() -> pass.verify(RUN, 1, t), QueueErrorCode.QUEUE_TOKEN_INVALID);
			assertCode(() -> queue.status(1, RUN), QueueErrorCode.QUEUE_NOT_FOUND);
			assertThat(redis.hasKey("queue:member:1")).isFalse();
		}

		@Test
		void 토큰_만료_뒤에는_다시_줄을_설_수_있다() throws Exception {
			setPolicy("queue.pass_ttl_sec", "1");
			queue.enter(1, RUN);
			admitter.admit(RUN);
			Thread.sleep(1_300);
			QueueStatus again = queue.enter(1, RUN);
			assertThat(again.state()).isEqualTo(QueueStatus.State.WAITING);
			assertThat(again.ticketNo()).isEqualTo(2); // 새 번호표
		}

		@Test
		void 입장_후_재진입하면_같은_토큰과_남은_시간을_준다() {
			queue.enter(1, RUN);
			admitter.admit(RUN);
			QueueStatus a = queue.status(1, RUN);
			QueueStatus b = queue.enter(1, RUN);
			assertThat(b.state()).isEqualTo(QueueStatus.State.ADMITTED);
			assertThat(b.passToken()).isEqualTo(a.passToken());
			assertThat(b.passExpiresAt()).isNotNull();
		}

		@ParameterizedTest(name = "토큰 유효 {0}초")
		@ValueSource(ints = {2, 60, 300, 1800})
		void 토큰_남은_시간은_정책_범위_안이다(int ttlSec) {
			setPolicy("queue.pass_ttl_sec", String.valueOf(ttlSec));
			queue.enter(1, RUN);
			admitter.admit(RUN);
			Long ttlMs = redis.getExpire("queue:pass:" + RUN + ":1", java.util.concurrent.TimeUnit.MILLISECONDS);
			assertThat(ttlMs).isBetween(1L, ttlSec * 1000L);
		}

		@Test
		void 이탈하면_토큰이_즉시_무효가_된다() {
			queue.enter(1, RUN);
			admitter.admit(RUN);
			String t = tokenOf(1);
			queue.leave(1, RUN);
			assertCode(() -> pass.verify(RUN, 1, t), QueueErrorCode.QUEUE_TOKEN_INVALID);
		}

		@Test
		void 이탈_후_다시_입장하면_새_토큰이다() {
			queue.enter(1, RUN);
			admitter.admit(RUN);
			String old = tokenOf(1);
			queue.leave(1, RUN);
			queue.enter(1, RUN);
			admitter.admit(RUN);
			assertThat(tokenOf(1)).isNotEqualTo(old);
			assertCode(() -> pass.verify(RUN, 1, old), QueueErrorCode.QUEUE_TOKEN_INVALID);
		}

		@Test
		void 토큰_검증은_토큰_수명을_늘리지_않는다() {
			queue.enter(1, RUN);
			admitter.admit(RUN);
			String t = tokenOf(1);
			Long before = redis.getExpire("queue:pass:" + RUN + ":1", java.util.concurrent.TimeUnit.MILLISECONDS);
			for (int i = 0; i < 20; i++) {
				pass.verify(RUN, 1, t);
			}
			Long after = redis.getExpire("queue:pass:" + RUN + ":1", java.util.concurrent.TimeUnit.MILLISECONDS);
			assertThat(after).isLessThanOrEqualTo(before);
		}
	}

	// =====================================================================
	@Nested
	@DisplayName("이탈")
	class Leave {

		@Test
		void 줄_가운데_사람이_나가면_뒷사람이_한_칸씩_당겨진다() {
			enterAll(1, 6);
			queue.leave(3, RUN);
			long[] expected = {1, 2, 0, 3, 4, 5};
			for (int i = 0; i < expected.length; i++) {
				if (expected[i] != 0) {
					assertThat(queue.status(i + 1, RUN).rank()).isEqualTo(expected[i]);
				}
			}
			assertCode(() -> queue.status(3, RUN), QueueErrorCode.QUEUE_NOT_FOUND);
		}

		@Test
		void 이탈하면_관련_키가_모두_지워진다() {
			queue.enter(1, RUN);
			queue.leave(1, RUN);
			assertThat(redis.hasKey("queue:member:1")).isFalse();
			assertThat(zcard(RUN)).isZero();
			assertThat(redis.hasKey("queue:pass:" + RUN + ":1")).isFalse();
			assertThat(redis.opsForZSet().zCard("queue:ci:dev-ci-1")).isZero();
		}

		@Test
		void 이탈_후_재진입하면_맨_뒤_새_번호표다() {
			enterAll(1, 3);
			queue.leave(1, RUN);
			QueueStatus again = queue.enter(1, RUN);
			assertThat(again.ticketNo()).isEqualTo(4);
			assertThat(again.rank()).isEqualTo(3);
		}

		@Test
		void 줄을_서지_않았거나_두_번_이탈해도_오류가_없고_기록은_한_번이다() {
			queue.leave(1, RUN);
			queue.enter(1, RUN);
			queue.leave(1, RUN);
			queue.leave(1, RUN);
			assertThat(sink.events.stream().filter(e -> e.type() == QueueLogEvent.Type.LEFT)).hasSize(1);
		}

		@Test
		void 다른_운행_이탈_요청은_내_줄을_건드리지_않는다() {
			runs.open(2002);
			queue.enter(1, RUN);
			queue.leave(1, 2002);
			assertThat(queue.status(1, RUN).rank()).isEqualTo(1);
		}

		@Test
		void 대기_중_이탈하고_입장_처리하면_나간_사람은_입장하지_않는다() {
			enterAll(1, 3);
			queue.leave(1, RUN);
			setPolicy("queue.admit_per_sec", "10");
			assertThat(admitter.admit(RUN)).isEqualTo(2);
			assertCode(() -> queue.status(1, RUN), QueueErrorCode.QUEUE_NOT_FOUND);
			assertThat(redis.hasKey("queue:pass:" + RUN + ":1")).isFalse();
		}

		@Test
		void 모두_이탈하면_줄이_비고_입장_처리는_0명이다() {
			enterAll(1, 20);
			for (long m = 1; m <= 20; m++) {
				queue.leave(m, RUN);
			}
			assertThat(admitter.admit(RUN)).isZero();
		}

		@Test
		void 이탈한_뒤_번호표_조회는_404다() {
			queue.enter(1, RUN);
			queue.leave(1, RUN);
			assertCode(() -> queue.status(1, RUN), QueueErrorCode.QUEUE_NOT_FOUND);
		}

		@Test
		void 입장_후_이탈하면_입장_기록과_이탈_기록이_모두_남는다() {
			queue.enter(1, RUN);
			admitter.admit(RUN);
			queue.leave(1, RUN);
			List<QueueLogEvent.Type> types = sink.events.stream().map(QueueLogEvent::type).collect(Collectors.toList());
			assertThat(types).containsExactly(QueueLogEvent.Type.ENTERED, QueueLogEvent.Type.ADMITTED, QueueLogEvent.Type.LEFT);
		}
	}

	// =====================================================================
	@Nested
	@DisplayName("진입 거절")
	class EntryRejected {

		@Test
		void BANNED는_줄에_서지_못하고_아무_흔적도_없다() {
			register(1, "BANNED", "ci-1");
			assertCode(() -> queue.enter(1, RUN), com.jejari.auth.AuthErrorCode.AUTH_BANNED);
			assertThat(zcard(RUN)).isZero();
			assertThat(redis.hasKey("queue:member:1")).isFalse();
			assertThat(sink.events).isEmpty();
		}

		@ParameterizedTest(name = "운행 {0}")
		@ValueSource(longs = {0, -1, 9999, 123456789012L})
		void 열려_있지_않은_운행은_RUN_NOT_ON_SALE이다(long runId) {
			assertCode(() -> queue.enter(1, runId), QueueErrorCode.RUN_NOT_ON_SALE);
			assertThat(redis.keys("queue:*")).isEmpty();
		}

		@Test
		void 판매가_닫힌_운행에는_더_이상_못_들어가지만_기존_줄은_남는다() {
			queue.enter(1, RUN);
			runs.close(RUN);
			assertCode(() -> queue.enter(2, RUN), QueueErrorCode.RUN_NOT_ON_SALE);
			assertThat(queue.status(1, RUN).rank()).isEqualTo(1);
		}

		@Test
		void 거절된_진입은_번호표_번호를_소비하지_않는다() {
			register(1, "BANNED", "ci-1");
			assertCode(() -> queue.enter(1, RUN), com.jejari.auth.AuthErrorCode.AUTH_BANNED);
			assertCode(() -> queue.enter(1, 9999), QueueErrorCode.RUN_NOT_ON_SALE);
			assertThat(queue.enter(2, RUN).ticketNo()).isEqualTo(1);
		}
	}

	// =====================================================================
	@Nested
	@DisplayName("저장 형식")
	class StorageFormat {

		@Test
		void 줄_멤버는_12자리_seq와_회원_ID다() {
			queue.enter(42, RUN);
			Set<String> members = redis.opsForZSet().range("queue:" + RUN, 0, -1);
			assertThat(members).containsExactly("000000000001:42");
		}

		@Test
		void 대기_기록은_운행_멤버_CI_해시를_담는다() {
			queue.enter(42, RUN);
			assertThat(redis.opsForValue().get("queue:member:42")).isEqualTo(RUN + "|000000000001:42|dev-ci-42");
		}

		@Test
		void 대기_기록_TTL은_정책_시간을_따른다() {
			setPolicy("queue.entry_ttl_sec", "120");
			queue.enter(1, RUN);
			Long ms = redis.getExpire("queue:member:1", java.util.concurrent.TimeUnit.MILLISECONDS);
			assertThat(ms).isBetween(100_000L, 120_000L);
		}

		@Test
		void 줄_점수는_진입시각_더하기_패널티다() {
			register(1, "ACTIVE", "ci-1");
			setRisk("CI", "ci-1", "50");
			long before = System.currentTimeMillis();
			queue.enter(1, RUN);
			Double score = redis.opsForZSet().score("queue:" + RUN, "000000000001:1");
			assertThat(score).isBetween((double) before + 50_000 - 5_000, (double) System.currentTimeMillis() + 50_000 + 5_000);
		}

		@Test
		void 진입하면_CI_집합에_만료_시각과_함께_기록된다() {
			queue.enter(1, RUN);
			Double expiry = redis.opsForZSet().score("queue:ci:dev-ci-1", "1");
			assertThat(expiry).isGreaterThan((double) System.currentTimeMillis());
		}
	}

	// =====================================================================
	@Nested
	@DisplayName("흐름 전체")
	class FullFlow {

		@Test
		void 진입에서_입장_검증_이탈까지_한_사람의_전체_흐름() {
			QueueStatus entered = queue.enter(1, RUN);
			assertThat(entered.state()).isEqualTo(QueueStatus.State.WAITING);
			assertThat(entered.passToken()).isNull();

			assertThat(admitter.admit(RUN)).isEqualTo(1);
			QueueStatus admitted = queue.status(1, RUN);
			assertThat(admitted.state()).isEqualTo(QueueStatus.State.ADMITTED);
			assertThat(admitted.rank()).isNull();
			assertThat(admitted.ticketNo()).isEqualTo(entered.ticketNo());

			pass.verify(RUN, 1, admitted.passToken());
			queue.leave(1, RUN);
			assertCode(() -> pass.verify(RUN, 1, admitted.passToken()), QueueErrorCode.QUEUE_TOKEN_INVALID);
			assertThat(redis.keys("queue:*")).containsAnyOf("queue:seq:" + RUN, "queue:throughput:" + RUN).doesNotContain("queue:member:1");
		}

		@Test
		void 천_명이_들어와_스무_번에_나눠_모두_한_번씩만_입장한다() {
			int n = 1000;
			setPolicy("queue.admit_per_sec", "50");
			enterAll(1, n);
			int total = 0;
			for (int round = 0; round < 20; round++) {
				int got = admitter.admit(RUN);
				assertThat(got).isEqualTo(50);
				total += got;
			}
			assertThat(total).isEqualTo(n);
			assertThat(zcard(RUN)).isZero();
			Set<Long> admitted = sink.events.stream().filter(e -> e.type() == QueueLogEvent.Type.ADMITTED)
					.map(QueueLogEvent::memberId).collect(Collectors.toSet());
			assertThat(admitted).hasSize(n);
		}

		@Test
		void 입장_순서는_진입_순서와_같다() {
			setPolicy("queue.admit_per_sec", "7");
			enterAll(1, 35);
			for (int i = 0; i < 5; i++) {
				admitter.admit(RUN);
			}
			List<Long> order = sink.events.stream().filter(e -> e.type() == QueueLogEvent.Type.ADMITTED)
					.map(QueueLogEvent::memberId).collect(Collectors.toList());
			assertThat(order).isEqualTo(LongStream35());
		}

		private List<Long> LongStream35() {
			return java.util.stream.LongStream.rangeClosed(1, 35).boxed().collect(Collectors.toList());
		}

		@Test
		void 진입_입장_이탈이_섞여도_번호표는_항상_증가한다() {
			long last = 0;
			for (long m = 1; m <= 30; m++) {
				QueueStatus s = queue.enter(m, RUN);
				assertThat(s.ticketNo()).isGreaterThan(last);
				last = s.ticketNo();
				if (m % 5 == 0) {
					setPolicy("queue.admit_per_sec", "2");
					admitter.admit(RUN);
				}
				if (m % 7 == 0) {
					queue.leave(m, RUN);
				}
			}
		}

		@Test
		void 정책을_바꾸면_다음_입장_처리부터_반영된다() {
			enterAll(1, 30);
			setPolicy("queue.admit_per_sec", "5");
			assertThat(admitter.admit(RUN)).isEqualTo(5);
			setPolicy("queue.admit_per_sec", "20");
			assertThat(admitter.admit(RUN)).isEqualTo(20);
			assertThat(admitter.admit(RUN)).isEqualTo(5);
		}
	}

	// =====================================================================
	@Nested
	@DisplayName("동시성")
	class Concurrency {

		private <T> List<T> runParallel(int threads, int tasks, java.util.function.IntFunction<java.util.concurrent.Callable<T>> make)
				throws Exception {
			ExecutorService pool = Executors.newFixedThreadPool(threads);
			CountDownLatch start = new CountDownLatch(1);
			List<Future<T>> fs = new ArrayList<>();
			for (int i = 0; i < tasks; i++) {
				java.util.concurrent.Callable<T> c = make.apply(i);
				fs.add(pool.submit(() -> {
					start.await();
					return c.call();
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

		@Test
		void 동시에_2000명이_진입해도_번호표와_줄이_정확하다() throws Exception {
			int n = 2000;
			List<QueueStatus> res = runParallel(64, n, i -> () -> queue.enter(i + 1L, RUN));
			Set<Long> tickets = res.stream().map(QueueStatus::ticketNo).collect(Collectors.toSet());
			assertThat(tickets).hasSize(n);
			assertThat(Collections.min(tickets)).isEqualTo(1);
			assertThat(Collections.max(tickets)).isEqualTo(n);
			assertThat(zcard(RUN)).isEqualTo(n);
		}

		@Test
		void 동시에_진입해도_순번은_1부터_빈틈없이_이어진다() throws Exception {
			int n = 500;
			runParallel(32, n, i -> () -> queue.enter(i + 1L, RUN));
			List<Long> ranks = new ArrayList<>();
			for (long m = 1; m <= n; m++) {
				ranks.add(queue.status(m, RUN).rank());
			}
			Collections.sort(ranks);
			for (int i = 0; i < n; i++) {
				assertThat(ranks.get(i)).isEqualTo(i + 1L);
			}
		}

		@Test
		void 여러_스레드가_동시에_입장_처리해도_모두_정확히_한_번씩만_입장한다() throws Exception {
			int n = 600;
			setPolicy("queue.admit_per_sec", "50");
			enterAll(1, n);
			List<Integer> counts = runParallel(8, 40, i -> () -> admitter.admit(RUN));
			assertThat(counts.stream().mapToInt(Integer::intValue).sum()).isEqualTo(n);
			assertThat(zcard(RUN)).isZero();
			Set<String> tokens = new HashSet<>();
			for (long m = 1; m <= n; m++) {
				tokens.add(tokenOf(m));
			}
			assertThat(tokens).hasSize(n);
		}

		@Test
		void 이탈과_입장_처리가_동시에_일어나도_유령_입장이_없다() throws Exception {
			int n = 300;
			setPolicy("queue.admit_per_sec", "30");
			enterAll(1, n);
			runParallel(16, 40, i -> () -> {
				if (i % 2 == 0) {
					return admitter.admit(RUN);
				}
				for (long m = 1; m <= 15; m++) {
					queue.leave((i / 2) * 15L + m, RUN);
				}
				return 0;
			});
			// 이탈한 회원(1~300 중 일부)은 줄에도 active에도 토큰에도 남아 있으면 안 된다
			for (long m = 1; m <= n; m++) {
				boolean hasMember = redis.hasKey("queue:member:" + m);
				boolean hasToken = redis.hasKey("queue:pass:" + RUN + ":" + m);
				if (!hasMember) {
					assertThat(hasToken).as("member %d 이탈했는데 토큰 남음", m).isFalse();
				}
			}
		}

		@Test
		void 같은_CI_스무_계정이_동시에_와도_한_계정만_선다() throws Exception {
			for (long id = 1; id <= 20; id++) {
				register(id, "ACTIVE", "same-ci");
			}
			List<Boolean> ok = runParallel(20, 20, i -> () -> {
				try {
					queue.enter(i + 1L, RUN);
					return true;
				} catch (BusinessException e) {
					return false;
				}
			});
			assertThat(ok.stream().filter(b -> b).count()).isEqualTo(1);
			assertThat(zcard(RUN)).isEqualTo(1);
		}

		@Test
		void 같은_회원이_스무_번_동시_연타해도_번호표는_하나다() throws Exception {
			List<QueueStatus> res = runParallel(20, 20, i -> () -> queue.enter(1, RUN));
			assertThat(res.stream().map(QueueStatus::ticketNo).collect(Collectors.toSet())).hasSize(1);
			assertThat(zcard(RUN)).isEqualTo(1);
			assertThat(sink.events.stream().filter(e -> e.type() == QueueLogEvent.Type.ENTERED)).hasSize(1);
		}

		@Test
		void 같은_회원이_두_운행에_동시에_진입해도_한_운행에만_선다() throws Exception {
			runs.open(2002);
			List<Boolean> ok = runParallel(2, 2, i -> () -> {
				try {
					queue.enter(1, i == 0 ? RUN : 2002);
					return true;
				} catch (BusinessException e) {
					return false;
				}
			});
			assertThat(ok.stream().filter(b -> b).count()).isEqualTo(1);
			assertThat(zcard(RUN) + zcard(2002)).isEqualTo(1);
		}

		@Test
		void 서로_다른_운행은_동시에_진입해도_번호표가_섞이지_않는다() throws Exception {
			runs.open(2002);
			runParallel(32, 400, i -> () -> queue.enter(i + 1L, i % 2 == 0 ? RUN : 2002));
			assertThat(zcard(RUN)).isEqualTo(200);
			assertThat(zcard(2002)).isEqualTo(200);
			assertThat(redis.opsForValue().get("queue:seq:" + RUN)).isEqualTo("200");
			assertThat(redis.opsForValue().get("queue:seq:2002")).isEqualTo("200");
		}

		@Test
		void 조회는_진입_폭주_중에도_예외_없이_일관된_값을_준다() throws Exception {
			enterAll(1, 100);
			List<Long> ranks = runParallel(16, 400, i -> () -> {
				if (i % 4 == 0) {
					queue.enter(1000L + i, RUN);
				}
				return queue.status(1 + (i % 100), RUN).rank();
			});
			assertThat(ranks).allMatch(r -> r != null && r >= 1);
		}

		@Test
		void 동시_진입과_이탈이_끝난_뒤_줄과_회원_기록이_일치한다() throws Exception {
			int n = 400;
			runParallel(32, n, i -> () -> {
				long id = i + 1L;
				queue.enter(id, RUN);
				if (id % 3 == 0) {
					queue.leave(id, RUN);
				}
				return null;
			});
			long expected = IntStream.rangeClosed(1, n).filter(i -> i % 3 != 0).count();
			assertThat(zcard(RUN)).isEqualTo(expected);
			Set<String> memberKeys = redis.keys("queue:member:*");
			assertThat(memberKeys).hasSize((int) expected);
		}
	}

	// =====================================================================
	@Nested
	@DisplayName("대기 기록 만료")
	class WaitRecordExpiry {

		@Test
		void 대기_기록이_만료되면_줄에서도_빠져야_하고_유령이_입장하면_안_된다() throws Exception {
			setPolicy("queue.entry_ttl_sec", "1");
			queue.enter(1, RUN);
			Thread.sleep(1_300); // queue:member:1 만료
			assertCode(() -> queue.status(1, RUN), QueueErrorCode.QUEUE_NOT_FOUND);

			// 이미 "대기 중이 아님"으로 보이는 회원이 줄에 남아 있다가 입장하면, 아무도 모르는 토큰이 생긴다
			int admitted = admitter.admit(RUN);
			assertThat(admitted).as("만료된 대기자가 입장 처리됨(유령 입장)").isZero();
		}

		@Test
		void 만료_뒤_다시_줄을_서면_옛_항목은_건너뛰고_새_항목만_입장한다() throws Exception {
			setPolicy("queue.entry_ttl_sec", "1");
			queue.enter(1, RUN);
			Thread.sleep(1_300);
			setPolicy("queue.entry_ttl_sec", "3600");
			QueueStatus again = queue.enter(1, RUN);
			assertThat(again.ticketNo()).isEqualTo(2);

			assertThat(admitter.admit(RUN)).isEqualTo(1);
			assertThat(queue.status(1, RUN).state()).isEqualTo(QueueStatus.State.ADMITTED);
			assertThat(zcard(RUN)).isZero();
		}

		@Test
		void 유령이_차지한_자리는_뒤의_실제_대기자로_채운다() throws Exception {
			setPolicy("queue.entry_ttl_sec", "1");
			enterAll(1, 3); // 곧 만료될 앞 세 명 (유령)
			Thread.sleep(1_300);
			setPolicy("queue.entry_ttl_sec", "3600");
			enterAll(4, 8); // 실제 대기자 다섯 명
			setPolicy("queue.admit_per_sec", "3");

			assertThat(admitter.admit(RUN)).isEqualTo(3);
			for (long m = 4; m <= 6; m++) {
				assertThat(queue.status(m, RUN).state()).isEqualTo(QueueStatus.State.ADMITTED);
			}
			assertThat(queue.status(7, RUN).rank()).isEqualTo(1);
			assertThat(sink.events.stream().filter(e -> e.type() == QueueLogEvent.Type.ADMITTED)
					.map(QueueLogEvent::memberId)).containsExactly(4L, 5L, 6L);
		}

		@Test
		void 유령만_남아_있으면_0명이고_줄은_정리된다() throws Exception {
			setPolicy("queue.entry_ttl_sec", "1");
			enterAll(1, 5);
			Thread.sleep(1_300);
			assertThat(admitter.admit(RUN)).isZero();
			assertThat(zcard(RUN)).isZero();
			assertThat(redis.keys("queue:pass:*")).isEmpty();
		}
	}
}
