package com.jejari.queue.port;

import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;

/**
 * Redis 위험 점수 캐시가 비어 있을 때(risk:meta:synced_at 없음) 대기열이 점수를 직접 묻는 곳.
 * 실제 구현은 Oracle risk_score에서 이 회원의 CI·결제 수단·기기 점수 중 최댓값을 읽는다 (D 담당 모듈).
 * 점수가 없으면 0, 있으면 0~100 사이 값을 돌려준다. 조회에 실패하면 예외를 던진다 (대기열이 최대 패널티로 처리).
 */
public interface RiskScoreFallbackPort {

	double maxScore(MemberIdentity member);
}
