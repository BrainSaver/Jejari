package com.jejari.admin.traffic;

import java.util.List;

/**
 * A-10 트래픽 관제 응답. 값을 못 구한 칸은 null (그 지표가 아직 수집되지 않았거나 Prometheus가 비어 있음).
 *
 * @param available Prometheus를 읽었나. false면 나머지는 비어 있고 {@code reason}에 이유
 * @param t         그래프 공통 시각 (초). 모든 {@link Chart}의 점이 이 시각에 맞춰 있다
 */
public record TrafficView(boolean available, String reason, long at, String range, int stepSec, Health health, Now now,
		long[] t, List<Chart> charts, List<Endpoint> endpoints, List<Long> restarts, Pipeline pipeline) {

	static TrafficView unavailable(String reason, long at, String range) {
		return new TrafficView(false, reason, at, range, 0, new Health("UNKNOWN", List.of()), null, new long[0], List.of(),
				List.of(), List.of(), null);
	}

	/** level: OK · WARN · CRIT · UNKNOWN. reasons는 심각한 것부터 */
	public record Health(String level, List<Reason> reasons) {
	}

	/** level: CRIT · WARN · INFO (INFO는 "트래픽 급증"처럼 장애는 아니지만 알아야 할 것) */
	public record Reason(String level, String metric, String text) {
	}

	/**
	 * 지금 값 (그래프의 마지막 점). baselineRps = 최근 24시간 초당 요청 중앙값(마지막 10분 제외), surge = rps / baselineRps.
	 * 비율(%)은 0~100. clientErrorPct = 409·429를 뺀 4xx 비율
	 */
	public record Now(Double rps, Double baselineRps, Double surge, Double peakRps, Double p50Ms, Double p95Ms, Double p99Ms,
			Double errorPct, Double rejectPct, Double clientErrorPct, Double conflictPerSec, Double reservePerSec, Double payPerSec,
			Double queueWaiting, Double queueActive, Double cpuPct, Double heapPct, Double dbPoolPct, Double dbPending,
			Double tomcatPct, Double redisMemPct, Double loginQueue, Double loginQueuePct, Double loginBusy5m,
			int instancesUp, int instancesTotal) {
	}

	/** unit: rps · ms · pct · people */
	public record Chart(String key, String title, String unit, List<Line> lines) {
	}

	/** v[i]는 {@link TrafficView#t}[i] 시각의 값 */
	public record Line(String name, Double[] v) {
	}

	/**
	 * 데이터 놓침·밀림 (admin/traffic/PipelineMetrics). 초당 값은 최근 30초, 건수는 최근 5분.
	 *
	 * @param queuePct 행동 로그 보내기 큐 사용률 (최대 5만 건 대비 %)
	 */
	public record Pipeline(Double behaviorPerSec, Double storedPerSec, Double droppedPerSec, Double failedPerSec, Double dropped5m,
			Double failed5m, Double skipped5m, Double stored5m,
			Double queuePct, Double outboxPending, Double outboxFailed, Double outboxOldestSec, List<Lag> lags, Boolean lagMeasured,
			Double deadLetters5m, Double deadLetters1h, List<DeadLetterCount> deadLetters) {
	}

	/** 격리 보관함(DLT)에 옮긴 메시지 수 (최근 1시간). group: 소비 그룹, reason: malformed · retries_exhausted · handler_failed */
	public record DeadLetterCount(String group, String reason, double count) {
	}

	/** Kafka 소비 그룹 하나의 밀림. level: OK · WARN · CRIT (값이 없으면 null) */
	public record Lag(String group, String name, Double lag, String level) {
	}

	/** 최근 1분 기준 요청 많은 순 */
	public record Endpoint(String method, String uri, String group, double rps, Double p95Ms, double errorPct,
			double clientErrorPct, double share) {
	}
}
