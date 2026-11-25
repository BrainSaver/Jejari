package com.jejari.member;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jejari.auth.AuthErrorCode;
import com.jejari.common.error.BusinessException;

/**
 * 정지(BANNED) 회원을 막는 한 곳. 판단은 JWT가 아니라 DB의 {@code member.status}로 한다.
 *
 * <p>관리자가 정지하면 세션은 폐기되지만 이미 받은 액세스 토큰은 만료(15분)까지 서명 검증을 통과한다.
 * 대기열·좌석 선점·취소표 대기는 원래 DB 상태를 봐서 바로 막혔지만, 결제·내 정보 변경·탈퇴는 보지 않아
 * 그 15분 동안 결제를 끝내거나 탈퇴로 이메일을 풀어 다시 가입할 수 있었다 (보안 점검 2026-11-25 §2.2).
 * 막는 기능은 호출이 드물어 조회 1번은 부담이 아니다.
 *
 * <p>가입 쪽은 회원 ID가 아직 없으므로 CI로 본다 ({@link #forbidBannedCi}): 같은 명의로 다시 가입해 정지를 피하지 못하게 (§2.1).
 */
@Component
public class MemberStatusGuard {

	private final JdbcTemplate jdbc;

	public MemberStatusGuard(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 정지된 회원이면 403 AUTH_BANNED */
	public void forbidBanned(long memberId) {
		List<String> status = jdbc.queryForList("SELECT status FROM member WHERE member_id = ?", String.class, memberId);
		if (!status.isEmpty() && "BANNED".equals(status.get(0))) {
			throw new BusinessException(AuthErrorCode.AUTH_BANNED);
		}
	}

	/** 이 CI로 정지된 계정이 하나라도 있으면(탈퇴 계정 포함) 403 AUTH_BANNED. 비회원 입장(GuestService)과 같은 기준 */
	public void forbidBannedCi(String ciHash) {
		Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE ci_hash = ? AND status = 'BANNED'",
				Integer.class, ciHash);
		if (n != null && n > 0) {
			throw new BusinessException(AuthErrorCode.AUTH_BANNED);
		}
	}
}
