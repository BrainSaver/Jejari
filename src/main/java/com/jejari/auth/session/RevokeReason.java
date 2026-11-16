package com.jejari.auth.session;

/**
 * auth_session.revoke_reason (LOGOUT / ROTATED / ADMIN / SUSPICIOUS).
 *
 * <p>ROTATED는 쓰지 않습니다. 회전할 때 새 행을 만들지 않고 같은 행의 해시만 바꾸기 때문입니다
 * (session_id를 로그인 동안 고정해 CCTV 녹화·행동 로그가 끊기지 않게). 값은 DB CHECK와 맞추려고 남겨 둡니다.
 */
public enum RevokeReason {
	LOGOUT,
	ROTATED,
	ADMIN,
	SUSPICIOUS
}
