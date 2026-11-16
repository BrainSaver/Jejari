package com.jejari.auth.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 회전·폐기는 조건부 UPDATE 한 문장으로 판정합니다. 영향 행 수가 1이면 성공, 0이면 조건 불일치.
 * "이미 지났는가" 비교와 updated_at은 SYSTIMESTAMP (concurrency.md §0.3, §0.7).
 */
public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {

	/** 제시된 토큰 해시가 현재 값과 같고, 폐기·만료되지 않은 세션일 때만 새 해시로 바꿉니다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE auth_session
			   SET refresh_token_hash = :newHash,
			       updated_at = SYSTIMESTAMP
			 WHERE session_id = :sessionId
			   AND refresh_token_hash = :oldHash
			   AND revoked_at IS NULL
			   AND expires_at > SYSTIMESTAMP
			""", nativeQuery = true)
	int rotate(@Param("sessionId") String sessionId,
			@Param("oldHash") String oldHash,
			@Param("newHash") String newHash);

	/** 세션 하나 폐기 (로그아웃). 이미 폐기됐으면 0. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE auth_session
			   SET revoked_at = SYSTIMESTAMP,
			       revoke_reason = :reason,
			       updated_at = SYSTIMESTAMP
			 WHERE session_id = :sessionId
			   AND revoked_at IS NULL
			""", nativeQuery = true)
	int revoke(@Param("sessionId") String sessionId, @Param("reason") String reason);

	/** 토큰 계열 전체 폐기 (재사용 감지). */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE auth_session
			   SET revoked_at = SYSTIMESTAMP,
			       revoke_reason = :reason,
			       updated_at = SYSTIMESTAMP
			 WHERE token_family_id = :familyId
			   AND revoked_at IS NULL
			""", nativeQuery = true)
	int revokeFamily(@Param("familyId") String familyId, @Param("reason") String reason);
}
