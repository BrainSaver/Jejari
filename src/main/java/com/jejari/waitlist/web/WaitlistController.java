package com.jejari.waitlist.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.behavior.BehaviorLog;
import com.jejari.behavior.BehaviorType;
import com.jejari.common.error.BusinessException;
import com.jejari.waitlist.WaitlistService;
import com.jejari.waitlist.WaitlistService.Applied;
import com.jejari.waitlist.WaitlistService.ApplyCommand;
import com.jejari.waitlist.WaitlistService.Item;

/**
 * 취소표 대기 (U-10). 대기열 입장 토큰은 필요 없다 (매진 화면에서 바로 신청).
 *
 * <pre>
 * POST   /api/v1/waitlists        {"runId":9001,"fromStationId":"…","toStationId":"…","seatGrade":"GENERAL","seatCount":1}  → 201
 * GET    /api/v1/waitlists        내 대기 목록 (최근 50건)
 * DELETE /api/v1/waitlists/{id}   대기 취소 (WAITING만) → 204
 * </pre>
 *
 * <p><b>행동 로그를 성공·실패 모두 남긴다</b> (강동균 추가, ERD v22). 취소표는 대기열 입장 토큰
 * 없이 들어오는 유일한 경로인데 로그가 없으면 {@code FAST_CLICK}·{@code REGULAR_INTERVAL}이
 * 그 구간을 통째로 못 본다. 두 규칙은 {@code event_type}을 가리지 않고 {@code interval_ms}만 보므로
 * 값을 남기기만 하면 바로 신호에 들어온다. 빠져 있으면 간격이 실제보다 길어 보여
 * <b>다른 이벤트의 신호까지 왜곡</b>한다.
 */
@RestController
@RequestMapping("/api/v1/waitlists")
public class WaitlistController {

	private final WaitlistService service;

	public WaitlistController(WaitlistService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<Applied> apply(@AuthenticationPrincipal AuthPrincipal me, @RequestBody ApplyCommand body) {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("seatGrade", body.seatGrade());
		meta.put("seatCount", body.seatCount());
		try {
			Applied applied = service.apply(me.memberId(), body);
			BehaviorLog.mark(BehaviorType.WAITLIST_APPLY, body.runId(), meta);
			return ResponseEntity.status(HttpStatus.CREATED).body(applied);
		} catch (BusinessException e) {
			// 실패도 남긴다. 매진 직후 거절당하며 재시도하는 패턴이 바로 봇 신호다
			meta.put("errorCode", e.getErrorCode().code());
			BehaviorLog.mark(BehaviorType.WAITLIST_APPLY, body.runId(), meta);
			throw e;
		}
	}

	@GetMapping
	public ResponseEntity<List<Item>> list(@AuthenticationPrincipal AuthPrincipal me) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(me.memberId()));
	}

	@DeleteMapping("/{waitlistId}")
	public ResponseEntity<Void> cancel(@AuthenticationPrincipal AuthPrincipal me, @PathVariable long waitlistId) {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("waitlistId", waitlistId);
		try {
			service.cancel(me.memberId(), waitlistId);
			BehaviorLog.mark(BehaviorType.WAITLIST_CANCEL, null, meta);
			return ResponseEntity.noContent().build();
		} catch (BusinessException e) {
			meta.put("errorCode", e.getErrorCode().code());
			BehaviorLog.mark(BehaviorType.WAITLIST_CANCEL, null, meta);
			throw e;
		}
	}
}
