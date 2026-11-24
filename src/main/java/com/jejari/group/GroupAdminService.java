package com.jejari.group;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.group.GroupAllocator.Outcome;
import com.jejari.group.GroupAllocator.Result;

/**
 * 관리자 단체 신청 처리. 위험 점수 때문에 자동 승인하지 않은 신청(REVIEW)을 관리자가 보고 승인하거나 반려합니다.
 * 승인은 자동 승인과 같은 절차(§18.3)라 오픈 직후에는 미뤄질 수 있습니다(DEFERRED → 스캐너가 이어서).
 *
 * <p>승인된 단체의 취소는 예약 취소(§8) 쪽입니다. 단체 위약금(group_fee_policy) 분기는 취소 코드와 맞춘 뒤 추가합니다 (§18.6).
 */
@Service
public class GroupAdminService {

	private static final Logger log = LoggerFactory.getLogger(GroupAdminService.class);

	private final JdbcTemplate jdbc;
	private final GroupAllocator allocator;

	public GroupAdminService(JdbcTemplate jdbc, GroupAllocator allocator) {
		this.jdbc = jdbc;
		this.allocator = allocator;
	}

	public Result approve(long adminId, long groupId) {
		Result r = allocator.approve(groupId, adminId, false, false);
		if (r.outcome() == Outcome.NOT_REQUESTED) {
			throw new BusinessException(exists(groupId) ? GroupErrorCode.GROUP_NOT_REQUESTED : GroupErrorCode.GROUP_NOT_FOUND);
		}
		log.info("관리자 {} 단체 {} 승인 시도 → {}", adminId, groupId, r.outcome());
		return r;
	}

	public void reject(long adminId, long groupId, String reason) {
		String why = reason == null ? "" : reason.strip();
		if (why.isEmpty() || why.length() > 100) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "반려 사유를 100자 이내로 적어 주세요.");
		}
		int n = jdbc.update("""
				UPDATE group_booking SET status = 'REJECTED', cancel_reason = ?, approved_by = ?, updated_at = SYSTIMESTAMP
				 WHERE group_id = ? AND status = 'REQUESTED'""", why, adminId, groupId);
		if (n != 1) {
			throw new BusinessException(exists(groupId) ? GroupErrorCode.GROUP_NOT_REQUESTED : GroupErrorCode.GROUP_NOT_FOUND);
		}
		log.info("관리자 {} 단체 {} 반려", adminId, groupId);
	}

	private boolean exists(long groupId) {
		Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM group_booking WHERE group_id = ?", Integer.class, groupId);
		return n != null && n > 0;
	}
}
