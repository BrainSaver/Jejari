package com.jejari.admin.policy.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.admin.policy.AuthRelaxService;
import com.jejari.admin.policy.AuthRelaxService.StartCommand;
import com.jejari.admin.policy.AuthRelaxService.Status;
import com.jejari.auth.AuthPrincipal;
import com.jejari.member.MemberAuthEnabled;
import com.jejari.member.web.ClientIpResolver;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 인증 시도 제한 임시 해제 (운영 설정 화면). 보기는 VIEWER, 켜고 끄기는 SUPERVISOR.
 *
 * <pre>
 *   GET    /api/v1/admin/auth-relax   지금 상태
 *   POST   /api/v1/admin/auth-relax   {ip(비우면 내 IP), hours 1~24, reason 5자 이상}
 *   DELETE /api/v1/admin/auth-relax   지금 끝내기
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/auth-relax")
@PreAuthorize("hasAuthority('ADMIN_VIEWER')")
@MemberAuthEnabled
public class AuthRelaxController {

	private final AuthRelaxService relax;
	private final ClientIpResolver ipResolver;

	public AuthRelaxController(AuthRelaxService relax, ClientIpResolver ipResolver) {
		this.relax = relax;
		this.ipResolver = ipResolver;
	}

	@GetMapping
	public Status status(HttpServletRequest http) {
		return relax.status(ipResolver.resolve(http));
	}

	@PostMapping
	@PreAuthorize("hasAuthority('ADMIN_SUPERVISOR')")
	public Status start(@AuthenticationPrincipal AuthPrincipal me, @RequestBody StartCommand body, HttpServletRequest http) {
		return relax.start(me.actorId(), body, ipResolver.resolve(http));
	}

	@DeleteMapping
	@PreAuthorize("hasAuthority('ADMIN_SUPERVISOR')")
	public Status stop(@AuthenticationPrincipal AuthPrincipal me, HttpServletRequest http) {
		return relax.stop(me.actorId(), ipResolver.resolve(http));
	}
}
