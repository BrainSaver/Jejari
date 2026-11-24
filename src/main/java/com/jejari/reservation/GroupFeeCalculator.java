package com.jejari.reservation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.jejari.reservation.CancelFeeCalculator.Quote;
import com.jejari.reservation.CancelFeeCalculator.Reason;

/**
 * 승인된 단체 예약(reservation.source = 'GROUP')의 위약금 계산 (concurrency.md §18.6). 상태가 없는 순수 계산이다.
 * 개인 취소 수수료({@link CancelFeeCalculator}, cancel_fee_policy)와 표·규칙이 달라 따로 둔다.
 *
 * <pre>
 * 남은 분 m = 출발 시각 − 지금 (개인과 같게 내림, 출발 후는 음수)
 * group_fee_policy 구간 [min, max) 중 m이 들어가는 행 하나 (출발 전·후를 한 표에서 음수로 나눔)
 * 좌석(= 탑승자 1명)마다: fixed_amount가 있으면 그 금액, 아니면 좌석 금액 × fee_rate (원 미만 버림). 좌석 금액을 넘지 않음
 * 도착 시각이 지나면 취소 불가 (마지막 구간이 "출발 후 60분 ~ 도착")
 * </pre>
 *
 * <p>개인과 같이 <b>정책이 비거나 겹치면 취소 불가</b>(fail-closed). 위약금을 0으로 넘기면 단체로 좌석을 묶었다가
 * 출발 직전에 공짜로 푸는 길이 생긴다.
 */
public final class GroupFeeCalculator {

	private GroupFeeCalculator() {
	}

	/**
	 * group_fee_policy 한 행. min·max는 출발 전 분(음수 = 출발 후), null이면 그쪽 한도 없음.
	 * feeRate와 fixedAmount 중 하나만 쓴다 (fixedAmount 우선)
	 */
	public record Band(long policyId, Long minMinutesBefore, Long maxMinutesBefore, BigDecimal feeRate,
			Long fixedAmount) {
	}

	/**
	 * @param departure  승차역 출발 시각 (모르면 null)
	 * @param arrival    하차역 도착 시각 (모르면 null = 도착 한도 없이 표만 봄)
	 * @param seatAmounts 좌석별 금액 (단체는 좌석 하나 = 탑승자 한 명)
	 * @return policyId는 group_fee_policy의 ID (cancellation.fee_policy_id는 cancel_fee_policy FK라 넣지 않는다)
	 */
	public static Quote quote(List<Band> bands, Instant departure, Instant arrival, Instant now, List<Long> seatAmounts,
			long paidAmount) {
		if (departure == null) {
			return Quote.denied(Reason.DEPARTURE_UNKNOWN, null);
		}
		long minutesBefore = Math.floorDiv(Duration.between(now, departure).toSeconds(), 60);
		if (arrival != null && !now.isBefore(arrival)) {
			return Quote.denied(Reason.AFTER_DEPARTURE_LIMIT, minutesBefore);
		}
		List<Band> hits = new ArrayList<>();
		for (Band b : bands) {
			boolean fixed = b.fixedAmount() != null;
			if (fixed ? b.fixedAmount() < 0
					: b.feeRate() == null || b.feeRate().signum() < 0 || b.feeRate().compareTo(BigDecimal.ONE) > 0) {
				continue; // 잘못된 행은 쓰지 않는다 (남은 행으로 못 정하면 불가)
			}
			if ((b.minMinutesBefore() == null || minutesBefore >= b.minMinutesBefore())
					&& (b.maxMinutesBefore() == null || minutesBefore < b.maxMinutesBefore())) {
				hits.add(b);
			}
		}
		if (hits.size() != 1) {
			return Quote.denied(Reason.FEE_POLICY_MISSING, minutesBefore);
		}
		Band band = hits.get(0);
		long fee = 0;
		for (long amount : seatAmounts) {
			long one = band.fixedAmount() != null ? band.fixedAmount()
					: BigDecimal.valueOf(amount).multiply(band.feeRate()).setScale(0, RoundingMode.FLOOR).longValueExact();
			fee += Math.min(Math.max(0, amount), one);
		}
		long refund = Math.max(0, paidAmount - fee);
		return new Quote(true, null, minutesBefore, band.policyId(), band.fixedAmount() != null ? null : band.feeRate(),
				fee, refund);
	}
}
