package com.jejari.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jejari.auth.ActorType;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.support.FullStackInfraTest;

import tools.jackson.databind.json.JsonMapper;

/**
 * 행동 로그 전체 경로: 요청 → mark → Kafka(jejari.behavior) → 적재 컨슈머 → MongoDB behavior_log.
 * 실제 Kafka·MongoDB·Oracle을 쓴다. 이 테스트가 만든 회원·문서만 지운다.
 */
@FullStackInfraTest
class BehaviorLogInfraTest {

	static final SecureRandom RANDOM = new SecureRandom();
	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	MongoTemplate mongo;
	@Autowired
	AccessTokenIssuer tokens;
	@Autowired
	Pseudonymizer pseudonymizer;
	@Value("${spring.kafka.bootstrap-servers}")
	String bootstrap;

	final List<Long> members = new ArrayList<>();
	final List<String> eventIds = new ArrayList<>();

	@AfterEach
	void cleanUp() {
		for (long id : members) {
			mongo.remove(Query.query(Criteria.where("member_id").is(id)), "behavior_log");
			jdbc.update("DELETE FROM member_device WHERE member_id = ?", id); // 적재가 회원↔기기 연결을 남김
			jdbc.update("DELETE FROM member WHERE member_id = ?", id);
		}
		if (!eventIds.isEmpty()) {
			mongo.remove(Query.query(Criteria.where("event_id").in(eventIds)), "behavior_log");
		}
	}

	static String hash() {
		byte[] b = new byte[32];
		RANDOM.nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	long member(String ciHash) {
		long id = jdbc.queryForObject("SELECT member_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, '테스트', 'ACTIVE')",
				id, "bh" + id + "@infra-test.jejari.dev", ciHash);
		members.add(id);
		return id;
	}

	List<Document> await(Query q, int count) throws InterruptedException {
		List<Document> docs = List.of();
		for (int i = 0; i < 300 && docs.size() < count; i++) { // 최대 30초 (컨슈머 그룹 합류 포함)
			docs = mongo.find(q, Document.class, "behavior_log");
			if (docs.size() < count) {
				Thread.sleep(100);
			}
		}
		return docs;
	}

	void produce(String key, String value) throws Exception {
		Map<String, Object> p = new HashMap<>();
		p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
		p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
			producer.send(new ProducerRecord<>(BehaviorEvent.TOPIC, key, value)).get(10, TimeUnit.SECONDS);
		}
	}

	BehaviorEvent event(String eventId, long memberId) {
		return new BehaviorEvent(eventId, "VIEW_SEAT", "2026-11-20T10:00:00+09:00", memberId, null, null, hash(), 9001L,
				"WEB", null, 200, Map.of());
	}

	@Test
	void 취소표_신청은_거절돼도_행동_로그에_남는다() throws Exception {
		// 취소표는 대기열 입장 토큰 없이 들어오는 유일한 경로다. 매진 직후 거절당하며 재시도하는
		// 패턴이 봇 신호라, 실패한 신청도 남아야 한다 (ERD v22)
		long id = member(hash());
		String bearer = "Bearer " + tokens.issue(ActorType.MEMBER, id, UUID.randomUUID().toString()).value();

		// 9001은 좌석이 남아 있으므로 WAITLIST_NOT_SOLD_OUT으로 거절된다
		mvc.perform(post("/api/v1/waitlists").header("Authorization", bearer)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("""
						{"runId":9001,"fromStationId":"3900023","toStationId":"3900114",
						 "seatGrade":"GENERAL","seatCount":1}"""))
				.andExpect(status().isConflict());

		List<Document> docs = await(Query.query(Criteria.where("member_id").is(id)), 1);
		assertThat(docs).hasSize(1);
		assertThat(docs.get(0).getString("event_type")).isEqualTo("WAITLIST_APPLY");
		assertThat(docs.get(0).getInteger("response_status")).as("거절된 신청도 남는다").isEqualTo(409);
		assertThat(docs.get(0).getLong("run_id")).isEqualTo(9001L);
	}

	@Test
	void 요청에서_남긴_이벤트가_MongoDB에_쌓인다_실패_요청과_클릭_간격_포함() throws Exception {
		String ci = hash();
		long id = member(ci);
		String session = UUID.randomUUID().toString();
		String bearer = "Bearer " + tokens.issue(ActorType.MEMBER, id, session).value();

		for (boolean fail : new boolean[] {true, false, false}) {
			mvc.perform(post("/api/v1/test-probe/seat-hold").param("runId", "9001").param("fail", String.valueOf(fail))
					.header("Authorization", bearer).header("X-Device-Id", "device-abcdef12")
					.with(r -> {
						r.setRemoteAddr("203.0.113.9");
						return r;
					}));
			Thread.sleep(30); // 간격이 0이 아니게
		}

		List<Document> docs = await(Query.query(Criteria.where("member_id").is(id)).with(
				org.springframework.data.domain.Sort.by("ts")), 3);
		assertThat(docs).hasSize(3);
		Document first = docs.get(0);
		assertThat(first.getString("event_type")).isEqualTo("SEAT_HOLD");
		assertThat(first.getInteger("response_status")).as("예외로 끝난 요청도 기록").isEqualTo(409);
		assertThat(first.getString("ci_hash")).as("컨슈머가 member_id로 붙임").isEqualTo(ci);
		assertThat(first.getString("session_id")).isEqualTo(session);
		assertThat(first.getString("ip_hash")).isEqualTo(pseudonymizer.ip("203.0.113.9"));
		assertThat(first.getString("device_hash")).isEqualTo(pseudonymizer.device("device-abcdef12"));
		assertThat(first.getLong("run_id")).isEqualTo(9001L);
		assertThat(first.get("expire_at")).isNotNull();
		assertThat(first.get("meta", Document.class)).containsKey("seatIds").doesNotContainKey("name");
		assertThat(first.toJson()).doesNotContain("203.0.113.9", "device-abcdef12", "홍길동");

		assertThat(first.get("interval_ms")).as("세션 첫 이벤트").isNull();
		assertThat(docs.get(1).getInteger("interval_ms")).isNotNull().isGreaterThanOrEqualTo(0);
		assertThat(docs.get(2).getInteger("response_status")).isEqualTo(200);
	}

	@Test
	void 같은_이벤트가_다시_와도_한_번만_저장() throws Exception {
		long id = member(hash());
		String eventId = UUID.randomUUID().toString();
		eventIds.add(eventId);
		String json = JSON.writeValueAsString(event(eventId, id));
		produce("m:" + id, json);
		produce("m:" + id, json); // 재전달

		String marker = UUID.randomUUID().toString(); // 뒤따르는 이벤트가 저장될 때까지 기다려 두 번째가 처리됐음을 보장
		eventIds.add(marker);
		produce("m:" + id, JSON.writeValueAsString(event(marker, id)));
		assertThat(await(Query.query(Criteria.where("event_id").is(marker)), 1)).hasSize(1);

		assertThat(mongo.count(Query.query(Criteria.where("event_id").is(eventId)), "behavior_log")).isEqualTo(1);
	}

	@Test
	void 깨진_메시지가_와도_컨슈머가_멈추지_않는다() throws Exception {
		long id = member(hash());
		produce("m:" + id, "{not json");
		produce("m:" + id, "{\"eventId\":\"x\",\"eventType\":\"DROP\"}");
		String ok = UUID.randomUUID().toString();
		eventIds.add(ok);
		produce("m:" + id, JSON.writeValueAsString(event(ok, id)));
		assertThat(await(Query.query(Criteria.where("event_id").is(ok)), 1)).hasSize(1);
	}
}
