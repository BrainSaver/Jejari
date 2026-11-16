package com.jejari.risk.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.queue.domain.RiskPenaltyService;
import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;
import com.jejari.queue.port.RiskScoreFallbackPort;
import com.jejari.queue.redis.QueueKeys;
import com.jejari.risk.RiskResult;
import com.jejari.risk.RiskSignals;
import com.jejari.risk.SubjectType;
import com.jejari.risk.store.OracleRiskScoreFallback;
import com.jejari.risk.store.RiskScoreStore;
import com.jejari.support.FullStackInfraTest;

/**
 * RISK-03 캐시 쓰기·재동기화를 실제 Oracle·Redis로 확인한다. {@code docker compose up -d} 뒤 {@code ./gradlew infraTest}.
 * Redis는 15번 DB만 쓰고 매번 비운다. Oracle은 테스트가 만든 해시 행만 지운다.
 */
@FullStackInfraTest
class RiskCacheInfraTest {

	@Autowired
	RiskScoreStore store;
	@Autowired
	RiskScoreCache cache;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	TransactionTemplate tx;

	RiskCacheResync resync;
	String h1, h2, stray;

	@BeforeEach
	void setUp() {
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		h1 = hash();
		h2 = hash();
		stray = hash();
		// 재동기화는 앱 시작 때 자동으로 돌지 않게 꺼 두고, 테스트에서 직접 부른다
		resync = new RiskCacheResync(jdbc, redis, cache, null, Duration.ofMinutes(10), Duration.ofSeconds(30));
	}

	// ---- 대기열(A)의 실제 읽기 코드와 맞물리는지 ----

	@Autowired
	RiskPenaltyService queue;
	@Autowired
	RiskScoreFallbackPort fallback;

	MemberIdentity member() {
		return new MemberIdentity(1L, "ACTIVE", h1, List.of(h2), List.of());
	}

	@Test
	void 재동기화_뒤_대기열은_Redis_점수를_그대로_읽는다() {
		store.save(result(SubjectType.CI, h1, "60"));
		store.save(result(SubjectType.PAYMENT, h2, "75.5"));
		resync.resync();

		// 표시 키 이름이 A와 다르면 대기열은 캐시를 안 믿고 Oracle 조회로 빠진다 → 여기서 잡힌다
		assertThat(redis.hasKey(QueueKeys.RISK_SYNCED_AT)).isTrue();
		assertThat(queue.score(member())).isEqualTo(75.5);
	}

