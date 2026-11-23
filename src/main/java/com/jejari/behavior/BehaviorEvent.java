package com.jejari.behavior;

import java.util.Map;

/**
 * Kafka {@code jejari.behavior} 메시지 (JSON). 키는 회원 ID (비로그인이면 기기 해시, 없으면 IP 해시).
 * 같은 회원의 이벤트가 같은 파티션으로 가서 적재 컨슈머가 순서대로 받는다 (세션별 클릭 간격 계산).
 *
 * <p>ci_hash는 여기 넣지 않는다: 요청마다 DB를 읽어야 해서, 적재 컨슈머가 member_id로 찾아 붙인다 (캐시).
 * 원문 IP·기기 ID·이메일은 없다. 해시만 있다.
 *
 * @param ts             요청을 받은 시각 (ISO-8601, Asia/Seoul)
 * @param responseStatus HTTP 상태 (실패 요청도 남긴다)
 * @param elapsedMs      서버가 이 요청을 처리한 시간(ms, 받은 시각 → 응답을 다 보낸 시각). 예전 메시지는 null.
 *                       "응답을 받고 다음 요청까지" = 다음 ts − (ts + elapsedMs) → 사람이 보고 누르는 시간 (INHUMAN_STEP)
 */
public record BehaviorEvent(
		String eventId,
		String eventType,
		String ts,
		Long memberId,
		String sessionId,
		String deviceHash,
		String ipHash,
		Long runId,
		String channel,
		String userAgent,
		int responseStatus,
		Map<String, Object> meta,
		Long elapsedMs) {

	/** 처리 시간 없는 모양 (예전 메시지·시험) */
	public BehaviorEvent(String eventId, String eventType, String ts, Long memberId, String sessionId, String deviceHash,
			String ipHash, Long runId, String channel, String userAgent, int responseStatus, Map<String, Object> meta) {
		this(eventId, eventType, ts, memberId, sessionId, deviceHash, ipHash, runId, channel, userAgent, responseStatus,
				meta, null);
	}

	public static final String TOPIC = "jejari.behavior";

	/** 파티션 키 */
	public String key() {
		if (memberId != null) {
			return "m:" + memberId;
		}
		if (deviceHash != null) {
			return "d:" + deviceHash;
		}
		return "i:" + ipHash;
	}
}
