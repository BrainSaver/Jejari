package com.jejari.auth;

/** 로그인 주체. auth_session.actor_type과 JWT role 클레임 값이 같습니다. */
public enum ActorType {
	MEMBER,
	ADMIN;

	/** Spring Security 권한 이름 (hasRole("ADMIN") → ROLE_ADMIN). */
	public String authority() {
		return "ROLE_" + name();
	}
}
