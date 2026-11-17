package com.jejari.risk.admin.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.risk.admin.CaseActionService;
import com.jejari.risk.admin.CaseActionService.ActionCommand;
import com.jejari.risk.admin.CaseActionService.ActionResult;
import com.jejari.risk.admin.CaseActionService.Unmasked;

/**
 * ADM-06 판정·조치(REVIEWER 이상), 마스킹 해제(SUPERVISOR만). 조회는 {@link CaseAdminController}.
 *
 * <pre>
 * POST /api/v1/admin/cases/{id}/actions  {"verdict":"MACRO","action":"RESTRICT","memo":"60초 안에 4계정 동시 선점"}
 * POST /api/v1/admin/cases/{id}/unmask   {"memo":"경찰 수사 협조 요청 2026-11-17"}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/cases")
public class CaseActionController {

	public record UnmaskRequest(String memo) {
	}

	private final CaseActionService service;

	public CaseActionController(CaseActionService service) {
		this.service = service;
	}

	@PostMapping("/{caseId}/actions")
	@PreAuthorize("hasAuthority('ADMIN_REVIEWER')")
	public ActionResult act(@AuthenticationPrincipal AuthPrincipal me, @PathVariable long caseId,
			@RequestBody ActionCommand body) {
		return service.act(me.actorId(), caseId, body);
	}

	/** 원문 개인정보가 나가는 유일한 경로. 응답을 어디에도 저장하지 않게 no-store */
	@PostMapping("/{caseId}/unmask")
	@PreAuthorize("hasAuthority('ADMIN_SUPERVISOR')")
	public ResponseEntity<Unmasked> unmask(@AuthenticationPrincipal AuthPrincipal me, @PathVariable long caseId,
			@RequestBody(required = false) UnmaskRequest body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(service.unmask(me.actorId(), caseId, body == null ? null : body.memo()));
	}
}
