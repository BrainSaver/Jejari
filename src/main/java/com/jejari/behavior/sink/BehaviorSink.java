package com.jejari.behavior.sink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.bson.BsonType;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;

import com.jejari.behavior.BehaviorEvent;
import com.jejari.behavior.sink.BehaviorDocuments.Parsed;
import com.jejari.risk.SubjectType;
import com.jejari.risk.batch.RiskDirtyQueue;
import com.jejari.risk.batch.RiskSubject;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;

import tools.jackson.databind.json.JsonMapper;

/**
 * 행동 로그 적재 컨슈머 (RISK-01). Kafka {@code jejari.behavior} → MongoDB {@code behavior_log}.
 *
 * <pre>
 * poll(최대 behavior.batch_size건) → 형식 검사(깨진 메시지는 건너뜀) → ci_hash 붙이기 → interval_ms 계산
 *   → insertMany(ordered:false) → CI·기기를 위험 점수 재계산 대상(risk:dirty)으로 표시 → 성공하면 오프셋 커밋
 * </pre>
 * <ul>
 *   <li><b>중복</b>: 재전달된 이벤트는 event_id 유니크 인덱스에 걸린다. 중복 키 오류(11000)만 있으면 성공으로 본다.</li>
 *   <li><b>MongoDB 장애</b>: 예외 → 커밋하지 않고 5초 뒤 같은 묶음을 다시 시도 (무한, {@link BehaviorSinkConfig}).
 *       Kafka가 7일 보관하므로 그동안 잃지 않는다. 깨진 메시지 때문에 멈추는 일은 없다 (검사에서 걸러짐).</li>
 *   <li><b>ci_hash</b>: 요청 경로에서 DB를 읽지 않으려고 여기서 member_id로 찾는다. 회원의 CI는 바뀌지 않으므로 캐시한다.</li>
 * </ul>
 */
public class BehaviorSink {
	private static final Logger log = LoggerFactory.getLogger(BehaviorSink.class);
	private static final int CI_CACHE_MAX = 200_000;

	private final MongoTemplate mongo;
	private final JdbcTemplate jdbc;
	private final JsonMapper json;
	private final BehaviorPolicy policy;
	private final RiskDirtyQueue riskDirty;
	private final IntervalTracker intervals = new IntervalTracker(200_000);
	private final Map<Long, String> ciCache = new ConcurrentHashMap<>();
	private volatile boolean indexesReady;

	public BehaviorSink(MongoTemplate mongo, JdbcTemplate jdbc, JsonMapper json, BehaviorPolicy policy,
			RiskDirtyQueue riskDirty) {
		this.mongo = mongo;
		this.jdbc = jdbc;
		this.json = json;
		this.policy = policy;
		this.riskDirty = riskDirty;
	}

	@KafkaListener(id = "behavior-sink", topics = BehaviorEvent.TOPIC,
			containerFactory = BehaviorSinkConfig.CONTAINER_FACTORY,
			autoStartup = "${jejari.behavior.sink-enabled:true}")
	public void consume(List<ConsumerRecord<String, String>> records) {
		List<Parsed> parsed = new ArrayList<>(records.size());
		int skipped = 0;
		for (ConsumerRecord<String, String> r : records) {
			Parsed p = BehaviorDocuments.parse(r.value(), json);
			if (p == null) {
				skipped++;
				continue;
			}
			parsed.add(p);
		}
		if (skipped > 0) {
			// 값은 남기지 않는다 (누가 넣었는지 모르는 데이터)
			log.warn("행동 로그 형식 오류 {}건을 건너뜀 (토픽 {})", skipped, BehaviorEvent.TOPIC);
		}
		if (parsed.isEmpty()) {
			return;
		}
		ensureIndexes();
		Map<Long, String> ci = ciHashes(parsed);
		long retention = policy.retentionDays();
		IntervalTracker.Batch batch = intervals.batch();
		List<Document> docs = new ArrayList<>(parsed.size());
		for (Parsed p : parsed) {
			BehaviorEvent e = p.event();
			String sessionKey = e.sessionId() != null ? e.sessionId() : e.deviceHash();
			docs.add(BehaviorDocuments.toDocument(p, e.memberId() == null ? null : ci.get(e.memberId()),
					batch.next(sessionKey, p.ts()), retention));
		}
		insert(docs);
		markRiskSubjects(docs);
		batch.commit(); // 저장에 성공한 뒤에만 (재시도 때 간격이 0으로 바뀌지 않게)
	}

