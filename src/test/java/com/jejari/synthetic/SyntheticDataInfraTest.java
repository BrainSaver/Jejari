package com.jejari.synthetic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jejari.risk.batch.RiskDirtyQueue;
import com.jejari.risk.batch.RiskScoreBatch;
import com.jejari.support.FullStackInfraTest;
import com.mongodb.client.model.Filters;

/**
 * DATA-02 생성기: 생성 → 실제 1분 배치 → 종류별 탐지(작은 규모 평가) → 삭제.
 * eval_risk.sh와 같은 기준: 회원에 연결된 CI·결제 수단·기기 중 하나라도 의심 케이스가 열렸으면 탐지.
 */
@FullStackInfraTest
class SyntheticDataInfraTest {

	static final String TAG = "zt" + Long.toString(System.nanoTime() % 1_000_000, 36);

	@Autowired
	SyntheticDataService service;
	@Autowired
	RiskScoreBatch batch;
	@Autowired
	RiskDirtyQueue dirty;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MongoTemplate mongo;
	@Autowired
	StringRedisTemplate redis;

	@AfterEach
	void tearDown() {
		service.cleanup(TAG);
	}

	@Test
	void 생성한_봇은_배치가_잡고_정상_회원은_가족과_기기를_같이_써도_안_잡힌다_삭제하면_흔적이_없다() {
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		SyntheticDataService.Result r = service.generate(new SyntheticDataService.Spec(TAG, 100, 7));

		assertThat(r.counts()).containsKeys("normal", "fast", "retry", "multi", "cancel");
		assertThat(r.counts().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
		assertThat(count("SELECT COUNT(*) FROM member WHERE email LIKE ?", "lt" + TAG + ".%")).isEqualTo(100);
		// 이메일 라벨 규칙이 eval_risk.sh와 같다
		assertThat(jdbc.queryForList("SELECT email FROM member WHERE email LIKE ?", String.class, "lt" + TAG + ".multi.%"))
				.allMatch(e -> e.matches("^lt" + TAG + "\\.multi\\.g\\d+\\.\\d+@loadtest\\.example\\.com$"));
		// multi 묶음은 한 기기를 공유
		assertThat(count("""
				SELECT COUNT(*) FROM (SELECT d.device_hash FROM member_device d JOIN member m ON m.member_id = d.member_id
				 WHERE m.email LIKE ? GROUP BY d.device_hash HAVING COUNT(*) = 3)""", "lt" + TAG + ".multi.%")).isPositive();
		assertThat(count("""
				SELECT COUNT(*) FROM cancellation c JOIN reservation r ON r.reservation_id = c.reservation_id
				  JOIN member m ON m.member_id = r.member_id WHERE m.email LIKE ? AND c.minutes_after_purchase <= 3""",
				"lt" + TAG + ".cancel.%")).isEqualTo(3L * r.counts().get("cancel"));
		assertThat(dirty.pending()).isPositive();

		// 실제 배치로 점수·케이스
		for (int i = 0; i < 20 && dirty.pending() > 0; i++) {
			batch.run();
		}
		assertThat(dirty.pending()).isZero();

		Map<String, long[]> byKind = new java.util.HashMap<>(); // kind → {인원, 탐지}
		jdbc.query("""
				SELECT REGEXP_SUBSTR(m.email, '^lt[a-z0-9]+\\.([a-z]+)\\.', 1, 1, NULL, 1) kind,
				       CASE WHEN EXISTS (SELECT 1 FROM suspicious_case c WHERE
				              (c.subject_type = 'CI' AND c.subject_hash = m.ci_hash)
				           OR (c.subject_type = 'PAYMENT' AND c.subject_hash IN (SELECT method_hash FROM payment_method p WHERE p.member_id = m.member_id))
				           OR (c.subject_type = 'DEVICE' AND c.subject_hash IN (SELECT device_hash FROM member_device d WHERE d.member_id = m.member_id)))
				            THEN 1 ELSE 0 END det
				  FROM member m WHERE m.email LIKE ?""",
				rs -> {
					long[] v = byKind.computeIfAbsent(rs.getString(1), k -> new long[2]);
					v[0]++;
					v[1] += rs.getInt(2);
				}, "lt" + TAG + ".%");
		for (String bot : List.of("fast", "retry", "multi", "cancel")) {
			assertThat(byKind.get(bot)[1]).as("탐지된 %s", bot).isEqualTo(byKind.get(bot)[0]);
		}
		assertThat(byKind.get("normal")[1]).as("오탐 (정상)").isZero();

		// 삭제하면 흔적이 없다 (이 테스트가 만든 회원만 센다. 로컬 DB에 syn1 같은 다른 가상 데이터가 남아 있어도 통과)
		long runId = r.runId();
		List<Long> ids = jdbc.queryForList("SELECT member_id FROM member WHERE email LIKE ?", Long.class, "lt" + TAG + ".%");
		assertThat(mongo.getCollection("behavior_log").countDocuments(Filters.in("member_id", ids))).isPositive();
		assertThat(service.cleanup(TAG)).isEqualTo(100);
		assertThat(count("SELECT COUNT(*) FROM member WHERE email LIKE ?", "lt" + TAG + ".%")).isZero();
		assertThat(count("SELECT COUNT(*) FROM train_run WHERE run_id = ?", runId)).isZero();
		assertThat(mongo.getCollection("behavior_log").countDocuments(Filters.in("member_id", ids))).isZero();
	}

	@Test
	void 같은_태그로_두_번_만들_수_없고_잘못된_입력은_거절() {
		assertThatThrownBy(() -> new SyntheticDataService.Spec("BAD TAG", 100, 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SyntheticDataService.Spec(TAG, 5, 1)).isInstanceOf(IllegalArgumentException.class);
		service.generate(new SyntheticDataService.Spec(TAG, 10, 1));
		assertThatThrownBy(() -> service.generate(new SyntheticDataService.Spec(TAG, 10, 1)))
				.isInstanceOf(IllegalStateException.class);
	}

	long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}
}
