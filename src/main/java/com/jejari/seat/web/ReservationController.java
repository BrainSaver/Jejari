package com.jejari.seat.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.queue.domain.QueuePassService;
import com.jejari.seat.SeatHoldService;
import com.jejari.seat.SeatHoldService.HoldCommand;
import com.jejari.seat.SeatHoldService.HoldResult;
import com.jejari.seat.SeatHoldService.SeatRequest;

/**
 * 좌석 선점 API (RSV-02). 기능설계서 7장: POST /api/v1/reservations, 회원 + 입장 토큰 + 멱등 키.
 *
 * <p>운행 ID가 경로가 아니라 본문에 있어서 대기열의 토큰 인터셉터로는 막을 수 없습니다. 그래서 여기서
 * {@link QueuePassService#verify}를 직접 부릅니다 (김성원 팀 확인 요청 3번). 이 호출이 빠지면
 * "토큰 없이 좌석 API 호출 차단율 100%"가 깨집니다.
 */
@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

	public record HoldRequest(Long runId, String fromStationId, String toStationId, List<SeatRequest> seats, String channel) {
	}

	private final SeatHoldService seatHold;
	private final QueuePassService queuePass;

	public ReservationController(SeatHoldService seatHold, QueuePassService queuePass) {
		this.seatHold = seatHold;
		this.queuePass = queuePass;
	}

	@PostMapping
	public ResponseEntity<HoldResult> hold(@AuthenticationPrincipal AuthPrincipal me,
			@RequestHeader(value = "X-Queue-Token", required = false) String queueToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody HoldRequest body) {
		long memberId = me.memberId();
		if (body == null || body.runId() == null) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		queuePass.verify(body.runId(), memberId, queueToken);
		HoldResult result = seatHold.hold(memberId, new HoldCommand(body.runId(), body.fromStationId(),
				body.toStationId(), body.seats(), body.channel(), idempotencyKey));
		// 새로 잡았으면 201, 같은 멱등 키 재요청이면 기존 예약 상태를 200으로
		return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
	}
}
