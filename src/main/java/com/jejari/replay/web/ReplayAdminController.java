package com.jejari.replay.web;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.replay.ReplayAdminService;
import com.jejari.replay.ReplayAdminService.Replay;
import com.jejari.replay.ReplayAdminService.Session;

/**
 * ADM-11 화면 녹화 재생 (A-06 케이스 상세). 케이스 화면과 같은 권한(VIEWER 이상). 녹화에는 입력값·이름이 없고
 * 화면 ID·좌표·입력 길이만 있다.
 *
 * <pre>
 * GET /api/v1/admin/cases/{caseId}/replays                → 이 케이스와 겹치는 녹화 세션
 * GET /api/v1/admin/cases/{caseId}/replays/{sessionId}    → {session, events, server}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/cases/{caseId}/replays")
@PreAuthorize("hasAuthority('ADMIN_VIEWER')")
public class ReplayAdminController {

	private final ReplayAdminService service;

	public ReplayAdminController(ReplayAdminService service) {
		this.service = service;
	}

	@GetMapping
	public List<Session> sessions(@PathVariable long caseId) {
		return service.sessions(caseId);
	}

	@GetMapping("/{sessionId}")
	public ResponseEntity<Replay> replay(@PathVariable long caseId, @PathVariable String sessionId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.replay(caseId, sessionId));
	}
}
