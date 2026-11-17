package com.jejari.admin;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.seat.SeatMapService;
import com.jejari.seat.SeatMapService.State;
import com.jejari.train.RunInfo;
import com.jejari.train.TrainRunReader;

/**
 * ADM-03 실시간 예매 현황. 운행 하나의 숫자를 모아 준다 (기능설계서 4.10, 화면 A-04).
 *
 * <p>좌석 수는 {@link SeatMapService}를 다시 쓴다. 좌석 상태의 원천은 Redis {@code seat:hold} 키라
 * DB를 다시 세는 것보다 정확하고(진 요청은 DB에 쓰지 않는다) {@code seatmap:{runId}} 캐시가 이미
 * 걸려 있어 10초 폴링이 Oracle을 두드리지 않는다. 대신 최대 {@code seatmap.cache_ms}만큼 늦다 —
 * 현황 화면은 그 정도 지연이 문제가 되지 않는다.
 *
 * <p>예약·결제·취소 건수만 Oracle에서 읽는다. 이건 캐시하지 않는다. 현황 화면이 보는 이유가
 * "지금 몇 건 들어오는가"라서, 캐시된 값을 보여 주면 폴링하는 의미가 없다.
 */
@Service
public class DashboardService {

	/** 분당 수치를 세는 창. 기능설계서가 "분당"으로 못박은 값이라 정책으로 빼지 않았다 */
	static final int WINDOW_SEC = 60;

	private final TrainRunReader runs;
	private final SeatMapService seatMap;
	private final ReservationStatsReader stats;
	private final QueueStatsReader queue;
	private final Clock clock;

	public DashboardService(TrainRunReader runs, SeatMapService seatMap, ReservationStatsReader stats,
			QueueStatsReader queue, Clock clock) {
		this.runs = runs;
		this.seatMap = seatMap;
		this.stats = stats;
		this.queue = queue;
		this.clock = clock;
	}

	public RunDashboard of(long runId) {
		RunInfo run = runs.find(runId)
				.orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND, "운행을 찾을 수 없습니다."));
		return new RunDashboard(runId, run.status(), LocalDateTime.now(clock), WINDOW_SEC,
				seats(runId, run.trainId()),
				stats.rates(runId, WINDOW_SEC),
				queue.read(runId),
				new RunDashboard.Payments(stats.unknownPayments()));
	}

	private RunDashboard.Seats seats(long runId, long trainId) {
		Map<Long, State> states = seatMap.states(runId, trainId);
		int sold = 0;
		int held = 0;
		for (State s : states.values()) {
			if (s == State.SOLD) {
				sold++;
			} else if (s == State.HELD) {
				held++;
			}
		}
		int total = states.size();
		return new RunDashboard.Seats(total, sold, held, total - sold - held);
	}
}
