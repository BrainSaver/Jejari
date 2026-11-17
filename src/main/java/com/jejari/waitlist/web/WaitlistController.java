package com.jejari.waitlist.web;

import java.util.List;

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
		return ResponseEntity.status(HttpStatus.CREATED).body(service.apply(me.memberId(), body));
	}

	@GetMapping
	public ResponseEntity<List<Item>> list(@AuthenticationPrincipal AuthPrincipal me) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(me.memberId()));
	}

	@DeleteMapping("/{waitlistId}")
	public ResponseEntity<Void> cancel(@AuthenticationPrincipal AuthPrincipal me, @PathVariable long waitlistId) {
		service.cancel(me.memberId(), waitlistId);
		return ResponseEntity.noContent().build();
	}
}