	/**
	 * 중복 방지 유니크 인덱스와 보관 기간 TTL 인덱스를 보장한다. mongo-init.js는 볼륨을 처음 만들 때만 돌아서,
	 * 그 뒤에 추가된 인덱스는 기존 볼륨에 없다 (실제로 event_id 인덱스가 빠져 중복이 저장됐음).
	 * createIndex는 이미 있으면 아무 일도 안 한다. 실패하면(Mongo 장애) 다음 묶음에서 다시 시도한다.
	 */
	private void ensureIndexes() {
		if (indexesReady) {
			return;
		}
		MongoCollection<Document> c = mongo.getCollection("behavior_log");
		boolean ok = createIndex(c, "ux_event_id", () -> c.createIndex(Indexes.ascending("event_id"),
				new IndexOptions().name("ux_event_id").unique(true)
						.partialFilterExpression(Filters.type("event_id", BsonType.STRING))));
		ok &= createIndex(c, "ttl_expire_at", () -> c.createIndex(Indexes.ascending("expire_at"),
				new IndexOptions().name("ttl_expire_at").expireAfter(0L, TimeUnit.SECONDS)));
		indexesReady = true;
		if (ok) {
			log.info("behavior_log 인덱스 확인 완료 (ux_event_id, ttl_expire_at)");
		}
	}

	/**
	 * 인덱스를 만들 수 없는 상태(이미 중복 문서가 있음, 같은 이름·다른 옵션)는 적재를 멈추지 않고 오류만 남긴다.
	 * 그 외(연결 실패 등)는 예외 → 이 묶음을 다시 시도.
	 */
	private static boolean createIndex(MongoCollection<Document> c, String name, Runnable create) {
		try {
			create.run();
			return true;
		} catch (MongoCommandException | MongoWriteException e) {
			log.error("behavior_log 인덱스 {}를 만들지 못함 (code {}). 중복 문서나 같은 이름의 다른 인덱스를 정리해야 합니다. "
					+ "적재는 계속하지만 그동안 중복 방지·보관 기간 삭제가 안 될 수 있습니다", name,
					e instanceof MongoCommandException ce ? ce.getErrorCode() : ((MongoWriteException) e).getCode());
			return false;
		}
	}

	/**
	 * 저장한 이벤트의 CI·기기를 위험 점수 재계산 대상으로 표시한다 (RISK-02 1분 배치가 읽음).
	 * 실패하면 예외 → 이 묶음을 다시 처리한다 (저장은 event_id 중복으로 무시됨). 표시를 잃으면 그 대상의 점수가
	 * 다음 활동 전까지 안 바뀌므로, 로그를 남기고 넘어가지 않는다.
	 */
	private void markRiskSubjects(List<Document> docs) {
		Set<RiskSubject> subjects = new LinkedHashSet<>();
		for (Document d : docs) {
			RiskSubject ci = RiskSubject.ofNullable(SubjectType.CI, d.getString("ci_hash"));
			RiskSubject device = RiskSubject.ofNullable(SubjectType.DEVICE, d.getString("device_hash"));
			if (ci != null) {
				subjects.add(ci);
			}
			if (device != null) {
				subjects.add(device);
			}
		}
		riskDirty.markAll(subjects);
	}

	void insert(List<Document> docs) {
		try {
			mongo.getCollection("behavior_log").insertMany(docs, new InsertManyOptions().ordered(false));
		} catch (MongoBulkWriteException e) {
			List<BulkWriteError> errors = e.getWriteErrors();
			boolean onlyDuplicates = e.getWriteConcernError() == null && !errors.isEmpty()
					&& errors.stream().allMatch(w -> w.getCode() == 11000);
			if (!onlyDuplicates) {
				throw e; // 다시 시도
			}
			log.debug("행동 로그 중복 {}건 무시 (재전달)", errors.size());
		}
	}

	/** member_id → ci_hash. 캐시에 없는 것만 한 번에 조회 */
	private Map<Long, String> ciHashes(List<Parsed> parsed) {
		Set<Long> missing = new LinkedHashSet<>();
		for (Parsed p : parsed) {
			Long id = p.event().memberId();
			if (id != null && !ciCache.containsKey(id)) {
				missing.add(id);
			}
		}
		if (!missing.isEmpty()) {
			if (ciCache.size() > CI_CACHE_MAX) {
				ciCache.clear(); // 단순하게 비운다 (다시 채우는 비용은 묶음당 쿼리 1번)
			}
			String in = String.join(",", Collections.nCopies(missing.size(), "?"));
			jdbc.query("SELECT member_id, ci_hash FROM member WHERE member_id IN (" + in + ")",
					rs -> {
						ciCache.put(rs.getLong(1), rs.getString(2));
					}, missing.toArray());
		}
		Map<Long, String> out = new HashMap<>();
		for (Parsed p : parsed) {
			Long id = p.event().memberId();
			if (id != null && ciCache.containsKey(id)) {
				out.put(id, ciCache.get(id));
			}
		}
		return out;
	}
}
