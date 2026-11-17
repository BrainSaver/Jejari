package com.jejari.waitlist.web;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.waitlist.WaitlistService;
import com.jejari.waitlist.WaitlistService.ApplyCommand;
import com.jejari.waitlist.WaitlistService.Entry;

/**
 * WL-01·02 취소표 대기 API (기능설계서 7장).
 *
 * <p>대기열 입장 토큰을 요구하지 않는다 — 매진 화면에서 바로 신청하기 때문이다(WL-01 [가정]).
 * 대신 {@code member.status = RESTRICTED}를 서비스가 막는다. 막지 않으면 위험 점수로 대기열
 * 뒤로 밀린 회원이 취소표로 우회하는 길이 된다 (concurrency.md §9.3-0).
 *
 * <p>행동 로그(BehaviorLog)는 아직 남기지 않는다. {@code BehaviorType}에 취소표 값이 없고
 * 그 목록은 행동 분석(D 강다인)의 것이라, 값을 임의로 늘리지 않았다. 필요하다고 하면
 * {@code WAITLIST_APPLY}·{@code WAITLIST_CANCEL}을 추가하고 여기에 두 줄 넣으면 된다.
 */
@RestController
@RequestMapping("/api/v1/waitlists")
public class WaitlistController {

	private final WaitlistService waitlists;

	public WaitlistController(WaitlistService waitlists) {
		this.waitlists = waitlists;
	}

	public record ApplyRequest(Long runId, String fromStationId, String toStationId, String seatGrade,
			Integer seatCount) {
	}

	/** POST /api/v1/waitlists */
	@PostMapping
	public Entry apply(@AuthenticationPrincipal AuthPrincipal me, @RequestBody ApplyRequest req) {
		if (req.runId() == null || req.fromStationId() == null || req.toStationId() == null
				|| req.seatGrade() == null || req.seatCount() == null) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
					"runId·fromStationId·toStationId·seatGrade·seatCount가 모두 필요합니다.");
		}
		return waitlists.apply(me.memberId(), new ApplyCommand(req.runId(), req.fromStationId(),
				req.toStationId(), req.seatGrade(), req.seatCount()));
	}

	/** GET /api/v1/waitlists — 내 대기 목록 (기다리는 중이면 내 순번도) */
	@GetMapping
	public List<Entry> myList(@AuthenticationPrincipal AuthPrincipal me) {
		return waitlists.myList(me.memberId());
	}

	/** DELETE /api/v1/waitlists/{id} — 대기 취소 (기다리는 중일 때만) */
	@DeleteMapping("/{waitlistId}")
	public Entry cancel(@AuthenticationPrincipal AuthPrincipal me, @PathVariable long waitlistId) {
		return waitlists.cancel(me.memberId(), waitlistId);
	}
}
