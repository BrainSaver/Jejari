package com.jejari.replay.sink;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;

import com.jejari.common.config.AppPolicy;
import com.jejari.replay.ReplayBatch;
import com.jejari.replay.ReplayMessage;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;

import tools.jackson.databind.json.JsonMapper;

/**
 * 녹화 적재 컨슈머 (그룹 {@code replay-sink}). 메시지 하나 = 한 세션의 묶음.
 *
 * <pre>
 * 1. replay_session upsert (처음이면 started_at·보관 기한·모드, 매번 ended_at·event_count·run_ids) → 갱신된 문서를 받음
 * 2. 세션 상한(replay.max_events_per_session)을 넘는 이벤트는 버림
 * 3. replay_event insertMany (unordered, (session_id, seq) 중복은 무시 = 재전달 안전)
 *    expire_at = 세션 보관 기한. 세션이 legal_hold면 expire_at 없이
 * 4. 저장한 뒤 세션을 다시 읽어 그 사이 legal_hold가 걸렸으면 방금 넣은 것의 expire_at을 지움 (경합 보정)
 * </pre>
 *
 * <ul>
 *   <li><b>시계열 컬렉션 대신 일반 컬렉션</b>: ERD 비고는 시계열이지만, MongoDB 시계열 컬렉션은 유니크 인덱스를 못 만들어
 *       재전달 중복을 막을 수 없다 (DB설계서는 {session_id, seq} 유니크를 요구). 한 세션 조회는 {session_id, seq} 인덱스로 충분.</li>
 *   <li><b>삭제 보류(legal_hold)</b>: 케이스가 열리면 {@code ReplayHold}가 세션과 이벤트의 expire_at을 지운다. 90일 TTL이
 *       조사 중인 증거를 지우지 않게.</li>
 * </ul>
 */
public class ReplaySink {
	private static final Logger log = LoggerFactory.getLogger(ReplaySink.class);
	static final String SESSIONS = "replay_session";
	static final String EVENTS = "replay_event";
	private static final long DAY_MS = 86_400_000L;

	private final MongoTemplate mongo;
	private final JdbcTemplate jdbc;
	private final JsonMapper json;
	private final AppPolicy policy;
	private final Map<Long, String> ciCache = new ConcurrentHashMap<>();
	private volatile boolean indexesReady;

	public ReplaySink(MongoTemplate mongo, JdbcTemplate jdbc, JsonMapper json, AppPolicy policy) {
		this.mongo = mongo;
		this.jdbc = jdbc;
		this.json = json;
		this.policy = policy;
	}

	@KafkaListener(id = "replay-sink", topics = ReplayMessage.TOPIC, containerFactory = ReplaySinkConfig.CONTAINER_FACTORY,
			autoStartup = "${jejari.replay.sink-enabled:true}")
	public void consume(List<ConsumerRecord<String, String>> records) {
		ensureIndexes();
		int skipped = 0;
		for (ConsumerRecord<String, String> r : records) {
			ReplayMessage m;
			try {
				m = json.readValue(r.value(), ReplayMessage.class);
			} catch (RuntimeException e) {
				skipped++;
				continue;
			}
			if (m.sessionId() == null || m.events() == null || m.events().isEmpty()) {
				skipped++;
				continue;
			}
			store(m);
		}
		if (skipped > 0) {
			log.warn("녹화 메시지 형식 오류 {}건을 건너뜀", skipped);
		}
	}

