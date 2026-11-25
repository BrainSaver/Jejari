package com.jejari.replay.sink;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.jejari.replay.ReplayBatch;
import com.jejari.replay.ReplayMessage;
import com.jejari.support.FullStackInfraTest;

/**
 * T28 (concurrency.md §13.2): 삭제 보류(legal_hold)를 거는 순간과 녹화 묶음 저장이 겹친다. 적재 컨슈머가 세션을 "보류 아님"으로
 * 읽은 뒤 보류가 걸리면, 그 묶음은 expire_at이 붙은 채 들어갈 수 있다. 문서는 "보류 캐시 시간 뒤 $unset을 한 번 더"로 적었고,
 * 구현({@link ReplaySink#store})은 캐시를 두지 않고 <b>저장한 뒤 세션을 다시 읽어 보류면 방금 넣은 것까지 $unset</b>으로 같은 창을 닫는다.
 * 보류 쪽은 {@code ReplayAdminService.hold}와 같은 두 갱신(세션 legal_hold+unset → 이벤트 unset)을 그 순서로 한다.
 * 30번 동시에 겹쳐도 보류된 세션에 expire_at이 남은 이벤트가 0건이어야 한다.
 */
@FullStackInfraTest
class ReplayHoldRaceInfraTest {

	@Autowired
	ReplaySink sink;
	@Autowired
	MongoTemplate mongo;

	final List<String> sessions = new ArrayList<>();

	@AfterEach
	void tearDown() {
		mongo.getCollection(ReplaySink.EVENTS).deleteMany(Filters.in("session_id", sessions));
		mongo.getCollection(ReplaySink.SESSIONS).deleteMany(Filters.in("_id", sessions));
	}

	@Test
	void T28_보류를_거는_순간_저장된_녹화도_expire_at_없이_남는다() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < 30; round++) {
				String sid = "t28-" + UUID.randomUUID();
				sessions.add(sid);
				long t0 = System.currentTimeMillis();
				sink.store(message(sid, t0, 1, 1)); // 세션이 이미 있고 보류 아님

				CountDownLatch start = new CountDownLatch(1);
				Future<?> store = pool.submit(() -> {
					await(start);
					sink.store(message(sid, t0 + 1000, 100, 20));
				});
				Future<?> hold = pool.submit(() -> {
					await(start);
					mongo.getCollection(ReplaySink.SESSIONS).updateOne(Filters.eq("_id", sid),
							Updates.combine(Updates.set("legal_hold", true), Updates.unset("expire_at")));
					mongo.getCollection(ReplaySink.EVENTS).updateMany(Filters.eq("session_id", sid), Updates.unset("expire_at"));
				});
				start.countDown();
				store.get(30, TimeUnit.SECONDS);
				hold.get(30, TimeUnit.SECONDS);

				assertThat(mongo.getCollection(ReplaySink.EVENTS).countDocuments(Filters.eq("session_id", sid)))
						.as("라운드 %d 저장 수", round).isEqualTo(21);
				assertThat(mongo.getCollection(ReplaySink.EVENTS).countDocuments(
						Filters.and(Filters.eq("session_id", sid), Filters.exists("expire_at"))))
						.as("라운드 %d: 보류된 세션에 TTL이 남은 이벤트", round).isZero();
				Document s = mongo.getCollection(ReplaySink.SESSIONS).find(Filters.eq("_id", sid)).first();
				assertThat(s.getBoolean("legal_hold")).isTrue();
				assertThat(s.containsKey("expire_at")).isFalse();
			}
		} finally {
			pool.shutdownNow();
		}
	}

	private static ReplayMessage message(String sid, long ts, long seqFrom, int n) {
		List<ReplayBatch.Event> events = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			events.add(new ReplayBatch.Event(seqFrom + i, ts + i, "CLICK", "runs/:id/seats", "seat", 10, 10, null, null,
					null, null, null, "m"));
		}
		return new ReplayMessage(sid, 0L, "dev-t28", "ip-t28", "SAMPLED", ts, events);
	}

	private static void await(CountDownLatch l) {
		try {
			l.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
