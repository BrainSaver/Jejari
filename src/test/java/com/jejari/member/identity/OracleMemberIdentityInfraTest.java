package com.jejari.member.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.jejari.queue.domain.QueueService;
import com.jejari.queue.domain.RiskPenaltyService;
import com.jejari.queue.port.MemberIdentityPort;
import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;
import com.jejari.queue.redis.QueueKeys;

/**
 * 대기열용 회원 정보 조회를 실제 Oracle로 확인하고, A의 RiskPenaltyService와 맞물리는지 본다.
 * {@code docker compose up -d} 뒤 {@code ./gradlew infraTest}. 이 테스트가 만든 회원 행만 지운다.
 */
@Tag("infra")
@SpringBootTest(properties = {
		"spring.data.redis.database=15",
		"jejari.queue.scheduler-enabled=false",
		"jejari.risk.cache-resync-enabled=false"})
class OracleMemberIdentityInfraTest {

	static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	MemberIdentityPort port;
	@Autowired
	ApplicationContext ctx;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	RiskPenaltyService risk;
	@Autowired
	QueueService queue;

	final List<Long> members = new ArrayList<>();
	long methodId = 900_000_000L + RANDOM.nextInt(1_000_000) * 100L;

	@BeforeEach
	void flushRedis() {
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
	}

	@AfterEach
	void cleanUp() {
		for (long id : members) {
			jdbc.update("DELETE FROM member_device WHERE member_id = ?", id);
			jdbc.update("DELETE FROM payment_method WHERE member_id = ?", id);
			jdbc.update("DELETE FROM member WHERE member_id = ?", id);
		}
	}

	static String hash() {
		byte[] b = new byte[32];
		RANDOM.nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	long member(String status, String ciHash) {
		long id = jdbc.queryForObject("SELECT member_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, '테스트', ?)",
				id, "id" + id + "@infra-test.jejari.dev", ciHash, status);
		members.add(id);
		return id;
	}

	void payment(long memberId, String methodHash) {
		jdbc.update("INSERT INTO payment_method (method_id, member_id, method_hash, method_type) VALUES (?, ?, ?, 'CARD')",
				++methodId, memberId, methodHash);
	}

	void device(long memberId, String deviceHash) {
		jdbc.update("INSERT INTO member_device (device_hash, member_id) VALUES (?, ?)", deviceHash, memberId);
	}

	@Test
	void 대기열이_이_구현을_쓴다() {
		assertThat(ctx.getBeansOfType(MemberIdentityPort.class)).hasSize(1);
		assertThat(port).isInstanceOf(OracleMemberIdentityPort.class);
		assertThat(ReflectionTestUtils.getField(queue, "members")).isSameAs(port);
	}

	@Test
	void 상태_CI_결제수단_기기를_한_번에_읽는다() {
		String ci = hash(), p1 = hash(), p2 = hash(), d1 = hash();
		long id = member("ACTIVE", ci);
		payment(id, p1);
		payment(id, p2);
		payment(id, null); // 해시 전 등록 중인 결제 수단은 뺀다
		device(id, d1);

		MemberIdentity m = port.find(id).orElseThrow();
		assertThat(m.memberId()).isEqualTo(id);
		assertThat(m.status()).isEqualTo("ACTIVE");
		assertThat(m.ciHash()).isEqualTo(ci);
		assertThat(m.paymentHashes()).containsExactlyInAnyOrder(p1, p2);
		assertThat(m.deviceHashes()).containsExactly(d1);
		assertThat(port.find(id)).as("같은 회원이면 같은 결과(정렬 고정)").contains(m);
	}

	@Test
	void 결제수단_기기가_없어도_된다() {
		long id = member("RESTRICTED", hash());
		MemberIdentity m = port.find(id).orElseThrow();
		assertThat(m.status()).isEqualTo("RESTRICTED");
		assertThat(m.paymentHashes()).isEmpty();
		assertThat(m.deviceHashes()).isEmpty();
	}

	@Test
	void 없는_회원과_탈퇴_회원은_빈_값() {
		assertThat(port.find(-1L)).isEmpty();
		long id = member("ACTIVE", hash());
		payment(id, hash());
		jdbc.update("UPDATE member SET withdrawn_at = SYSTIMESTAMP WHERE member_id = ?", id);
		assertThat(port.find(id)).isEmpty();
	}

	@Test
	void 정지_상태가_바로_보인다_캐시_없음() {
		long id = member("ACTIVE", hash());
		assertThat(port.find(id).orElseThrow().status()).isEqualTo("ACTIVE");
		jdbc.update("UPDATE member SET status = 'BANNED' WHERE member_id = ?", id);
		assertThat(port.find(id).orElseThrow().status()).isEqualTo("BANNED");
	}

	@Test
	void 형식이_깨진_해시면_막는다() {
		long id = member("ACTIVE", hash());
		device(id, "NOT-A-HASH");
		assertThatThrownBy(() -> port.find(id)).isInstanceOf(IllegalStateException.class)
				.hasMessageNotContaining("NOT-A-HASH");
	}

	@Test
	void 기기가_상한을_넘으면_자르지_않고_막는다() {
		long id = member("ACTIVE", hash());
		for (int i = 0; i < OracleMemberIdentityPort.MAX_LINKED; i++) {
			device(id, hash());
		}
		assertThat(port.find(id).orElseThrow().deviceHashes()).hasSize(OracleMemberIdentityPort.MAX_LINKED);
		device(id, hash());
		assertThatThrownBy(() -> port.find(id)).isInstanceOf(IllegalStateException.class);
	}

	// ---- A의 위험 점수 계산과 맞물림 ----

	@Test
	void 결제수단_해시의_위험_점수가_대기열_점수가_된다() {
		String ci = hash(), card = hash();
		long id = member("ACTIVE", ci);
		payment(id, card);
		redis.opsForValue().set(QueueKeys.RISK_SYNCED_AT, "test");
		redis.opsForValue().set(QueueKeys.risk("CI", ci), "20.00");
		redis.opsForValue().set(QueueKeys.risk("PAYMENT", card), "70.00");

		assertThat(risk.score(port.find(id).orElseThrow())).isEqualTo(70.0); // MAX
	}

	@Test
	void RESTRICTED는_캐시와_관계없이_100점() {
		long id = member("RESTRICTED", hash());
		redis.opsForValue().set(QueueKeys.RISK_SYNCED_AT, "test");
		assertThat(risk.score(port.find(id).orElseThrow())).isEqualTo(100.0);
	}

	@Test
	void 조회_시간_측정() {
		long id = member("ACTIVE", hash());
		for (int i = 0; i < 3; i++) {
			payment(id, hash());
			device(id, hash());
		}
		for (int i = 0; i < 50; i++) {
			port.find(id); // 준비 (커넥션·실행 계획)
		}
		int n = 500;
		long[] ns = new long[n];
		for (int i = 0; i < n; i++) {
			long t = System.nanoTime();
			port.find(id);
			ns[i] = System.nanoTime() - t;
		}
		Arrays.sort(ns);
		double avg = Arrays.stream(ns).average().orElse(0) / 1e6;
		System.out.printf("[OracleMemberIdentityPort] %d회 평균 %.2fms, p50 %.2fms, p99 %.2fms%n", n, avg,
				ns[n / 2] / 1e6, ns[(int) (n * 0.99)] / 1e6);
		assertThat(avg).as("인덱스 조회 1회 왕복이면 수 ms 이내").isLessThan(20);
	}
}
