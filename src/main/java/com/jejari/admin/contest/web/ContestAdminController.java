package com.jejari.admin.contest.web;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.admin.contest.ContestResultService;
import com.jejari.admin.contest.ContestResultService.ContestResult;
import com.jejari.admin.contest.ContestResultService.ContestSummary;

/**
 * 봇 대회 결과 (읽기 전용, 보기는 VIEWER). 대회 진행은 집계자 PC의 진행 화면(loadtest/contest/web)이 하고,
 * 여기서는 아무것도 실행하거나 쓰지 않는다 — 웹에서 서버 명령·봇을 돌리는 기능은 일부러 두지 않았다.
 *
 * <pre>
 * GET /api/v1/admin/contests                대회 목록 (참가자 이메일 태그별)
 * GET /api/v1/admin/contests/{tag}?runId=   종류별 결과: 결제한 좌석·막힌 층·사람 오탐·걸린 규칙
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/contests")
@PreAuthorize("hasAuthority('ADMIN_VIEWER')")
public class ContestAdminController {

	private final ContestResultService service;

	public ContestAdminController(ContestResultService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<List<ContestSummary>> list() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list());
	}

	@GetMapping("/{tag}")
	public ResponseEntity<ContestResult> result(@PathVariable String tag, @RequestParam(required = false) Long runId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.result(tag, runId));
	}
}
