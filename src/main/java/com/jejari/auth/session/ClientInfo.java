package com.jejari.auth.session;

/**
 * 로그인할 때 auth_session에 남기는 접속 정보. 원문 IP·기기 ID는 저장하지 않고 HMAC 해시만 받습니다.
 *
 * @param deviceHash 기기 지문 해시 (없으면 null)
 * @param ipHash     IP 해시 (없으면 null)
 * @param userAgent  User-Agent (300자로 자름)
 */
public record ClientInfo(String deviceHash, String ipHash, String userAgent) {

	private static final int USER_AGENT_MAX = 300;

	public ClientInfo {
		if (userAgent != null && userAgent.length() > USER_AGENT_MAX) {
			userAgent = userAgent.substring(0, USER_AGENT_MAX);
		}
	}

	public static ClientInfo none() {
		return new ClientInfo(null, null, null);
	}
}
