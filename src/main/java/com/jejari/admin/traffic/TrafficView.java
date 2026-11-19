package com.jejari.admin.traffic;

import java.util.List;

/**
 * A-10 트래픽 관제 응답. 값을 못 구한 칸은 null (그 지표가 아직 수집되지 않았거나 Prometheus가 비어 있음).
 *
 * @param available Prometheus를 읽었나. false면 나머지는 비어 있고 {@code reason}에 이유
 * @param t         그래프 공통 시각 (초). 모든 {@link Chart}의 점이 이 시각에 맞춰 있다
 */
public record TrafficView(boolean available, String reason, long at, String range, int stepSec, Health health, Now now,
		long[] t, List<Chart> charts, List<Endpoint> endpoints) {

	static TrafficView unavailable(String reason, long at, String range) {
		return new TrafficView(false, reason, at, range, 0, new Health("UNKNOWN", List.of()), null, new long[0], List.of(),
				List.of());
	}

	/** level: OK · WARN · CRIT · UNKNOWN. reasons는 심각한 것부터 */
	public record Health(String level, List<Reason> reasons) {
	}

	/** level: CRIT · WARN · INFO (INFO는 "트래픽 급증"처럼 장애는 아니지만 알아야 할 것) */
	public record Reason(String level, String metric, String text) {
	}

	/**
	 * 지금 값 (그래프의 마지막 점). baselineRps = 직전 1시간 평균(마지막 10분 제외), surge = rps / baselineRps.
	 * 비율(%)은 0~100.
	 */
	public record Now(Double rps, Double baselineRps, Double surge, Double peakRps, Double p50Ms, Double p95Ms, Double p99Ms,
			Double errorPct, Double rejectPct, Double conflictPerSec, Double reservePerSec, Double payPerSec,
			Double queueWaiting, Double queueActive, Double cpuPct, Double heapPct, Double dbPoolPct, Double dbPending,
			Double tomcatPct, Double redisMemPct, int instancesUp, int instancesTotal) {
	}

	/** unit: rps · ms · pct · people */
	public record Chart(String key, String title, String unit, List<Line> lines) {
	}

	/** v[i]는 {@link TrafficView#t}[i] 시각의 값 */
	public record Line(String name, Double[] v) {
	}

	/** 최근 1분 기준 요청 많은 순 */
	public record Endpoint(String method, String uri, String group, double rps, Double p95Ms, double errorPct, double share) {
	}
}
