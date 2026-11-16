package com.jejari.risk.cache;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jejari.risk.SubjectType;

/**
 * RISK-03 캐시 재동기화. Oracle {@code risk_score}(원본)를 Redis {@code risk:*}에 그대로 맞춘다.
 * 앱 시작 직후 한 번, 그 뒤 {@code jejari.risk.cache-resync-interval}(기본 10분)마다 돈다.
 *
 * <ol>
 *   <li>final_score &gt; 0 인 행을 모두 SET (Redis 재시작·삭제, 쓰기 실패로 빠진 키 복구)</li>
 *   <li>Redis에만 남은 {@code risk:{CI|PAYMENT|DEVICE}:*} 키를 삭제 (Oracle에서 0점이 됐는데 삭제가 실패한 키)</li>
 *   <li>{@link RiskScoreCache#SYNCED_AT_KEY}에 완료 시각 기록</li>
 * </ol>
 *
 * <p>위험 점수 전용 실행기({@link RiskJobRunner}, 스레드 1개)에서 돈다. 대기열 입장 스케줄러를 막지 않고,
 * 같은 실행기를 쓰는 점수 배치와 동시에 돌지 않아 서로의 값을 덮어쓰지 않는다.
 */
@Component
// 개발 모드는 Oracle 점수 원본 없이 risk:*를 Redis에 직접 넣으므로(부하 테스트) 재동기화가 그 키를 지우지 않게 끈다
@ConditionalOnExpression("${jejari.risk.cache-resync-enabled:true} and !${jejari.dev-mode:false}")
public class RiskCacheResync {
	private static final Logger log = LoggerFactory.getLogger(RiskCacheResync.class);
	private static final int BATCH = 1000;

	private final JdbcTemplate jdbc;
	private final StringRedisTemplate redis;
	private final RiskScoreCache cache;
	private final RiskJobRunner runner;
	private final Duration resyncInterval;
	private final Duration markerCheckInterval;

	public RiskCacheResync(JdbcTemplate jdbc, StringRedisTemplate redis, RiskScoreCache cache, RiskJobRunner runner,
			@Value("${jejari.risk.cache-resync-interval:10m}") Duration resyncInterval,
			@Value("${jejari.risk.cache-marker-check-interval:30s}") Duration markerCheckInterval) {
		this.jdbc = jdbc;
		this.redis = redis;
		this.cache = cache;
		this.runner = runner;
		this.resyncInterval = resyncInterval;
		this.markerCheckInterval = markerCheckInterval;
	}

	/**
	 * 앱이 요청을 받기 시작할 때 바로 한 번, 그 뒤 주기마다 전체 재동기화.
	 * 그와 별도로 표시 키를 짧은 간격으로 확인해, Redis가 재시작·초기화돼 키가 사라졌으면 주기를 기다리지 않고 바로 재동기화한다.
	 * 표시 키가 없는 동안 대기열은 요청마다 Oracle을 읽으므로 그 시간을 짧게 줄이기 위해서다.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		runner.scheduleWithFixedDelay(this::runOnce, resyncInterval);
		runner.scheduleWithFixedDelay(this::resyncIfMarkerMissing, markerCheckInterval);
	}

	void resyncIfMarkerMissing() {
		try {
			if (!Boolean.TRUE.equals(redis.hasKey(RiskScoreCache.SYNCED_AT_KEY))) {
				log.warn("위험 점수 동기화 표시 키가 없음 (Redis 재시작·초기화?) → 즉시 재동기화");
				runOnce();
			}
		} catch (RuntimeException e) {
			log.error("위험 점수 동기화 표시 키 확인 실패", e);
		}
	}

	void runOnce() {
		try {
			Result r = resync();
			log.info("위험 점수 캐시 재동기화: 기록 {}건, 정리 {}건", r.written(), r.removed());
		} catch (RuntimeException e) {
			// 다음 주기에 다시 시도한다. 표시 키를 갱신하지 않으므로 마지막 성공 시각으로 지연을 알 수 있다
			log.error("위험 점수 캐시 재동기화 실패", e);
		}
	}

	public Result resync() {
		Map<String, String> wanted = new HashMap<>();
		jdbc.query("SELECT subject_type, subject_hash, final_score FROM risk_score WHERE final_score > 0", rs -> {
			SubjectType type = SubjectType.valueOf(rs.getString(1));
			BigDecimal score = rs.getBigDecimal(3);
			wanted.put(RiskScoreCache.key(type, rs.getString(2)), RiskScoreCache.format(score));
		});

		// MSET 한 번에 너무 많이 보내 Redis를 오래 붙잡지 않게 1000개씩
		Map<String, String> chunk = new HashMap<>();
		for (Map.Entry<String, String> e : wanted.entrySet()) {
			chunk.put(e.getKey(), e.getValue());
			if (chunk.size() == BATCH) {
				redis.opsForValue().multiSet(chunk);
				chunk.clear();
			}
		}
		if (!chunk.isEmpty()) {
			redis.opsForValue().multiSet(chunk);
		}

		int removed = 0;
		for (SubjectType type : SubjectType.values()) {
			ScanOptions scan = ScanOptions.scanOptions().match("risk:" + type.name() + ":*").count(BATCH).build();
			List<String> stale = new ArrayList<>();
			try (Cursor<String> c = redis.scan(scan)) {
				c.forEachRemaining(k -> {
					if (!wanted.containsKey(k)) {
						stale.add(k);
					}
				});
			}
			if (!stale.isEmpty()) {
				redis.delete(stale);
				removed += stale.size();
			}
		}
		cache.markSynced();
		return new Result(wanted.size(), removed);
	}

	public record Result(int written, int removed) {
	}
}
