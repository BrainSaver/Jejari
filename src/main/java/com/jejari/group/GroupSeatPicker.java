package com.jejari.group;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 단체 좌석 고르기 (concurrency.md §18.3-1, §18.3a-1). DB·Redis를 보지 않는 순수 계산이라 단위 테스트로 확인합니다.
 *
 * <ul>
 * <li><b>할당석</b>: 한 객차에 다 들어가면 그 객차에서 (들어가는 객차 중 가장 작은 덩어리를 써서 큰 덩어리를 남김).
 *     안 들어가면 할당석이 많은 객차부터 채웁니다. 객차 안에서는 좌석 번호 순서대로 붙여서.</li>
 * <li><b>일반 좌석</b>(할당석이 모자랄 때): 할당석을 받은 객차의 바로 옆 좌석부터, 그다음 가까운 객차 순.</li>
 * </ul>
 */
final class GroupSeatPicker {

	/** 후보 좌석 (seat.seat_id, seat.car_no) */
	record Seat(long seatId, int carNo) {
	}

	private GroupSeatPicker() {
	}

	/** 같은 객차에 붙여 n석. 후보가 모자라면 있는 만큼만 돌려준다 */
	static List<Seat> pickBlock(List<Seat> candidates, int n) {
		if (n <= 0 || candidates.isEmpty()) {
			return List.of();
		}
		Map<Integer, List<Seat>> byCar = byCar(candidates);
		// 1. 한 객차에 다 들어가면 그 객차
		Integer fit = null;
		for (var e : byCar.entrySet()) {
			if (e.getValue().size() >= n && (fit == null || e.getValue().size() < byCar.get(fit).size())) {
				fit = e.getKey();
			}
		}
		if (fit != null) {
			return List.copyOf(byCar.get(fit).subList(0, n));
		}
		// 2. 안 들어가면 큰 객차부터
		List<List<Seat>> cars = new ArrayList<>(byCar.values());
		cars.sort(Comparator.<List<Seat>>comparingInt(List::size).reversed()
				.thenComparingInt(l -> l.get(0).carNo()));
		List<Seat> out = new ArrayList<>();
		for (List<Seat> car : cars) {
			for (Seat s : car) {
				if (out.size() == n) {
					return out;
				}
				out.add(s);
			}
		}
		return out;
	}

	/**
	 * 할당석({@code anchor}) 옆으로 일반 좌석 m석. 할당석이 없으면 {@link #pickBlock}과 같다.
	 * 후보에 할당석 자체는 들어 있지 않아야 한다.
	 */
	static List<Seat> pickNear(List<Seat> candidates, int m, List<Seat> anchor) {
		if (m <= 0 || candidates.isEmpty()) {
			return List.of();
		}
		if (anchor.isEmpty()) {
			return pickBlock(candidates, m);
		}
		Map<Integer, List<Seat>> anchorByCar = byCar(anchor);
		int mainCar = anchorByCar.entrySet().stream()
				.max(Comparator.<Map.Entry<Integer, List<Seat>>>comparingInt(e -> e.getValue().size())
						.thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
				.orElseThrow().getKey();
		List<Seat> sorted = new ArrayList<>(candidates);
		sorted.sort(Comparator.<Seat>comparingInt(s -> anchorByCar.containsKey(s.carNo()) ? 0 : 1)
				.thenComparingInt(s -> anchorByCar.containsKey(s.carNo()) ? 0 : Math.abs(s.carNo() - mainCar))
				.thenComparingInt(Seat::carNo)
				.thenComparingLong(s -> distance(s, anchorByCar.get(s.carNo())))
				.thenComparingLong(Seat::seatId));
		return List.copyOf(sorted.subList(0, Math.min(m, sorted.size())));
	}

	/** 같은 객차 할당석 중 가장 가까운 좌석과의 번호 차이. 다른 객차면 좌석 번호 순서 그대로 */
	private static long distance(Seat s, List<Seat> sameCarAnchor) {
		if (sameCarAnchor == null) {
			return s.seatId();
		}
		long best = Long.MAX_VALUE;
		for (Seat a : sameCarAnchor) {
			best = Math.min(best, Math.abs(a.seatId() - s.seatId()));
		}
		return best;
	}

	/** 객차 번호 순, 객차 안은 좌석 번호 순 */
	private static Map<Integer, List<Seat>> byCar(List<Seat> seats) {
		Map<Integer, List<Seat>> m = new TreeMap<>();
		for (Seat s : seats) {
			m.computeIfAbsent(s.carNo(), k -> new ArrayList<>()).add(s);
		}
		Map<Integer, List<Seat>> out = new LinkedHashMap<>();
		m.forEach((car, list) -> {
			list.sort(Comparator.comparingLong(Seat::seatId));
			out.put(car, list);
		});
		return out;
	}
}
