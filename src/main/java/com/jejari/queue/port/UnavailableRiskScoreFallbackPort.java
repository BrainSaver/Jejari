package com.jejari.queue.port;

import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;

/** Oracle 조회 구현이 아직 없을 때의 기본값. 캐시가 비어 있는 동안은 최대 패널티(안전한 쪽)로 처리됩니다. */
public class UnavailableRiskScoreFallbackPort implements RiskScoreFallbackPort {

	@Override
	public double maxScore(MemberIdentity member) {
		throw new IllegalStateException("위험 점수 Oracle 조회 구현(RiskScoreFallbackPort)이 없습니다");
	}
}
