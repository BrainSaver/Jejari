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
		Limits limits) {

	/** 구간 번호. 구간 재고 OFF(inventory.segment_mode)라 구간은 하나뿐입니다 (§2.4, K = seat_count) */
	static final int SEGMENT_NO = 0;

	record SeatLine(long seatId, String passengerType, long amount) {
	}

	int seatCount() {
		return seats.size();
	}
}
