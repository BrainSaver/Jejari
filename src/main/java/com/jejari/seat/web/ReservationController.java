package com.jejari.seat.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.behavior.BehaviorLog;
import com.jejari.behavior.BehaviorType;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.queue.domain.QueuePassService;
import com.jejari.seat.SeatHoldService;
import com.jejari.seat.SeatHoldService.HoldCommand;
import com.jejari.seat.SeatHoldService.HoldResult;
import com.jejari.seat.SeatHoldService.SeatRequest;
import com.jejari.reservation.ReservationCancelService;
import com.jejari.reservation.ReservationCancelService.CancelResponse;

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
	private final ReservationCancelService cancels;
	private final QueuePassService queuePass;

	public ReservationController(SeatHoldService seatHold, ReservationCancelService cancels, QueuePassService queuePass) {
		this.seatHold = seatHold;
		this.cancels = cancels;
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
		// 행동 로그: 실패(409 등)도 남아야 같은 좌석 반복 실패(SAME_SEAT_RETRY)를 잡는다. 예외 전에 먼저 표시
		Map<String, Object> meta = new LinkedHashMap<>();
		List<Long> seatIds = body.seats() == null ? List.of() : body.seats().stream().map(SeatRequest::seatId).toList();
		meta.put("seatIds", seatIds);
		meta.put("seatCount", seatIds.size());
		BehaviorLog.mark(BehaviorType.SEAT_HOLD, body.runId(), meta);
		try {
			queuePass.verify(body.runId(), memberId, queueToken);
			HoldResult result = seatHold.hold(memberId, new HoldCommand(body.runId(), body.fromStationId(),
					body.toStationId(), body.seats(), body.channel(), idempotencyKey));
			meta.put("reservationId", result.reservationId());
			BehaviorLog.mark(BehaviorType.SEAT_HOLD, body.runId(), meta);
			// 새로 잡았으면 201, 같은 멱등 키 재요청이면 기존 예약 상태를 200으로
			return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
		} catch (BusinessException e) {
			meta.put("errorCode", e.getErrorCode().code());
			BehaviorLog.mark(BehaviorType.SEAT_HOLD, body.runId(), meta);
			throw e;
		}
	}

	/**
	 * 취소 (기능설계서 7장: HOLD·CONFIRMED 공통). 상태에 따라 나누는 것은 {@link ReservationCancelService#cancel}(D 강다인)이 한다.
	 * <ul>
	 *   <li>RSV-05 결제 전 취소 (§7.4): HOLD만, 수수료 없음, 풀린 좌석은 대기자에게 먼저 (REOFFER).
	 *       결제 중(PAYMENT_PENDING)이면 409 PAYMENT_IN_PROGRESS.</li>
	 *   <li>CNL-02 확정 취소 (§8): 수수료를 서버가 다시 계산해 cancellation 기록, 좌석 반환, 커밋 뒤 PG 환불.</li>
	 * </ul>
	 */
	@PostMapping("/{reservationId}/cancel")
	public CancelResponse cancel(@AuthenticationPrincipal AuthPrincipal me, @PathVariable long reservationId) {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("reservationId", reservationId);
		try {
			CancelResponse r = cancels.cancel(me.memberId(), reservationId);
			meta.put("cancelKind", r.kind());
			meta.put("seatCount", r.seatCount());
			BehaviorLog.mark(BehaviorType.CANCEL, r.runId(), meta);
			return r;
		} catch (BusinessException e) {
			meta.put("errorCode", e.getErrorCode().code());
			BehaviorLog.mark(BehaviorType.CANCEL, null, meta);
			throw e;
		}
	}
}
