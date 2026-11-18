package com.jejari.reservation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 확정 예약 취소 수수료 계산 (CNL-01 미리보기·CNL-02 취소·RSV-04 상세가 같은 계산을 쓴다). 상태가 없는 순수 계산이다.
 *
 * <pre>
 * 출발 전: 남은 분 m = 출발 시각 − 지금 (분 단위 버림), 정책 구간 [min, max) 중 m이 들어가는 행 (출발 후 행 제외)
 * 출발 후: after_departure = 'Y' 행. 출발 후 {@code afterDepartureLimitMin}분(app_policy cancel.after_departure_limit_min)이
 *          지났으면 취소 불가
 * 수수료 = Σ 좌석별 금액 × 비율 (좌석마다 원 단위 미만 버림), 환불액 = 결제 금액 − 수수료 (0 미만이면 0)
 * </pre>
 *
 * <p><b>정책이 비거나 어긋나면 취소 불가로 답한다</b> (fail-closed). 맞는 구간이 없거나 두 구간이 겹치면
 * 수수료 0원으로 넘어가지 않고 {@link Reason#FEE_POLICY_MISSING}을 돌려준다. 수수료를 잘못 0으로 계산하면
 * 매진 직후 사고 바로 취소하는 되팔이 비용이 사라진다.
 */
public final class CancelFeeCalculator {

	private CancelFeeCalculator() {
	}

	/** cancel_fee_policy 한 행. min·max는 출발 전 분, null이면 그쪽 한도 없음 */
	public record Band(long policyId, Long minMinutesBefore, Long maxMinutesBefore, BigDecimal feeRate,
			boolean afterDeparture) {
	}

	public enum Reason {
		/** 출발 후 취소 가능 시간이 지남 → CANCEL_NOT_ALLOWED */
		AFTER_DEPARTURE_LIMIT,
		/** 출발 시각을 모름 (정차 정보 없음) */
		DEPARTURE_UNKNOWN,
		/** 맞는 수수료 구간이 없거나 여러 개 */
		FEE_POLICY_MISSING
	}

	/**
	 * @param cancelable             취소 가능 여부
	 * @param reason                 불가 사유 (가능하면 null)
	 * @param minutesBeforeDeparture 출발까지 남은 분. 출발 후면 음수 (cancellation.minutes_before_departure)
	 * @param policyId               적용한 cancel_fee_policy (불가면 null)
	 * @param feeRate                수수료율 (불가면 null)
	 * @param fee                    수수료 (원)
	 * @param refund                 환불 예정액 (원)
	 */
	public record Quote(boolean cancelable, Reason reason, Long minutesBeforeDeparture, Long policyId,
			BigDecimal feeRate, long fee, long refund) {

		static Quote denied(Reason reason, Long minutesBefore) {
			return new Quote(false, reason, minutesBefore, null, null, 0, 0);
		}
	}

	/**
	 * @param bands                  이 운행에 적용할 정책 행 (행사 정책, 없으면 상시 정책)
	 * @param departure              승차역 출발 시각 (모르면 null)
	 * @param now                    지금
	 * @param seatAmounts            좌석별 금액
	 * @param paidAmount             결제 금액
	 * @param afterDepartureLimitMin 출발 후 취소 가능 분
	 */
	public static Quote quote(List<Band> bands, Instant departure, Instant now, List<Long> seatAmounts,
			long paidAmount, long afterDepartureLimitMin) {
		if (departure == null) {
			return Quote.denied(Reason.DEPARTURE_UNKNOWN, null);
		}
		long secondsBefore = Duration.between(now, departure).toSeconds();
		// 출발 전은 내림(10분 59초 → 10분), 출발 후는 음수로 올림(-0분 30초 → -1분): 경계에서 늘 더 불리한 쪽(덜 남은 쪽)
		long minutesBefore = Math.floorDiv(secondsBefore, 60);
		boolean departed = secondsBefore <= 0;
		if (departed && -secondsBefore > afterDepartureLimitMin * 60) {
			return Quote.denied(Reason.AFTER_DEPARTURE_LIMIT, minutesBefore);
		}

		List<Band> hits = new ArrayList<>();
		for (Band b : bands) {
			if (b.feeRate() == null || b.feeRate().signum() < 0 || b.feeRate().compareTo(BigDecimal.ONE) > 0) {
				continue; // 잘못된 비율 행은 쓰지 않는다 (남은 행으로 못 정하면 불가)
			}
			if (departed ? b.afterDeparture() : !b.afterDeparture() && inBand(b, minutesBefore)) {
				hits.add(b);
			}
		}
		if (hits.size() != 1) {
			return Quote.denied(Reason.FEE_POLICY_MISSING, minutesBefore);
		}
		Band band = hits.get(0);
		long fee = 0;
		for (long amount : seatAmounts) {
			fee += BigDecimal.valueOf(amount).multiply(band.feeRate()).setScale(0, RoundingMode.FLOOR).longValueExact();
		}
		long refund = Math.max(0, paidAmount - fee);
		return new Quote(true, null, minutesBefore, band.policyId(), band.feeRate(), fee, refund);
	}

	/** [min, max) 반열림. min이 null이면 하한 없음, max가 null이면 상한 없음 */
	private static boolean inBand(Band b, long m) {
		return (b.minMinutesBefore() == null || m >= b.minMinutesBefore())
				&& (b.maxMinutesBefore() == null || m < b.maxMinutesBefore());
	}
}
