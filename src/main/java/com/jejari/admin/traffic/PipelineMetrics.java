package com.jejari.admin.traffic;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.jejari.behavior.BehaviorPublisher;
import com.jejari.behavior.sink.BehaviorSink;
import com.jejari.event.EventTopics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 사람이 몰릴 때 <b>데이터를 놓치거나 밀리는지</b> 지표로 내보낸다 (A-10 "데이터 놓침·밀림").
 *
 * <ul>
 *   <li><b>행동 로그 (봇 탐지 재료)</b>: {@code jejari_behavior_sent_total}(Kafka가 받음) · {@code _dropped_total}(보내기 큐가 넘쳐 버림)
 *       · {@code _failed_total}(Kafka 전송 실패) · {@code jejari_behavior_queue_size}(보내기 큐, 최대 5만).
 *       {@link BehaviorPublisher}는 예매 응답을 지키려고 몰릴 때 로그를 버리게 짜여 있다. 버리는 것 자체는 설계지만, <b>얼마나 버렸는지가
 *       보이지 않으면</b> 정작 봇이 몰리는 순간의 재료가 빠진 것을 모른다</li>
 *   <li><b>행동 로그 적재</b>: {@code jejari_behavior_stored_total}(MongoDB 저장) · {@code _skipped_total}(형식 오류로 건너뜀).
 *       건너뛴 것은 오프셋이 커밋되어 <b>밀림(lag)에도 안 잡히는 조용한 유실</b>이다 (예전 dev 모드의 "dev-session"처럼 UUID가 아닌
 *       세션 ID가 오면 전부 건너뛰는 것을 실측에서 확인)</li>
 *   <li><b>Kafka 소비 밀림</b>: {@code jejari_kafka_consumer_lag{group}} = 파티션마다 (끝 오프셋 − 커밋한 오프셋)의 합.
 *       Spring Kafka는 이 값을 내지 않아 15초마다 AdminClient로 잰다. 밀림이 커지면 적재·좌석 정리·알림이 늦는 것</li>
 *   <li><b>outbox 미발행</b>: {@code jejari_outbox_pending}·{@code _failed}·{@code _oldest_seconds}. 예약 이벤트가 Kafka로 아직 못 나간 것.
 *       {@code ix_outbox_pending}이 PENDING·FAILED 행만 담는 함수 기반 인덱스라 그 행만 읽는다 (쓰기 폭주 테이블 풀스캔 없음)</li>
 * </ul>
 *
 * <p>재는 쪽(Kafka·Oracle)이 죽어 있으면 값은 NaN(없음)이다. 앱이 여러 대면 같은 값을 각자 내므로 화면 쿼리는 max로 묶는다.
 * 테스트에서는 끈다 ({@code jejari.ops.pipeline-metrics-enabled=false}, build.gradle).
 */
