package com.jejari.group.web;

import java.util.List;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.group.GroupAdminService;
import com.jejari.group.GroupAllocator.Result;
import com.jejari.group.GroupQueryService;
import com.jejari.group.GroupQueryService.Detail;
import com.jejari.group.GroupQueryService.Summary;

/**
 * 관리자 단체 신청 화면. 조회는 관리자 누구나, 승인·반려는 REVIEWER 이상.
 *
 * <pre>
 * GET  /api/v1/admin/groups?status=REQUESTED   목록 (경고 있는 신청이 위)
 * GET  /api/v1/admin/groups/{id}               상세 (탑승자 마스킹)
 * POST /api/v1/admin/groups/{id}/approve       승인 (§18.3과 같은 절차)
 * POST /api/v1/admin/groups/{id}/reject        {"reason":"같은 명단으로 반복 신청"}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/groups")
@PreAuthorize("hasAuthority('ADMIN_VIEWER')") // 다른 관리자 컨트롤러와 같게 클래스에도 최소 권한 (보안 점검 2026-11-25 §3-6)
public class GroupAdminController {

	public record RejectRequest(String reason) {
	}

	private static final Set<String> STATUSES = Set.of("REQUESTED", "APPROVED", "REJECTED", "WITHDRAWN");

	private final GroupQueryService query;
	private final GroupAdminService admin;

	public GroupAdminController(GroupQueryService query, GroupAdminService admin) {
		this.query = query;
		this.admin = admin;
	}

	@GetMapping
	public List<Summary> list(@RequestParam(required = false) String status,
			@RequestParam(defaultValue = "50") int limit) {
		if (status != null && !STATUSES.contains(status)) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		return query.list(status, limit);
	}

	@GetMapping("/{groupId}")
	public Detail detail(@PathVariable long groupId) {
		return query.detail(groupId);
	}

	@PostMapping("/{groupId}/approve")
	@PreAuthorize("hasAuthority('ADMIN_REVIEWER')")
	public Result approve(@AuthenticationPrincipal AuthPrincipal me, @PathVariable long groupId) {
		return admin.approve(me.actorId(), groupId);
	}

	@PostMapping("/{groupId}/reject")
	@PreAuthorize("hasAuthority('ADMIN_REVIEWER')")
	public ResponseEntity<Void> reject(@AuthenticationPrincipal AuthPrincipal me, @PathVariable long groupId,
			@RequestBody(required = false) RejectRequest body) {
		admin.reject(me.actorId(), groupId, body == null ? null : body.reason());
		return ResponseEntity.noContent().build();
	}
}
