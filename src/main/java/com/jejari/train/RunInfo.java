package com.jejari.train;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 운행 하나를 예매에 필요한 만큼 읽은 값 (train_run + train + sale_event + train_stop).
 *
 * @param saleEventId  명절 행사 ID. 없으면 null (PER_HOLIDAY 미적용, concurrency.md §10.1)
 * @param departureAt  첫 정차역 출발 시각. 좌석 확정 키·한도 키 만료에 씀
 * @param eventEndDate 명절 행사 마지막 날 (sale_event.holiday_to). 행사가 없으면 null
 * @param stopOrder    정차역 → 정차 순서. 승하차역 검증과 구간 수 계산(§2.4)에 씀
 */
public record RunInfo(
		long runId,
		long trainId,
		String trainKind,
		String status,
		Long saleEventId,
		LocalDateTime saleCloseAt,
		LocalDate runDate,
		LocalDateTime departureAt,
		LocalDate eventEndDate,
		Map<String, Integer> stopOrder) {

	public static final String ON_SALE = "ON_SALE";

	/** 판매 중: status = ON_SALE이고 판매 마감 전 */
	public boolean onSale(LocalDateTime now) {
		return ON_SALE.equals(status) && (saleCloseAt == null || saleCloseAt.isAfter(now));
	}

	/** 승차역이 하차역보다 앞에 있는 정차역 쌍인가 */
	public boolean validSegment(String fromStationId, String toStationId) {
		Integer from = stopOrder.get(fromStationId);
		Integer to = stopOrder.get(toStationId);
		return from != null && to != null && from < to;
	}
}
