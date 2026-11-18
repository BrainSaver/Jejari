package com.jejari.queue.redis;

/** Redis 키 이름 (DB설계서 6.2). 키의 {…}는 자리표시자일 뿐 실제 키에는 중괄호가 없다. */
public final class QueueKeys {
	private QueueKeys() {}

	public static String queue(long runId) { return "queue:" + runId; }
	public static String seq(long runId) { return "queue:seq:" + runId; }
	public static String member(long memberId) { return "queue:member:" + memberId; }
	public static String ci(String ciHash) { return "queue:ci:" + ciHash; }
	public static String active(long runId) { return "queue:active:" + runId; }
	public static String throughput(long runId) { return "queue:throughput:" + runId; }
	public static String pass(long runId, long memberId) { return "queue:pass:" + runId + ":" + memberId; }
	/** 입장 토큰을 이미 한 번 연장했다는 표시. 토큰과 같이 사라진다 */
	public static String passExt(long runId, long memberId) { return "queue:passext:" + runId + ":" + memberId; }

	/** risk:{subjectType}:{hash}. subjectType은 CI / PAYMENT / DEVICE */
	public static String risk(String subjectType, String hash) { return "risk:" + subjectType + ":" + hash; }

	/** 위험 점수 배치(D)가 Redis 전체 재동기화를 끝낼 때마다 갱신하는 표시 키. 없으면 risk:* 캐시를 믿지 않는다. */
	public static final String RISK_SYNCED_AT = "risk:meta:synced_at";

	public static final String POLICY_CACHE = "policy:cache";
}
