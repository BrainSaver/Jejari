package com.jejari.reservation.web;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.reservation.ReservationQueryService;
import com.jejari.reservation.ReservationQueryService.Detail;
import com.jejari.reservation.ReservationQueryService.Page;
import com.jejari.reservation.ReservationQueryService.Status;

/**
 * RSV-04 예매 내역 (U-08). 같은 경로의 POST(선점·취소)는 {@code seat.web.ReservationController}가 맡는다.
 *
 * <pre>
 * GET /api/v1/reservations?status=CONFIRMED,CANCELED&amp;before=1024&amp;size=20   내 예약 목록 (최근 것 먼저)
 * GET /api/v1/reservations/{id}                                         상세 (좌석·승차권·결제·취소 가능 여부·예상 수수료)
 * </pre>
 *
 * 응답에 승차권 번호·결제 상태가 있어 브라우저·프록시에 캐시하지 않는다 ({@code Cache-Control: no-store}).
 */
@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationQueryController {

	private final ReservationQueryService service;

	public ReservationQueryController(ReservationQueryService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<Page> list(@AuthenticationPrincipal AuthPrincipal me,
			@RequestParam(required = false) String status, @RequestParam(required = false) Long before,
			@RequestParam(required = false) Integer size) {
		int n = size == null ? ReservationQueryService.DEFAULT_SIZE : size;
		if (n < 1 || n > ReservationQueryService.MAX_SIZE || (before != null && before <= 0)) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		return noStore(service.list(me.memberId(), statuses(status), before, n));
	}

	@GetMapping("/{reservationId}")
	public ResponseEntity<Detail> detail(@AuthenticationPrincipal AuthPrincipal me, @PathVariable long reservationId) {
		return noStore(service.detail(me.memberId(), reservationId));
	}

	/** "CONFIRMED,CANCELED" → 집합. 모르는 값이 하나라도 있으면 400 (조용히 무시하면 "전체"로 보일 수 있음) */
	static Set<Status> statuses(String raw) {
		Set<Status> out = EnumSet.noneOf(Status.class);
		if (raw == null || raw.isBlank()) {
			return out;
		}
		String[] parts = raw.split(",");
		if (parts.length > Status.values().length) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		for (String p : parts) {
			try {
				out.add(Status.valueOf(p.strip().toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException e) {
				throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
			}
		}
		return out;
	}

	private static <T> ResponseEntity<T> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
