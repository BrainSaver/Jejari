package com.jejari.seat;

/**
 * 좌석·한도·멱등 Redis 키 (ERD redis_keys, concurrency.md §14.1). 단일 노드 전제라 해시 태그를 쓰지 않습니다.
 * 클러스터로 바꾸면 §14.4대로 키 이름을 바꿔야 Lua 다중 키가 동작합니다.
 */
public final class SeatKeys {

	private SeatKeys() {
	}

	/** 좌석 구간 점유. 값: R(HOLD) / SOLD:R / REOFFER:R */
	public static String hold(long runId, long seatId, int segmentNo) {
		return "seat:hold:" + runId + ":" + seatId + ":" + segmentNo;
	}

	/** 요청 멱등 키. 값: reservation_id */
	public static String idem(long memberId, String key) {
		return "idem:" + memberId + ":" + key;
	}

	public static String limitRun(long runId, String ciHash) {
		return "limit:" + runId + ":" + ciHash;
	}

	/** 구매일(yyyymmdd) 기준 1일 매수 (v11) */
	public static String limitDay(long yyyymmdd, String ciHash) {
		return "limit:day:" + yyyymmdd + ":" + ciHash;
	}

	public static String limitEvent(long saleEventId, String ciHash) {
		return "limit:event:" + saleEventId + ":" + ciHash;
	}

	/** 예약 하나의 한도 감소를 이미 했다는 표시 (limit_decr.lua) */
	public static String limitDec(long reservationId) {
		return "limit:dec:" + reservationId;
	}
}
