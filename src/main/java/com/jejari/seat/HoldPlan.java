package com.jejari.seat;

import java.time.LocalDateTime;
import java.util.List;

import com.jejari.seat.PurchaseLimits.Limits;
import com.jejari.train.RunInfo;

/**
 * Redis 단계를 통과한 뒤 DB 트랜잭션(§2.2)에 넘기는 값. 마감 시각은 앱 시계로 한 번만 계산해 넣습니다 (§0.7).
 *
 * @param seats       좌석 ID 오름차순 (seat_inventory INSERT 순서, §0.4-3)
 * @param purchaseDay 구매일 yyyymmdd (PER_DAY 카운터의 scope_ref_id, v11)
 * @param source      NORMAL = 본인이 고른 예약 / WAITLIST = 취소표 대기자에게 시스템이 대신 잡아 준 예약 (§9.1-6)
 */
record HoldPlan(
		long reservationId,
		long memberId,
		String ciHash,
		RunInfo run,
		String fromStationId,
		String toStationId,
		List<SeatLine> seats,
		long totalAmount,
		String idempotencyKey,
		String channel,
		LocalDateTime now,
		LocalDateTime deadline,
		long purchaseDay,
		Limits limits,
		String source) {

	static final String NORMAL = "NORMAL";
	static final String WAITLIST = "WAITLIST";

	/**
	 * 이력의 주체 (reservation_history.actor_type: USER / SYSTEM / ADMIN).
	 * 취소표 제안은 회원이 누른 것이 아니라 시스템이 대신 잡아 준 것이다 (§9.1-6).
	 */
	String actorType() {
		return WAITLIST.equals(source) ? "SYSTEM" : "USER";
	}

	/** 구간 번호. 구간 재고 OFF(inventory.segment_mode)라 구간은 하나뿐입니다 (§2.4, K = seat_count) */
	static final int SEGMENT_NO = 0;

	record SeatLine(long seatId, String passengerType, long amount) {
	}

	int seatCount() {
		return seats.size();
	}
}
