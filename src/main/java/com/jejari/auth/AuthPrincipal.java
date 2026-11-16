package com.jejari.auth;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;

/**
 * 로그인한 사용자. 컨트롤러에서 {@code @AuthenticationPrincipal AuthPrincipal me}로 받습니다.
 *
 * @param actorType 회원 / 관리자
 * @param actorId   member_id 또는 admin_id
 * @param sessionId auth_session.session_id (CCTV 녹화·행동 로그 연결 키)
 */
public record AuthPrincipal(ActorType actorType, long actorId, String sessionId) {

	/** 회원 API에서 member_id가 필요할 때. 관리자 토큰이면 403. */
	public long memberId() {
		if (actorType != ActorType.MEMBER) {
			throw new BusinessException(CommonErrorCode.ACCESS_DENIED);
		}
		return actorId;
	}

	/** 관리자 API에서 admin_id가 필요할 때. 회원 토큰이면 403. */
	public long adminId() {
		if (actorType != ActorType.ADMIN) {
			throw new BusinessException(AuthErrorCode.ADMIN_FORBIDDEN);
		}
		return actorId;
	}
}
