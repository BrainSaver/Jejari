package com.jejari.replay;

import java.util.List;

/** Kafka {@code jejari.replay} 메시지. 키 = session_id (한 세션의 묶음은 한 파티션에서 순서대로) */
public record ReplayMessage(String sessionId, long memberId, String deviceHash, String ipHash, String mode,
		long receivedAt, List<ReplayBatch.Event> events) {

	public static final String TOPIC = "jejari.replay";
}
