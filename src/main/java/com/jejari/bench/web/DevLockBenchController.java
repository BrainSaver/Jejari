package com.jejari.bench.web;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.bench.BenchTables;
import com.jejari.bench.LockBenchService;
import com.jejari.bench.LockBenchService.Attempt;
import com.jejari.bench.LockBenchService.Strategy;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;

/**
 * §17.1 락 비교 측정용 API. k6가 때린다 (`bench/k6/lock-compare.js`).
 *
 * <p>개발 모드에서만 존재하고 인증이 없다 ({@code DevSecurityConfig}가 {@code /api/v1/dev/**}를 열어 준다).
 * 실험 테이블만 건드리므로 실제 예약 데이터에는 영향이 없다.
 *
 * <ul>
 *   <li>{@code POST /api/v1/dev/bench/reset} — 한 회차 시작 상태로 되돌린다 (좌석 목록을 받는다)</li>
 *   <li>{@code POST /api/v1/dev/bench/hold} — 한 번의 선점 시도. k6의 요청 하나가 이것</li>
 *   <li>{@code GET  /api/v1/dev/bench/verify} — 중복 예약 건수와 선점된 좌석 수</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/dev/bench")
@ConditionalOnProperty(name = "jejari.dev-mode", havingValue = "true")
public class DevLockBenchController {

	/** 실험 좌석 ID 상한. 실수로 거대한 목록을 받아 Oracle을 채우지 않게 */
	private static final int MAX_SEATS = 2_000;

	/** 측정용 예약 ID. 실제 reservation_seq를 쓰지 않는다 (시퀀스를 측정으로 소모하지 않으려고) */
	private final AtomicLong nextReservationId = new AtomicLong(1);

	private final LockBenchService bench;
	private final BenchTables tables;
	private final StringRedisTemplate redis;

	public DevLockBenchController(LockBenchService bench, BenchTables tables, StringRedisTemplate redis) {
		this.bench = bench;
		this.tables = tables;
		this.redis = redis;
	}

	public record ResetRequest(Long runId, List<Long> seatIds) {
	}

	@PostMapping("/reset")
	public Map<String, Object> reset(@RequestBody ResetRequest req) {
		long runId = req.runId() == null ? 9001L : req.runId();
		List<Long> seats = req.seatIds();
		if (seats == null || seats.isEmpty() || seats.size() > MAX_SEATS) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
					"seatIds는 1~" + MAX_SEATS + "개여야 합니다.");
		}
		tables.reset(runId, seats);
		// C안이 쓰는 좌석 키만 지운다. flushdb는 다른 사람이 쓰는 대기열·정책 캐시까지 날린다
		for (long s : seats) {
			redis.delete(com.jejari.seat.SeatKeys.hold(runId, s, 0));
		}
		nextReservationId.set(1);
		return Map.of("runId", runId, "seats", seats.size());
	}

	public record HoldRequest(Long runId, Long seatId, String strategy) {
	}

	@PostMapping("/hold")
	public Attempt hold(@RequestBody HoldRequest req) {
		if (req.seatId() == null || req.strategy() == null) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "seatId와 strategy가 필요합니다.");
		}
		Strategy strategy;
		try {
			strategy = Strategy.valueOf(req.strategy().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
					"strategy는 PESSIMISTIC · OPTIMISTIC · REDIS 중 하나여야 합니다.");
		}
		long runId = req.runId() == null ? 9001L : req.runId();
		return bench.attempt(strategy, runId, req.seatId(), nextReservationId.getAndIncrement());
	}

	@GetMapping("/verify")
	public Map<String, Object> verify(@RequestParam(defaultValue = "9001") long runId) {
		// 러너가 "개발 모드인가 · 실험 테이블을 쓸 수 있는가"를 이 호출로 확인하므로 여기서 만든다
		tables.ensure();
		BenchTables.Duplicates dup = tables.duplicates(runId);
		return Map.of(
				"runId", runId,
				"duplicates", dup.total(),
				"duplicatesSeatInventory", dup.seatInventory(),
				"duplicatesSeat", dup.seat(),
				"heldSeats", tables.heldSeats(runId));
	}
}
