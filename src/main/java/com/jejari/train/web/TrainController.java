package com.jejari.train.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.behavior.BehaviorLog;
import com.jejari.behavior.BehaviorType;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.train.TrainQueryService;
import com.jejari.train.TrainQueryService.RunDetail;
import com.jejari.train.TrainQueryService.RunSummary;
import com.jejari.train.TrainQueryService.Station;

/** 열차 조회 API (TRN-01~03). 비회원도 부를 수 있습니다 (SecurityConfig에서 GET만 열어 둠). */
@RestController
@RequestMapping("/api/v1")
public class TrainController {

	private final TrainQueryService trains;

	public TrainController(TrainQueryService trains) {
		this.trains = trains;
	}

	@GetMapping("/stations")
	public List<Station> stations() {
		return trains.stations();
	}

	/** GET /api/v1/runs?from=3900023&to=3900114&date=2026-11-19 */
	@GetMapping("/runs")
	public List<RunSummary> search(@RequestParam String from, @RequestParam String to, @RequestParam String date,
			@RequestHeader(name = AUTO_HEADER, required = false) String auto) {
		boolean isAuto = "1".equals(auto);
		LocalDate day;
		try {
			day = LocalDate.parse(date);
		} catch (DateTimeParseException e) {
			BehaviorLog.mark(BehaviorType.VIEW_TRAIN, null, Map.of("fromStationId", from, "toStationId", to,
					"errorCode", CommonErrorCode.INVALID_REQUEST.code()));
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "날짜는 2026-11-19 형식이어야 합니다.");
		}
		// 행동 로그는 예외 전에 먼저 남긴다 (실패 요청도 보내야 함, 강다인 요청)
		BehaviorLog.mark(BehaviorType.VIEW_TRAIN, null, viewMeta(from, to, day, null, isAuto));
		List<RunSummary> result = trains.search(from, to, day);
		BehaviorLog.mark(BehaviorType.VIEW_TRAIN, null, viewMeta(from, to, day, result.size(), isAuto));
		return result;
	}

	/**
	 * 화면이 사람 조작 없이 스스로 부른 검색(첫 화면 자동 검색)이라는 표시. 값 "1".
	 * 행동 로그 meta에 auto=true로 남긴다.
	 */
	public static final String AUTO_HEADER = "X-Search-Auto";

	private static Map<String, Object> viewMeta(String from, String to, LocalDate day, Integer resultCount, boolean auto) {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("fromStationId", from);
		meta.put("toStationId", to);
		meta.put("travelDate", day.toString());
		if (resultCount != null) meta.put("resultCount", resultCount);
		if (auto) meta.put("auto", true);
		return meta;
	}

	@GetMapping("/runs/{runId}")
	public RunDetail detail(@PathVariable long runId, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to) {
		return trains.detail(runId, from, to);
	}
}