@Component
@ConditionalOnProperty(name = "jejari.ops.pipeline-metrics-enabled", havingValue = "true", matchIfMissing = true)
public class PipelineMetrics implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(PipelineMetrics.class);

	/** 잴 소비 그룹 → 화면 이름. 순서 = 화면 순서 */
	static final Map<String, String> GROUPS = linked(
			"behavior-sink", "행동 로그 적재",
			"replay-sink", "재생 기록 적재",
			EventTopics.GROUP_SEAT_RELEASE, "좌석 정리",
			EventTopics.GROUP_NOTIFICATION, "알림",
			EventTopics.GROUP_RISK_SIGNAL, "위험 신호",
			EventTopics.GROUP_REFUND, "환불");

	private final ObjectProvider<BehaviorPublisher> publisher;
	private final ObjectProvider<BehaviorSink> sink;
	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final String bootstrap;
	private final Map<String, Double> lag = new ConcurrentHashMap<>();
	private volatile double lagOk = Double.NaN;      // 마지막 밀림 측정이 성공했나 (1/0). Kafka가 죽으면 0 → "잴 수 없음"
	private volatile double outboxPending = Double.NaN;
	private volatile double outboxFailed = Double.NaN;
	private volatile double outboxOldest = Double.NaN;
	private volatile Admin admin;

	public PipelineMetrics(ObjectProvider<BehaviorPublisher> publisher, ObjectProvider<BehaviorSink> sink, JdbcTemplate jdbc, Clock clock, MeterRegistry meters,
			@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
		this.publisher = publisher;
		this.sink = sink;
		this.jdbc = jdbc;
		this.clock = clock;
		this.bootstrap = bootstrap;
		FunctionCounter.builder("jejari.behavior.sent", this, m -> m.pub(BehaviorPublisher::sentCount))
				.description("행동 로그: Kafka가 받은 건수").register(meters);
		FunctionCounter.builder("jejari.behavior.dropped", this, m -> m.pub(BehaviorPublisher::droppedCount))
				.description("행동 로그: 보내기 큐가 넘쳐 버린 건수").register(meters);
		FunctionCounter.builder("jejari.behavior.failed", this, m -> m.pub(BehaviorPublisher::failedCount))
				.description("행동 로그: Kafka 전송 실패 건수 (다시 보내기까지 실패한 것)").register(meters);
		FunctionCounter.builder("jejari.behavior.retried", this, m -> m.pub(BehaviorPublisher::retriedCount))
				.description("행동 로그: 전송 실패 뒤 다시 보낸 횟수").register(meters);
		// 디스크 버퍼 (유실 아님: Kafka가 받을 때 다시 보냄). 적은 것(spilled)과 다시 넣은 것(replayed)이 벌어진 채로 있으면 아직 밀린 것
		FunctionCounter.builder("jejari.behavior.spilled", this, m -> m.pub(BehaviorPublisher::spilledCount))
				.description("행동 로그: Kafka로 못 보내 디스크 버퍼에 적은 건수").register(meters);
		FunctionCounter.builder("jejari.behavior.replayed", this, m -> m.pub(BehaviorPublisher::replayedCount))
				.description("행동 로그: 디스크 버퍼에서 다시 보낸 건수").register(meters);
		Gauge.builder("jejari.behavior.spill.bytes", this, m -> m.pub(BehaviorPublisher::spillPendingBytes))
				.description("행동 로그 디스크 버퍼에 남은 바이트").baseUnit("bytes").register(meters);
		// 적재 쪽 (Kafka → MongoDB). 받음(sent)과 적재(stored)가 벌어지면 어딘가에서 새고 있다
		FunctionCounter.builder("jejari.behavior.stored", this, m -> m.sinkCount(BehaviorSink::storedCount))
				.description("행동 로그: MongoDB에 저장된 건수").register(meters);
		FunctionCounter.builder("jejari.behavior.skipped", this, m -> m.sinkCount(BehaviorSink::skippedCount))
				.description("행동 로그: 형식 오류로 적재하지 않고 건너뛴 건수 (다시 오지 않음)").register(meters);
		Gauge.builder("jejari.behavior.queue.size", this, m -> m.pub(p -> p.queueSize()))
				.description("행동 로그 보내기 큐에 쌓인 건수 (최대 50000)").register(meters);
		GROUPS.keySet().forEach(g -> Gauge.builder("jejari.kafka.consumer.lag", lag, x -> x.getOrDefault(g, Double.NaN))
				.tag("group", g).description("Kafka 소비 그룹이 밀린 메시지 수").register(meters));
		Gauge.builder("jejari.kafka.lag.measured", this, m -> m.lagOk).description("Kafka 소비 밀림 측정 성공 1 · 실패 0").register(meters);
		Gauge.builder("jejari.outbox.pending", this, m -> m.outboxPending).description("발행 대기 outbox 이벤트").register(meters);
		Gauge.builder("jejari.outbox.failed", this, m -> m.outboxFailed).description("발행 실패 outbox 이벤트").register(meters);
		Gauge.builder("jejari.outbox.oldest.seconds", this, m -> m.outboxOldest)
				.description("가장 오래 발행을 기다린 outbox 이벤트의 나이 (초)").register(meters);
	}

	private double pub(java.util.function.ToDoubleFunction<BehaviorPublisher> f) {
		BehaviorPublisher p = publisher.getIfAvailable();
		return p == null ? Double.NaN : f.applyAsDouble(p);
	}

	private double sinkCount(java.util.function.ToDoubleFunction<BehaviorSink> f) {
		BehaviorSink s = sink.getIfAvailable();
		return s == null ? Double.NaN : f.applyAsDouble(s);
	}

	@Scheduled(fixedDelayString = "${jejari.ops.pipeline-interval-ms:15000}", initialDelay = 5000)
	public void refresh() {
		refreshOutbox();
		refreshLag();
	}

	void refreshOutbox() {
		try {
			jdbc.query("""
					SELECT COUNT(*), SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), MIN(created_at)
					  FROM outbox_event
					 WHERE CASE WHEN status IN ('PENDING', 'FAILED') THEN shard_no END IS NOT NULL""", rs -> {
				outboxPending = rs.getLong(1);
				outboxFailed = rs.getLong(2);
				Timestamp oldest = rs.getTimestamp(3);
				outboxOldest = oldest == null ? 0 : Math.max(0, (clock.millis() - oldest.getTime()) / 1000.0);
			});
		} catch (RuntimeException e) {
			outboxPending = outboxFailed = outboxOldest = Double.NaN;
			log.debug("outbox 미발행 집계 실패: {}", e.toString());
		}
	}

	void refreshLag() {
		try {
			Admin a = admin();
			for (String g : GROUPS.keySet()) {
				Map<TopicPartition, OffsetAndMetadata> committed = a.listConsumerGroupOffsets(g)
						.partitionsToOffsetAndMetadata().get(3, TimeUnit.SECONDS);
				if (committed == null || committed.isEmpty()) {
					lag.put(g, Double.NaN);       // 아직 소비한 적 없는 그룹 (기능이 꺼져 있는 등)
					continue;
				}
				Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
				committed.keySet().forEach(tp -> latest.put(tp, OffsetSpec.latest()));
				Map<TopicPartition, ListOffsetsResultInfo> end = a.listOffsets(latest).all().get(3, TimeUnit.SECONDS);
				Map<TopicPartition, Long> endOffsets = new HashMap<>();
				end.forEach((tp, info) -> endOffsets.put(tp, info.offset()));
				lag.put(g, (double) lag(committed, endOffsets));
			}
			lagOk = 1;
		} catch (Exception e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			GROUPS.keySet().forEach(g -> lag.put(g, Double.NaN));
			lagOk = 0;
			log.debug("Kafka 소비 밀림 측정 실패: {}", e.toString());
		}
	}

	/** 파티션마다 (끝 − 커밋)을 더한다. 커밋이 끝보다 앞서 보이는 순간(측정 사이에 커밋)은 0으로 */
	static long lag(Map<TopicPartition, OffsetAndMetadata> committed, Map<TopicPartition, Long> end) {
		long sum = 0;
		for (var e : committed.entrySet()) {
			Long last = end.get(e.getKey());
			if (last != null && e.getValue() != null) {
				sum += Math.max(0, last - e.getValue().offset());
			}
		}
		return sum;
	}

	private Admin admin() {
		Admin a = admin;
		if (a == null) {
			Properties p = new Properties();
			p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
			p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
			p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);
			p.put(AdminClientConfig.CLIENT_ID_CONFIG, "jejari-pipeline-metrics");
			admin = a = Admin.create(p);
		}
		return a;
	}

	@Override
	public void destroy() {
		Admin a = admin;
		if (a != null) {
			a.close(java.time.Duration.ofSeconds(2));
		}
	}

	private static Map<String, String> linked(String... kv) {
		Map<String, String> m = new java.util.LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put(kv[i], kv[i + 1]);
		}
		return java.util.Collections.unmodifiableMap(m);
	}

	List<String> groups() {
		return List.copyOf(GROUPS.keySet());
	}
}
