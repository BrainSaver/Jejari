package com.jejari.auth.session;

import com.jejari.common.web.UserAgents;

/**
 * 로그인할 때 auth_session에 남기는 접속 정보. 원문 IP·기기 ID는 저장하지 않고 HMAC 해시만 받습니다.
 *
 * @param deviceHash 기기 지문 해시 (없으면 null)
 * @param ipHash     IP 해시 (없으면 null)
 * @param userAgent  User-Agent (UTF-8 300바이트로 자름. 컬럼이 VARCHAR2(300) BYTE)
 */
public record ClientInfo(String deviceHash, String ipHash, String userAgent) {

	public ClientInfo {
		// 어느 경로로 들어와도 DB에 넣기 전 마지막으로 정리한다 (글자 수가 아니라 바이트 기준, 보안 점검 2026-11-25 §2.3)
		userAgent = UserAgents.clean(userAgent);
	}

	public static ClientInfo none() {
		return new ClientInfo(null, null, null);
	}
}
