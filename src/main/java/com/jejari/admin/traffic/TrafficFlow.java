package com.jejari.admin.traffic;

import java.util.List;

/**
 * A-10 상단 "실시간 흐름도" 응답 (2초마다). 최근 30초 평균 초당 값.
 * 화면은 이 숫자만큼 점(요청)을 사용자 → 업무 영역 → 앱 → 저장소로 흘려 보낸다.
 *
 * @param groups 업무 영역별 요청 (순서 = {@link TrafficService#GROUPS})
 * @param stores 앱이 저장소로 보내는 초당 작업 (Redis 명령·Oracle 커넥션 사용·MongoDB 명령·Kafka 발행)
 */
public record TrafficFlow(boolean available, String reason, long at, List<Group> groups, List<Store> stores,
		Double kafkaConsumed, Double queueWaiting, Double queueActive, int instancesUp, int instancesTotal) {

	static TrafficFlow unavailable(String reason, long at) {
		return new TrafficFlow(false, reason, at, List.of(), List.of(), null, null, null, 0, 0);
	}

	/** rps 전체 중 err5 = 5xx, rejected = 429, conflict = 409, err4 = 그 밖의 4xx (모두 초당). avgMs = 평균 응답 시간 */
	public record Group(String name, double rps, double err5, double rejected, double conflict, double err4, Double avgMs) {
	}

	/** key: redis · oracle · mongo · kafka. ops = 초당 작업 수 (값 없으면 null — 그 지표가 수집되지 않음) */
	public record Store(String key, String name, Double ops) {
	}
}
