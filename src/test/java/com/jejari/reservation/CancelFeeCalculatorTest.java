package com.jejari.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jejari.reservation.CancelFeeCalculator.Band;
import com.jejari.reservation.CancelFeeCalculator.Quote;
import com.jejari.reservation.CancelFeeCalculator.Reason;

/** 수수료 구간 경계·버림·출발 후 한도·정책 이상 (기능설계서 CNL-01 예시 비율) */
class CancelFeeCalculatorTest {

	static final Instant DEP = Instant.parse("2026-11-20T00:00:00Z");
	/** 3일 전 0% · 1~3일 5% · 3시간~1일 10% · 3시간 이내 20% · 출발 후 40% */
	static final List<Band> BANDS = List.of(
			new Band(1, 4320L, null, new BigDecimal("0"), false),
			new Band(2, 1440L, 4320L, new BigDecimal("0.05"), false),
			new Band(3, 180L, 1440L, new BigDecimal("0.1"), false),
			new Band(4, 0L, 180L, new BigDecimal("0.2"), false),
			new Band(5, null, null, new BigDecimal("0.4"), true));

	static Quote at(Duration beforeDeparture, List<Long> seats, long paid) {
		return CancelFeeCalculator.quote(BANDS, DEP, DEP.minus(beforeDeparture), seats, paid, 20);
	}

	static Quote at(Duration beforeDeparture) {
		return at(beforeDeparture, List.of(59800L), 59800);
	}

	@Test
	void 구간_경계는_반열림이고_남은_분은_내림() {
		assertThat(at(Duration.ofMinutes(4320)).policyId()).isEqualTo(1L);          // 정확히 3일 전 → 0%
		assertThat(at(Duration.ofMinutes(4320).minusSeconds(1)).policyId()).isEqualTo(2L); // 1초 모자라면 5%
		assertThat(at(Duration.ofMinutes(1440)).policyId()).isEqualTo(2L);
		assertThat(at(Duration.ofMinutes(1439)).policyId()).isEqualTo(3L);
		assertThat(at(Duration.ofMinutes(180)).policyId()).isEqualTo(3L);
		assertThat(at(Duration.ofMinutes(179)).policyId()).isEqualTo(4L);
		assertThat(at(Duration.ofSeconds(1)).policyId()).isEqualTo(4L);
		assertThat(at(Duration.ofMinutes(10).plusSeconds(59)).minutesBeforeDeparture()).isEqualTo(10);
	}

	@Test
	void 수수료는_좌석마다_원_미만_버림_환불은_결제액에서_뺀다() {
		Quote q = at(Duration.ofHours(5), List.of(33333L, 33333L), 66666); // 10%
		assertThat(q.cancelable()).isTrue();
		assertThat(q.fee()).isEqualTo(3333 + 3333); // 3333.3 → 3333씩
		assertThat(q.refund()).isEqualTo(66666 - 6666);
		assertThat(q.feeRate()).isEqualByComparingTo("0.1");

		Quote free = at(Duration.ofDays(4));
		assertThat(free.fee()).isZero();
		assertThat(free.refund()).isEqualTo(59800);
	}

	@Test
	void 출발_순간부터는_출발_후_구간이고_20분이_지나면_취소_불가() {
		Quote atDeparture = at(Duration.ZERO);
		assertThat(atDeparture.policyId()).isEqualTo(5L);
		assertThat(atDeparture.fee()).isEqualTo(23920); // 40%

		Quote twentyMin = at(Duration.ofMinutes(-20));
		assertThat(twentyMin.cancelable()).isTrue();
		assertThat(twentyMin.minutesBeforeDeparture()).isEqualTo(-20);

		Quote late = at(Duration.ofMinutes(-20).minusSeconds(1));
		assertThat(late.cancelable()).isFalse();
		assertThat(late.reason()).isEqualTo(Reason.AFTER_DEPARTURE_LIMIT);

		assertThat(at(Duration.ofSeconds(-30)).minutesBeforeDeparture()).isEqualTo(-1); // 출발 후는 더 지난 쪽으로
	}

	@Test
	void 정책이_없거나_겹치거나_비율이_이상하면_0원이_아니라_취소_불가() {
		Instant now = DEP.minus(Duration.ofHours(5));
		assertThat(CancelFeeCalculator.quote(List.of(), DEP, now, List.of(1000L), 1000, 20).reason())
				.isEqualTo(Reason.FEE_POLICY_MISSING);

		List<Band> overlap = List.of(new Band(1, 0L, 600L, new BigDecimal("0.1"), false),
				new Band(2, 180L, 1440L, new BigDecimal("0"), false));
		Quote q = CancelFeeCalculator.quote(overlap, DEP, now, List.of(1000L), 1000, 20);
		assertThat(q.cancelable()).isFalse();
		assertThat(q.reason()).isEqualTo(Reason.FEE_POLICY_MISSING);

		List<Band> broken = List.of(new Band(1, 0L, null, new BigDecimal("1.5"), false));
		assertThat(CancelFeeCalculator.quote(broken, DEP, now, List.of(1000L), 1000, 20).cancelable()).isFalse();

		// 출발 후 구간 행이 없으면 출발 후 취소도 불가
		List<Band> noAfter = BANDS.subList(0, 4);
		assertThat(CancelFeeCalculator.quote(noAfter, DEP, DEP.plusSeconds(60), List.of(1000L), 1000, 20).reason())
				.isEqualTo(Reason.FEE_POLICY_MISSING);
	}

	@Test
	void 출발_시각을_모르면_취소_불가() {
		assertThat(CancelFeeCalculator.quote(BANDS, null, DEP, List.of(1000L), 1000, 20).reason())
				.isEqualTo(Reason.DEPARTURE_UNKNOWN);
	}
}