	@Test
	void 표시_키가_없으면_대기열은_Oracle에서_읽는다_100점_아님() {
		store.save(result(SubjectType.CI, h1, "60"));
		store.save(result(SubjectType.PAYMENT, h2, "75.5"));
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb(); // Redis 초기화 상황
			return null;
		});

		assertThat(fallback).isInstanceOf(OracleRiskScoreFallback.class);
		assertThat(queue.score(member())).isEqualTo(75.5);
	}

	@Test
	void 점수가_없는_회원은_Oracle_조회로도_0점() {
		assertThat(fallback.maxScore(member())).isZero();
	}

	@Test
	void 잘못된_해시면_Oracle_조회가_예외_대기열은_100점() {
		MemberIdentity bad = new MemberIdentity(1L, "ACTIVE", "01012345678", List.of(), List.of());
		assertThat(queue.score(bad)).isEqualTo(100.0);
	}

	@Test
	void 표시_키가_사라지면_감시가_바로_재동기화() {
		store.save(result(SubjectType.CI, h1, "60"));
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		resync.resyncIfMarkerMissing();
		assertThat(redis.hasKey(QueueKeys.RISK_SYNCED_AT)).isTrue();
		assertThat(redis.opsForValue().get("risk:CI:" + h1)).isEqualTo("60.00");
	}

	@Test
	void 재동기화는_표시_키를_정리_대상으로_지우지_않는다() {
		resync.resync();
		resync.resync();
		assertThat(redis.hasKey(QueueKeys.RISK_SYNCED_AT)).isTrue();
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM risk_score WHERE subject_hash IN (?, ?, ?)", h1, h2, stray);
	}

	@Test
	void 저장하면_Oracle과_Redis에_같은_점수() {
		store.save(result(SubjectType.CI, h1, "80.5"));

		assertThat(jdbc.queryForObject("SELECT final_score FROM risk_score WHERE subject_type='CI' AND subject_hash=?",
				BigDecimal.class, h1)).isEqualByComparingTo("80.5");
		assertThat(redis.opsForValue().get("risk:CI:" + h1)).isEqualTo("80.50");
		assertThat(redis.getExpire("risk:CI:" + h1)).as("TTL 없음").isEqualTo(-1L);
	}

	@Test
	void 영점이_되면_키를_지운다() {
		store.save(result(SubjectType.DEVICE, h1, "40"));
		store.save(result(SubjectType.DEVICE, h1, "0"));
		assertThat(redis.hasKey("risk:DEVICE:" + h1)).isFalse();
	}

	@Test
	void 다시_계산해도_관리자_판정은_유지() {
		store.save(result(SubjectType.CI, h1, "90"));
		jdbc.update("UPDATE risk_score SET review_status='CONFIRMED' WHERE subject_hash=?", h1);
		store.save(result(SubjectType.CI, h1, "85"));
		assertThat(jdbc.queryForObject("SELECT review_status FROM risk_score WHERE subject_hash=?", String.class, h1))
				.isEqualTo("CONFIRMED");
	}

	@Test
	void 바깥_트랜잭션이_롤백되면_캐시도_쓰지_않는다() {
		tx.executeWithoutResult(st -> {
			store.save(result(SubjectType.CI, h1, "70"));
			st.setRollbackOnly();
		});
		assertThat(redis.hasKey("risk:CI:" + h1)).isFalse();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM risk_score WHERE subject_hash=?", Integer.class, h1))
				.isZero();
	}

	@Test
	void 바깥_트랜잭션이_커밋된_뒤에_캐시를_쓴다() {
		tx.executeWithoutResult(st -> {
			store.save(result(SubjectType.CI, h1, "70"));
			assertThat(redis.hasKey("risk:CI:" + h1)).as("커밋 전").isFalse();
		});
		assertThat(redis.opsForValue().get("risk:CI:" + h1)).isEqualTo("70.00");
	}

	@Test
	void 관리자가_DB를_바꾼_뒤_refreshCache로_바로_반영() {
		store.save(result(SubjectType.PAYMENT, h1, "90"));
		jdbc.update("UPDATE risk_score SET final_score = 0, review_status='CLEARED' WHERE subject_hash=?", h1);
		store.refreshCache(SubjectType.PAYMENT, h1);
		assertThat(redis.hasKey("risk:PAYMENT:" + h1)).isFalse();
	}

	@Test
	void 재동기화는_빠진_키를_채우고_남은_키를_지우고_완료_표시() {
		store.save(result(SubjectType.CI, h1, "60"));
		store.save(result(SubjectType.DEVICE, h2, "30"));
		redis.delete("risk:CI:" + h1);                         // Redis 재시작·삭제로 빠진 키
		redis.opsForValue().set("risk:DEVICE:" + h2, "1.00");    // 오염된 값
		redis.opsForValue().set("risk:PAYMENT:" + stray, "99"); // Oracle에 없는 키 (삭제 실패로 남음)

		resync.resync();

		assertThat(redis.opsForValue().get("risk:CI:" + h1)).isEqualTo("60.00");
		assertThat(redis.opsForValue().get("risk:DEVICE:" + h2)).isEqualTo("30.00");
		assertThat(redis.hasKey("risk:PAYMENT:" + stray)).isFalse();
		assertThat(redis.opsForValue().get(RiskScoreCache.SYNCED_AT_KEY)).isNotBlank();
	}

	// ---- 도우미 ----

	static String hash() {
		byte[] b = new byte[32];
		byte[] u = UUID.randomUUID().toString().getBytes();
		System.arraycopy(u, 0, b, 0, 32);
		return HexFormat.of().formatHex(b);
	}

	static RiskResult result(SubjectType type, String hash, String score) {
		Instant now = Instant.now();
		RiskSignals s = new RiskSignals(type, hash, now.minusSeconds(600), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, null);
		BigDecimal f = new BigDecimal(score);
		return new RiskResult(s, f, f, List.of(), false, null, now);
	}
}