	void store(ReplayMessage m) {
		List<ReplayBatch.Event> events = m.events();
		long first = events.stream().mapToLong(ReplayBatch.Event::ts).min().orElse(m.receivedAt());
		long last = events.stream().mapToLong(ReplayBatch.Event::ts).max().orElse(m.receivedAt());
		long retentionMs = policy.getLong(AppPolicy.REPLAY_RETENTION_DAYS) * DAY_MS;
		List<Long> runIds = events.stream().map(ReplayBatch.Event::runId).filter(x -> x != null).distinct().toList();

		Document session = mongo.getCollection(SESSIONS).findOneAndUpdate(new Document("_id", m.sessionId()),
				Updates.combine(
						Updates.setOnInsert("session_id", m.sessionId()),
						Updates.setOnInsert("member_id", m.memberId()),
						Updates.setOnInsert("ci_hash", ci(m.memberId())),
						Updates.setOnInsert("device_hash", m.deviceHash()),
						Updates.setOnInsert("ip_hash", m.ipHash()),
						Updates.setOnInsert("retention_until", new Date(first + retentionMs)),
						Updates.setOnInsert("expire_at", new Date(first + retentionMs)),
						Updates.setOnInsert("legal_hold", false),
						Updates.setOnInsert("mask_policy_version", "v1-length-only"),
						Updates.min("started_at", new Date(first)),
						Updates.max("ended_at", new Date(last)),
						Updates.setOnInsert("recording_mode", m.mode()), // SAMPLED → FULL로만 바뀐다 (아래에서)
						Updates.addEachToSet("run_ids", runIds)),
				new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
		if ("FULL".equals(m.mode()) && !"FULL".equals(session.getString("recording_mode"))) {
			mongo.getCollection(SESSIONS).updateOne(new Document("_id", m.sessionId()),
					Updates.set("recording_mode", "FULL"));
		}

		long max = policy.getLong(AppPolicy.REPLAY_MAX_EVENTS_PER_SESSION);
		long have = session.get("event_count") instanceof Number n ? n.longValue() : 0;
		if (have >= max) {
			return; // 세션 상한 (매크로가 이벤트를 쏟아부어도 저장량이 끝없이 늘지 않게)
		}
		long startedAt = session.getDate("started_at").getTime();
		boolean hold = Boolean.TRUE.equals(session.getBoolean("legal_hold"));
		Date expireAt = hold ? null : session.getDate("retention_until");
		List<Document> docs = new ArrayList<>();
		for (ReplayBatch.Event e : events) {
			if (have + docs.size() >= max) {
				break;
			}
			docs.add(toDocument(m.sessionId(), startedAt, expireAt, e));
		}
		int inserted = insert(docs);
		mongo.getCollection(SESSIONS).updateOne(new Document("_id", m.sessionId()), Updates.inc("event_count", inserted));

		// 저장하는 사이에 삭제 보류가 걸렸을 수 있다 → 다시 확인하고 방금 넣은 것도 TTL에서 뺀다
		if (!hold) {
			Document now = mongo.getCollection(SESSIONS).find(new Document("_id", m.sessionId()))
					.projection(new Document("legal_hold", 1)).first();
			if (now != null && Boolean.TRUE.equals(now.getBoolean("legal_hold"))) {
				mongo.getCollection(EVENTS).updateMany(new Document("session_id", m.sessionId()), Updates.unset("expire_at"));
			}
		}
	}

	static Document toDocument(String sessionId, long startedAt, Date expireAt, ReplayBatch.Event e) {
		Document d = new Document("session_id", sessionId).append("ts", new Date(e.ts())).append("seq", e.seq())
				.append("t_offset_ms", Math.max(0, e.ts() - startedAt)).append("type", e.type())
				.append("screen", e.screen());
		if (e.target() != null) {
			d.append("target", e.target());
		}
		if (e.x() != null) {
			d.append("x", e.x());
		}
		if (e.y() != null) {
			d.append("y", e.y());
		}
		if (e.path() != null) {
			List<List<Integer>> path = new ArrayList<>();
			for (int[] p : e.path()) {
				path.add(List.of(p[0], p[1], p[2]));
			}
			d.append("path", path);
		}
		if (e.inputLen() != null) {
			d.append("input_masked", "len:" + e.inputLen()); // 값은 받지도 않는다. 길이만
		}
		if (e.runId() != null) {
			d.append("run_id", e.runId());
		}
		if (e.label() != null) {
			d.append("label", e.label());
		}
		if (e.boxes() != null) {
			List<Document> boxes = new ArrayList<>();
			for (ReplayBatch.Box b : e.boxes()) {
				Document bd = new Document("k", b.k()).append("x", b.x()).append("y", b.y()).append("w", b.w())
						.append("h", b.h());
				if (b.id() != null) {
					bd.append("id", b.id());
				}
				if (b.label() != null) {
					bd.append("label", b.label());
				}
				if (b.off()) {
					bd.append("off", true);
				}
				boxes.add(bd);
			}
			d.append("boxes", boxes);
		}
		if (expireAt != null) {
			d.append("expire_at", expireAt);
		}
		return d;
	}

	/** @return 새로 들어간 수 (재전달 중복 제외) */
	int insert(List<Document> docs) {
		if (docs.isEmpty()) {
			return 0;
		}
		try {
			mongo.getCollection(EVENTS).insertMany(docs, new InsertManyOptions().ordered(false));
			return docs.size();
		} catch (MongoBulkWriteException e) {
			boolean onlyDuplicates = e.getWriteConcernError() == null
					&& e.getWriteErrors().stream().allMatch(w -> w.getCode() == 11000);
			if (!onlyDuplicates) {
				throw e; // 다시 시도
			}
			return docs.size() - e.getWriteErrors().size();
		}
	}

	private String ci(long memberId) {
		return ciCache.computeIfAbsent(memberId, id -> {
			List<String> r = jdbc.queryForList("SELECT ci_hash FROM member WHERE member_id = ?", String.class, id);
			return r.isEmpty() ? null : r.get(0);
		});
	}

	private void ensureIndexes() {
		if (indexesReady) {
			return;
		}
		try {
			MongoCollection<Document> ev = mongo.getCollection(EVENTS);
			ev.createIndex(Indexes.ascending("session_id", "seq"), new IndexOptions().name("ux_session_seq").unique(true));
			ev.createIndex(Indexes.ascending("expire_at"), new IndexOptions().name("ttl_expire_at").expireAfter(0L,
					TimeUnit.SECONDS));
			MongoCollection<Document> s = mongo.getCollection(SESSIONS);
			s.createIndex(Indexes.ascending("ci_hash", "started_at"), new IndexOptions().name("ix_ci_started"));
			s.createIndex(Indexes.ascending("device_hash", "started_at"), new IndexOptions().name("ix_device_started"));
			s.createIndex(Indexes.ascending("member_id", "started_at"), new IndexOptions().name("ix_member_started"));
			s.createIndex(Indexes.ascending("expire_at"), new IndexOptions().name("ttl_expire_at").expireAfter(0L,
					TimeUnit.SECONDS));
			indexesReady = true;
		} catch (MongoCommandException e) {
			log.error("replay 인덱스를 만들지 못함 (code {}). 적재는 계속합니다", e.getErrorCode());
			indexesReady = true;
		}
	}
}
