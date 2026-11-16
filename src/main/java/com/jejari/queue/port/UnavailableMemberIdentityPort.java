package com.jejari.queue.port;

import java.util.Optional;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;

/**
 * 회원 정보 연동(member·결제 수단·기기 조회)이 아직 없을 때의 기본값. 앱은 뜨지만 대기열 진입은 503으로 거절합니다.
 * 실제 구현(MemberIdentityPort 빈)이 생기면 자동으로 그쪽을 씁니다.
 */
public class UnavailableMemberIdentityPort implements MemberIdentityPort {

	@Override
	public Optional<MemberIdentity> find(long memberId) {
		throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "대기열 준비 중입니다. (회원 정보 연동 전)");
	}
}
