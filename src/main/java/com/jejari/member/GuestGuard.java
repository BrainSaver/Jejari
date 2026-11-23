package com.jejari.member;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jejari.common.error.BusinessException;
import com.jejari.member.guest.GuestErrorCode;

/**
 * 비회원(게스트)이 쓸 수 없는 기능(취소표 대기, 내 정보 변경·탈퇴)을 막는 한 곳.
 * 게스트 여부는 JWT가 아니라 DB의 member.member_type으로 판단한다: 게스트가 같은 CI로 가입해 회원이 되면(행이 회원으로 바뀜)
 * 토큰이 남아 있어도 바로 회원으로 대접받고, 반대로 막힌 기능을 토큰으로 우회할 수 없다. 막는 기능은 호출이 드물어 조회 1번은 부담이 아니다.
 */
@Component
public class GuestGuard {

	private final JdbcTemplate jdbc;

	public GuestGuard(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public boolean isGuest(long memberId) {
		Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE member_id = ? AND member_type = 'GUEST'",
				Integer.class, memberId);
		return n != null && n > 0;
	}

	/** 게스트면 403 GUEST_NOT_ALLOWED */
	public void forbidGuest(long memberId) {
		if (isGuest(memberId)) {
			throw new BusinessException(GuestErrorCode.GUEST_NOT_ALLOWED);
		}
	}
}
