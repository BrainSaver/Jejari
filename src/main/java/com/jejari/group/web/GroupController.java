package com.jejari.group.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.group.GroupApplyService;
import com.jejari.group.GroupApplyService.ApplyCommand;
import com.jejari.group.GroupApplyService.ApplyResult;
import com.jejari.group.GroupQueryService;
import com.jejari.group.GroupQueryService.Detail;
import com.jejari.group.GroupQueryService.Summary;
import com.jejari.group.GroupRosterService;
import com.jejari.group.GroupRosterService.Change;
import com.jejari.member.MemberAuthEnabled;

/**
 * 여행사 단체 신청 (대표자). 승인되면 응답의 reservationId로 일반 결제 API를 그대로 씁니다 (결제 기한 = payDeadlineAt).
 *
 * <pre>
 * POST /api/v1/groups                 신청 (+ 바로 자동 승인 시도)
 * GET  /api/v1/groups/me              내 신청 목록
 * GET  /api/v1/groups/{id}            내 신청 상세 (탑승자 마스킹)
 * POST /api/v1/groups/{id}/withdraw   승인 전 철회
 * PUT  /api/v1/groups/{id}/passengers {"changes":[{"passengerNo":3,"name":"김철수","phone":"010…","noPhone":false}]}
 *                                     명단 변경 (승인 뒤 7일 이내 1회, 대표자·인원·좌석은 그대로)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/groups")
@MemberAuthEnabled
public class GroupController {

	public record RosterRequest(List<Change> changes) {
	}

	private final GroupApplyService apply;
	private final GroupQueryService query;
	private final GroupRosterService roster;

	public GroupController(GroupApplyService apply, GroupQueryService query, GroupRosterService roster) {
		this.apply = apply;
		this.query = query;
		this.roster = roster;
	}

	@PutMapping("/{groupId}/passengers")
	public Detail changeRoster(@AuthenticationPrincipal AuthPrincipal me, @PathVariable long groupId,
			@RequestBody RosterRequest body) {
		roster.change(me.memberId(), groupId, body == null ? null : body.changes());
		return query.mineDetail(me.memberId(), groupId);
	}

	@PostMapping
	public ResponseEntity<ApplyResult> apply(@AuthenticationPrincipal AuthPrincipal me, @RequestBody ApplyCommand body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(apply.apply(me.memberId(), body));
	}

	@GetMapping("/me")
	public List<Summary> mine(@AuthenticationPrincipal AuthPrincipal me) {
		return query.mine(me.memberId());
	}

	@GetMapping("/{groupId}")
	public Detail detail(@AuthenticationPrincipal AuthPrincipal me, @PathVariable long groupId) {
		return query.mineDetail(me.memberId(), groupId);
	}

	@PostMapping("/{groupId}/withdraw")
	public ResponseEntity<Void> withdraw(@AuthenticationPrincipal AuthPrincipal me, @PathVariable long groupId) {
		apply.withdraw(me.memberId(), groupId);
		return ResponseEntity.noContent().build();
	}
}
