package com.jejari.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jejari.common.error.BusinessException;
import com.jejari.group.GroupSeatPicker.Seat;

/** 단체 좌석 고르기 (concurrency.md §18.3-1, §18.3a-1) */
class GroupSeatPickerTest {

	@Test
	void 한_객차에_다_들어가면_그_객차에서_붙여_고른다() {
		List<Seat> c = new ArrayList<>();
		c.addAll(car(1, 100, 20)); // 1호차 20석
		c.addAll(car(2, 200, 12)); // 2호차 12석

		List<Seat> got = GroupSeatPicker.pickBlock(c, 11);

		assertThat(got).hasSize(11).allMatch(s -> s.carNo() == 2); // 들어가는 객차 중 작은 덩어리 → 큰 덩어리를 남김
		assertThat(got.get(0).seatId()).isEqualTo(200);
		assertThat(got.get(10).seatId()).isEqualTo(210);
	}

	@Test
	void 한_객차로_모자라면_큰_객차부터_채운다() {
		List<Seat> c = new ArrayList<>();
		c.addAll(car(1, 100, 5));
		c.addAll(car(2, 200, 8));
		c.addAll(car(3, 300, 3));

		List<Seat> got = GroupSeatPicker.pickBlock(c, 12);

		assertThat(got).hasSize(12);
		assertThat(got.subList(0, 8)).allMatch(s -> s.carNo() == 2);
		assertThat(got.subList(8, 12)).allMatch(s -> s.carNo() == 1);
	}

	@Test
	void 후보가_모자라면_있는_만큼만() {
		assertThat(GroupSeatPicker.pickBlock(car(1, 100, 4), 11)).hasSize(4);
		assertThat(GroupSeatPicker.pickBlock(List.of(), 11)).isEmpty();
	}

	@Test
	void 일반_좌석은_할당석_바로_옆부터_그다음_가까운_객차() {
		List<Seat> anchor = car(3, 330, 5);       // 3호차 330~334가 할당석
		List<Seat> free = new ArrayList<>();
		free.addAll(car(1, 100, 10));
		free.addAll(car(3, 300, 30).stream().filter(s -> s.seatId() < 330).toList()); // 3호차 300~329
		free.addAll(car(4, 400, 10));

		List<Seat> got = GroupSeatPicker.pickNear(free, 6, anchor);

		assertThat(got).hasSize(6).allMatch(s -> s.carNo() == 3);
		assertThat(got.get(0).seatId()).isEqualTo(329); // 330 바로 옆
		assertThat(got.get(5).seatId()).isEqualTo(324);

		List<Seat> more = GroupSeatPicker.pickNear(free, 35, anchor);
		assertThat(more.subList(30, 35)).allMatch(s -> s.carNo() == 4); // 3호차 다음은 가까운 4호차 (1호차보다 먼저)
	}

	@Test
	void 할당석이_없으면_일반_좌석도_한_객차에_붙여서() {
		List<Seat> free = new ArrayList<>();
		free.addAll(car(1, 100, 5));
		free.addAll(car(2, 200, 40));

		assertThat(GroupSeatPicker.pickNear(free, 15, List.of())).hasSize(15).allMatch(s -> s.carNo() == 2);
	}

	@Test
	void 어른_할인은_원_단위_반올림() {
		assertThat(GroupAllocator.discounted(59_800, 0.10)).isEqualTo(53_820);
		assertThat(GroupAllocator.discounted(8_405, 0.10)).isEqualTo(7_564); // 840.5 → 841
		assertThat(GroupAllocator.discounted(59_800, 0)).isEqualTo(59_800);
	}

	@Test
	void 휴대폰_번호는_숫자만_남기고_한국_휴대폰_형식만_받는다() {
		assertThat(GroupApplyService.normalizePhone("010-1234-5678")).isEqualTo("01012345678");
		assertThat(GroupApplyService.normalizePhone("+82 10 1234 5678")).isEqualTo("01012345678");
		assertThatThrownBy(() -> GroupApplyService.normalizePhone("02-123-4567")).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> GroupApplyService.normalizePhone(null)).isInstanceOf(BusinessException.class);
	}

	private static List<Seat> car(int carNo, long firstSeatId, int count) {
		List<Seat> out = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			out.add(new Seat(firstSeatId + i, carNo));
		}
		return out;
	}
}
