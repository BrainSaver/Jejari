package com.jejari.admin.policy.web;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.admin.policy.PolicyAdminService;
import com.jejari.admin.policy.PolicyAdminService.History;
import com.jejari.admin.policy.PolicyAdminService.Policy;
import com.jejari.admin.policy.PolicyAdminService.UpdateCommand;
import com.jejari.auth.AuthPrincipal;

/** ADM-08 운영 설정 API (화면 A-07, 기능설계서 7장 /admin/policies). 보기는 VIEWER, 바꾸기는 SUPERVISOR */
@RestController
@RequestMapping("/api/v1/admin/policies")
@PreAuthorize("hasAuthority('ADMIN_VIEWER')")
public class PolicyAdminController {

	private final PolicyAdminService policies;

	public PolicyAdminController(PolicyAdminService policies) {
		this.policies = policies;
	}

	@GetMapping
	public List<Policy> list() {
		return policies.list();
	}

	@GetMapping("/{key}")
	public Policy get(@PathVariable String key) {
		return policies.get(key);
	}

	@GetMapping("/{key}/history")
	public List<History> history(@PathVariable String key) {
		return policies.history(key);
	}

	@PutMapping("/{key}")
	@PreAuthorize("hasAuthority('ADMIN_SUPERVISOR')")
	public Policy update(@AuthenticationPrincipal AuthPrincipal me, @PathVariable String key, @RequestBody UpdateCommand body) {
		return policies.update(me.actorId(), key, body);
	}
}
