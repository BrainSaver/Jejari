package com.jejari.behavior.sink;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jejari.behavior.BehaviorEvent;
import com.jejari.support.FullStackInfraTest;
import com.mongodb.client.model.Filters;

import tools.jackson.databind.json.JsonMapper;

/** 행동 로그 적재가 회원 ↔ 기기 연결(member_device)을 남기는지: 처음·마지막 본 시각, 없는 회원 무시, 회원당 상한 */
@FullStackInfraTest
class MemberDeviceInfraTest {

	static final AtomicLong NEXT = new AtomicLong(890_001);

	@Autowired
	BehaviorSink sink;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MongoTemplate mongo;
	@Autowired
	JsonMapper json;

	final List<Long> members = new ArrayList<>();

	@AfterEach
	void tearDown() {
		for (long m : members) {
			jdbc.update("DELETE FROM member_device WHERE member_id = ?", m);
			jdbc.update("DELETE FROM member WHERE member_id = ?", m);
		}
		mongo.getCollection("behavior_log").deleteMany(Filters.in("member_id", new ArrayList<>(members)));
		mongo.getCollection("behavior_log").deleteMany(Filters.eq("member_id", 999_999_999L));
	}

	@Test
	void 회원과_기기를_연결하고_마지막으로_본_시각을_늘린다() {
		long m = member();
		String device = hash();
		Instant t1 = Instant.parse("2026-11-17T01:00:00Z");
		sink.consume(List.of(record(m, device, t1), record(m, device, t1.plusSeconds(30))));
		sink.consume(List.of(record(m, device, t1.plusSeconds(600))));

		Map<String, Object> row = jdbc.queryForMap(
				"SELECT first_seen_at, last_seen_at FROM member_device WHERE member_id = ? AND device_hash = ?", m, device);
		assertThat(((Timestamp) row.get("FIRST_SEEN_AT")).toInstant()).isEqualTo(t1);
		assertThat(((Timestamp) row.get("LAST_SEEN_AT")).toInstant()).isEqualTo(t1.plusSeconds(600));

		// 늦게 도착한 옛 이벤트가 마지막 시각을 되돌리지 않는다
		sink.consume(List.of(record(m, device, t1.plusSeconds(5))));
		assertThat(jdbc.queryForObject("SELECT last_seen_at FROM member_device WHERE member_id = ?", Timestamp.class, m)
				.toInstant()).isEqualTo(t1.plusSeconds(600));
	}

	@Test
	void 없는_회원_ID는_무시하고_적재는_멈추지_않는다() {
		long m = member();
		String device = hash();
		sink.consume(List.of(record(999_999_999L, device, Instant.now()), record(m, device, Instant.now())));
		assertThat(count("SELECT COUNT(*) FROM member_device WHERE device_hash = ?", device)).isOne(); // 진짜 회원 것만
	}

	@Test
	void 회원당_기기는_상한까지만_새로_넣는다() {
		long m = member();
		List<ConsumerRecord<String, String>> batch = new ArrayList<>();
		for (int i = 0; i < BehaviorSink.MAX_DEVICES_PER_MEMBER; i++) {
			batch.add(record(m, hash(), Instant.now()));
		}
		sink.consume(batch);
		assertThat(count("SELECT COUNT(*) FROM member_device WHERE member_id = ?", m))
				.isEqualTo(BehaviorSink.MAX_DEVICES_PER_MEMBER);

		String known = jdbc.queryForList("SELECT device_hash FROM member_device WHERE member_id = ?", String.class, m).get(0);
		sink.consume(List.of(record(m, hash(), Instant.now()), record(m, known, Instant.now().plusSeconds(60))));
		assertThat(count("SELECT COUNT(*) FROM member_device WHERE member_id = ?", m))
				.isEqualTo(BehaviorSink.MAX_DEVICES_PER_MEMBER); // 새 기기는 안 들어감
		assertThat(jdbc.queryForObject("SELECT last_seen_at FROM member_device WHERE member_id = ? AND device_hash = ?",
				Timestamp.class, m, known).toInstant()).isAfter(Instant.now().plusSeconds(30)); // 아는 기기는 갱신
	}

	// ------------------------------------------------------------ 도우미

	ConsumerRecord<String, String> record(long memberId, String device, Instant ts) {
		BehaviorEvent e = new BehaviorEvent(UUID.randomUUID().toString(), "VIEW_SEAT",
				OffsetDateTime.ofInstant(ts, ZoneOffset.ofHours(9)).toString(), memberId, null, device, null, 9001L, "WEB",
				null, 200, Map.of());
		return new ConsumerRecord<>(BehaviorEvent.TOPIC, 0, 0, e.key(), json.writeValueAsString(e));
	}

	long member() {
		long id = NEXT.getAndIncrement();
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, '테스트', 'ACTIVE')",
				id, "dev" + id + "@infra-test.jejari.dev", hash());
		members.add(id);
		return id;
	}

	static String hash() {
		byte[] b = new byte[32];
		new SecureRandom().nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}
}
