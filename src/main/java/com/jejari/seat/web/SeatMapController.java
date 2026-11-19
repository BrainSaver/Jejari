package com.jejari.seat.web;

import java.util.ArrayList;
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
import com.jejari.seat.SeatMapService;
import com.jejari.seat.SeatMapService.State;
import com.jejari.train.RunInfo;
import com.jejari.train.TrainLayoutReader;
import com.jejari.train.TrainLayoutReader.Car;
import com.jejari.train.web.TrainController;
import com.jejari.train.TrainLayoutReader.SeatInfo;
import com.jejari.train.TrainRunReader;

/**
 * 좌석 배치도 (RSV-01). GET /api/v1/runs/{runId}/seats?from=&to=&grade=
 *
 * <p>로그인과 입장 토큰(X-Queue-Token)이 필요합니다. 토큰 검사는 대기열의 {@code QueueSeatTokenInterceptor}가
 * 이 경로({@code /api/v1/runs/*}{@code /seats})에서 컨트롤러 앞에 합니다.
 * 상태는 짧은 캐시라 조금 늦을 수 있고, 최종 판정은 선점(POST /reservations) 때 합니다.
 */
@RestController
@RequestMapping("/api/v1/runs/{runId}/seats")
public class SeatMapController {

	public record SeatView(long seatId, String seatNo, boolean window, State state) {
	}

	public record CarView(int carNo, String carTypeName, String grade, String seatColumns, int available,
			List<SeatView> seats) {
	}

	public record SeatMapView(long runId, String fromStationId, String toStationId, int available, List<CarView> cars) {
	}

	private final TrainRunReader runs;
	private final TrainLayoutReader layouts;
	private final SeatMapService seatMap;

	public SeatMapController(TrainRunReader runs, TrainLayoutReader layouts, SeatMapService seatMap) {
		this.runs = runs;
		this.layouts = layouts;
		this.seatMap = seatMap;
	}

	@GetMapping
	public SeatMapView seats(@PathVariable long runId, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(required = false) String grade,
			@RequestHeader(name = TrainController.AUTO_HEADER, required = false) String auto) {
		Map<String, Object> meta = new LinkedHashMap<>();
		if ("1".equals(auto)) {
			meta.put("auto", true); // 화면이 스스로 다시 부른 조회 (좌석을 뺏긴 직후). 위험 점수 특징에서 뺀다
		}
		if (grade != null) {
			meta.put("grade", grade);
		}
		BehaviorLog.mark(BehaviorType.VIEW_SEAT, runId, meta); // 실패해도 남게 먼저 (강다인 요청)

		RunInfo run = runs.find(runId).orElseThrow(() -> fail(meta, runId, CommonErrorCode.NOT_FOUND));
		if ((from != null || to != null) && !run.validSegment(from, to)) {
			throw fail(meta, runId, CommonErrorCode.INVALID_REQUEST);
		}
		Map<Long, State> states = seatMap.states(runId, run.trainId());
		List<CarView> cars = new ArrayList<>();
		int total = 0;
		for (Car c : layouts.layout(run.trainId()).cars()) {
			if (grade != null && !grade.equals(c.grade())) {
				continue;
			}
			List<SeatView> seats = new ArrayList<>();
			int available = 0;
			for (SeatInfo s : c.seats()) {
				State st = states.getOrDefault(s.seatId(), State.EMPTY);
				if (st == State.EMPTY) {
					available++;
				}
				seats.add(new SeatView(s.seatId(), s.seatNo(), s.window(), st));
			}
			total += available;
			cars.add(new CarView(c.carNo(), c.carTypeName(), c.grade(), c.seatColumns(), available, seats));
		}
		meta.put("availableCount", total);
		BehaviorLog.mark(BehaviorType.VIEW_SEAT, runId, meta);
		return new SeatMapView(runId, from, to, total, cars);
	}

	private static BusinessException fail(Map<String, Object> meta, long runId, CommonErrorCode code) {
		meta.put("errorCode", code.code());
		BehaviorLog.mark(BehaviorType.VIEW_SEAT, runId, meta);
		return new BusinessException(code);
	}
}
