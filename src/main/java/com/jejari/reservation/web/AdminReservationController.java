package com.jejari.reservation.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.reservation.ReservationCancelService;
import com.jejari.reservation.ReservationCancelService.AdminCancelResponse;

/**
 * ADM-07 예약 강제 취소 (A-04). SUPERVISOR만.
 *
 * <pre>
 * POST /api/v1/admin/reservations/{id}/cancel   {"memo":"되팔이 확정 케이스 C-204 조치"}
 *   200 {"cancel":{"status":"CANCELED","cancelFee":0,"refundAmount":전액,"refundStatus":"DONE",…},"actionId":…}
 *   400 ACTION_REASON_REQUIRED (사유 5자 미만) · 403 ADMIN_FORBIDDEN · 404 RESERVATION_NOT_FOUND
 *   409 RESERVATION_NOT_CONFIRMED (확정 예약이 아님, 이미 취소됨 → 두 번 눌러도 한 번만)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/reservations")
public class AdminReservationController {

	/** @param waiveFee 위약금 면제 (없으면 true = 면제). 매크로로 판정해 위약금을 받을 때만 false */
	public record CancelRequest(String memo, Boolean waiveFee) {
	}

	private final ReservationCancelService cancels;

	public AdminReservationController(ReservationCancelService cancels) {
		this.cancels = cancels;
	}

	@PostMapping("/{reservationId}/cancel")
	@PreAuthorize("hasAuthority('ADMIN_SUPERVISOR')")
	public AdminCancelResponse cancel(@AuthenticationPrincipal AuthPrincipal me, @PathVariable long reservationId,
			@RequestBody(required = false) CancelRequest body) {
		return cancels.cancelByAdmin(me.actorId(), reservationId, body == null ? null : body.memo(),
				body == null || body.waiveFee() == null || body.waiveFee());
	}
}
