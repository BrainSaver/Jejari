package com.jejari.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jejari.reservation.CancelFeeCalculator.Quote;
import com.jejari.reservation.CancelFeeCalculator.Reason;
import com.jejari.reservation.GroupFeeCalculator.Band;

/** 단체 위약금 (concurrency.md §18.6). 구간은 ERD group_fee_policy 예시 행 그대로 */
class GroupFeeCalculatorTest {

	static final List<Band> KORAIL = List.of(
			new Band(1, 2880L, null, null, 400L), // 출발 2일 전까지 1인 400원
			new Band(2, 0L, 2880L, new BigDecimal("0.1"), null), // 1일 전 ~ 출발 전 10%
			new Band(3, -20L, 0L, new BigDecimal("0.15"), null), // 출발 후 20분까지 15%
			new Band(4, -60L, -20L, new BigDecimal("0.4"), null), // 출발 후 20~60분 40%
			new Band(5, null, -60L, new BigDecimal("0.7"), null)); // 출발 후 60분 ~ 도착 70%

	static final Instant DEP = Instant.parse("2027-02-04T00:00:00Z");
	static final Instant ARR = DEP.plus(Duration.ofHours(3));
	static final List<Long> SEATS = List.of(59800L, 59800L, 29900L); // 어른 2 + 어린이 1
	static final long PAID = 149500;

	static Quote at(Duration beforeDeparture) {
		return GroupFeeCalculator.quote(KORAIL, DEP, ARR, DEP.minus(beforeDeparture), SEATS, PAID);
	}

	@Test
	void 출발_2일_전까지는_1인_400원() {
		Quote q = at(Duration.ofDays(3));
		assertThat(q.cancelable()).isTrue();
		assertThat(q.fee()).isEqualTo(1200); // 3명 × 400
		assertThat(q.refund()).isEqualTo(PAID - 1200);
		assertThat(q.feeRate()).isNull(); // 비율이 아니라 고정 금액
		assertThat(q.policyId()).isEqualTo(1);
	}

	@Test
	void 딱_2일_전은_400원_1초_모자라면_10퍼센트() {
		assertThat(at(Duration.ofDays(2)).fee()).isEqualTo(1200);
		assertThat(at(Duration.ofDays(2).minusSeconds(1)).fee()).isEqualTo(14950); // 5980 + 5980 + 2990
	}

	@Test
	void 출발_직전은_10퍼센트_출발_뒤는_구간대로() {
		assertThat(at(Duration.ofSeconds(30)).fee()).isEqualTo(14950); // 출발 30초 전 → 0분 → 10%
		assertThat(at(Duration.ofMinutes(-10)).fee()).isEqualTo(22425); // 15%: 8970 + 8970 + 4485
		assertThat(at(Duration.ofMinutes(-20)).fee()).isEqualTo(22425); // 딱 20분까지 15%
		assertThat(at(Duration.ofMinutes(-21)).fee()).isEqualTo(59800); // 40%: 23920 + 23920 + 11960
		assertThat(at(Duration.ofMinutes(-60)).fee()).isEqualTo(59800); // 딱 60분까지 40%
		assertThat(at(Duration.ofMinutes(-61)).fee()).isEqualTo(104650); // 70%: 41860 + 41860 + 20930
	}

	@Test
	void 도착_뒤는_취소_불가() {
		Quote q = GroupFeeCalculator.quote(KORAIL, DEP, ARR, ARR, SEATS, PAID);
		assertThat(q.cancelable()).isFalse();
		assertThat(q.reason()).isEqualTo(Reason.AFTER_DEPARTURE_LIMIT);
	}

	@Test
	void 정책이_비거나_겹치면_취소_불가() {
		assertThat(GroupFeeCalculator.quote(List.of(), DEP, ARR, DEP.minus(Duration.ofDays(3)), SEATS, PAID).reason())
				.isEqualTo(Reason.FEE_POLICY_MISSING);
		List<Band> overlap = List.of(KORAIL.get(0), new Band(9, 0L, null, new BigDecimal("0.05"), null));
		assertThat(GroupFeeCalculator.quote(overlap, DEP, ARR, DEP.minus(Duration.ofDays(3)), SEATS, PAID).reason())
				.isEqualTo(Reason.FEE_POLICY_MISSING);
	}

	@Test
	void 고정_위약금은_좌석_금액을_넘지_않는다() {
		Quote q = GroupFeeCalculator.quote(KORAIL, DEP, ARR, DEP.minus(Duration.ofDays(3)), List.of(300L), 300);
		assertThat(q.fee()).isEqualTo(300);
		assertThat(q.refund()).isZero();
	}

	@Test
	void 출발_시각을_모르면_취소_불가() {
		assertThat(GroupFeeCalculator.quote(KORAIL, null, ARR, DEP, SEATS, PAID).reason())
				.isEqualTo(Reason.DEPARTURE_UNKNOWN);
	}
}
