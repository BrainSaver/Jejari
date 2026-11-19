package com.jejari.admin.deploy.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.admin.deploy.DeployAdminService;
import com.jejari.admin.deploy.DeployAdminService.Status;
import com.jejari.auth.AuthPrincipal;

/** 공용 서버 수동 배포 (화면 A-08). 보기는 VIEWER, 누르기는 SUPERVISOR 중 서버 .env에 적힌 관리자만 */
@RestController
@RequestMapping("/api/v1/admin/deploy")
@PreAuthorize("hasAuthority('ADMIN_VIEWER')")
public class DeployAdminController {

	private final DeployAdminService deploy;

	public DeployAdminController(DeployAdminService deploy) {
		this.deploy = deploy;
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
}
