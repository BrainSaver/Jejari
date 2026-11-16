package com.jejari.queue.port;

import java.time.Instant;

/**
 * queue_log(MongoDB) 적재 자리. 실제 구현은 Kafka로 보낸다 (D·C와 토픽 합의 필요).
 * 순번 조정 정확도(initialRank → adjustedRank)와 중복 대기 차단 건수를 사후 검증하는 근거다.
 */
public interface QueueLogSink {

	void record(QueueLogEvent event);

	/** leftReason: ADMITTED / TIMEOUT / LEFT / REJECTED_DUPLICATE (ENTERED는 null) */
	record QueueLogEvent(Type type, long memberId, long runId, Long ticketNo, Instant at,
						 Double riskScore, Long penaltyMs, Long initialRank, Long adjustedRank,
						 String leftReason) {
		public enum Type { ENTERED, ADMITTED, LEFT, REJECTED_DUPLICATE }
	}
}
