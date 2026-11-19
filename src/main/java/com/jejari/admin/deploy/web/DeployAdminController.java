package com.jejari.admin.deploy.web;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.admin.deploy.DeployAdminService;
import com.jejari.admin.deploy.DeployAdminService.Status;
import com.jejari.admin.deploy.DeployApprovalService;
import com.jejari.admin.deploy.DeployApprovalService.Blocked;
import com.jejari.admin.deploy.DeployApprovalService.PatchResult;
import com.jejari.auth.AuthPrincipal;

/**
 * 공용 서버 수동 배포 (화면 A-08). 보기는 VIEWER, 누르기는 SUPERVISOR 중 서버 .env에 적힌 관리자만.
 * BLOCKED 확인·DB 패치 적용·승인도 같은 규칙 (DeployApprovalService).
 */
@RestController
@RequestMapping("/api/v1/admin/deploy")
@PreAuthorize("hasAuthority('ADMIN_VIEWER')")
public class DeployAdminController {

	private final DeployAdminService deploy;
	private final DeployApprovalService approval;

	public DeployAdminController(DeployAdminService deploy, DeployApprovalService approval) {
		this.deploy = deploy;
		this.approval = approval;
	}

	@GetMapping
	public Status status(@AuthenticationPrincipal AuthPrincipal me) {
		return deploy.status(me.actorId());
	}

	@PostMapping
	@PreAuthorize("hasAuthority('ADMIN_SUPERVISOR')")
	public Status trigger(@AuthenticationPrincipal AuthPrincipal me) {
		return deploy.trigger(me.actorId());
	}

	/** 지금 BLOCKED 인 커밋과 파일별 할 일 */
	@GetMapping("/blocked")
	public Blocked blocked(@AuthenticationPrincipal AuthPrincipal me) {
		return approval.blocked(me.actorId());
	}

	/** 막힌 파일의 내용 (그 커밋 기준) */
	@GetMapping("/blocked/file")
	@PreAuthorize("hasAuthority('ADMIN_SUPERVISOR')")
	public FileBody file(@RequestParam String sha, @RequestParam String path) {
		return new FileBody(path, approval.file(sha, path));
	}

	@PostMapping("/blocked/patches")
	@PreAuthorize("hasAuthority('ADMIN_SUPERVISOR')")
	public PatchResult applyPatch(@AuthenticationPrincipal AuthPrincipal me, @RequestBody PatchRequest req) {
		return approval.applyPatch(me.actorId(), req.sha(), req.path());
	}

	@PostMapping("/blocked/approve")
	@PreAuthorize("hasAuthority('ADMIN_SUPERVISOR')")
	public Blocked approve(@AuthenticationPrincipal AuthPrincipal me, @RequestBody ApproveRequest req) {
		return approval.approve(me.actorId(), req.sha(), req.confirmed() == null ? List.of() : req.confirmed());
	}

	public record FileBody(String path, String content) {
	}

	public record PatchRequest(String sha, String path) {
	}

	public record ApproveRequest(String sha, List<String> confirmed) {
	}
}
