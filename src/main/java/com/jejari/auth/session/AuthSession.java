package com.jejari.auth.session;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

import com.jejari.auth.ActorType;

/**
 * auth_session: 로그인 1번 = 행 1개. session_id는 로그아웃·만료까지 바뀌지 않습니다.
 * 리프레시 토큰을 회전하면 같은 행의 refresh_token_hash만 바뀝니다 (AuthSessionRepository#rotate).
 *
 * <p>상태 변경(회전·폐기)은 조건부 네이티브 UPDATE로만 합니다. 동시에 두 요청이 와도 한 쪽만 성공하게 하려고
 * 엔티티 필드를 바꾸는 방식(dirty checking)을 쓰지 않습니다.
 */
@Entity
@Table(name = "auth_session")
public class AuthSession implements Persistable<String> {

	@Id
	@Column(name = "session_id", length = 36)
	private String sessionId;

	@Column(name = "member_id")
	private Long memberId;

	@Enumerated(EnumType.STRING)
	@Column(name = "actor_type", nullable = false, length = 10)
	private ActorType actorType;

	@Column(name = "admin_id")
	private Long adminId;

	@Column(name = "refresh_token_hash", nullable = false, length = 64)
	private String refreshTokenHash;

	@Column(name = "token_family_id", nullable = false, length = 36)
	private String tokenFamilyId;

	@Column(name = "device_hash", length = 64)
	private String deviceHash;

	@Column(name = "ip_hash", length = 64)
	private String ipHash;

	@Column(name = "user_agent", length = 300)
	private String userAgent;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "revoked_at")
	private LocalDateTime revokedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "revoke_reason", length = 20)
	private RevokeReason revokeReason;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	/** session_id를 앱에서 정하므로 save()가 merge(SELECT 후 INSERT)하지 않게 새 행임을 알려 줍니다. */
	@Transient
	private boolean isNew = true;

	protected AuthSession() {
	}

	public static AuthSession open(String sessionId, String tokenFamilyId, ActorType actorType, long actorId,
			String refreshTokenHash, ClientInfo client, LocalDateTime now, LocalDateTime expiresAt) {
		AuthSession s = new AuthSession();
		s.sessionId = sessionId;
		s.tokenFamilyId = tokenFamilyId;
		s.actorType = actorType;
		if (actorType == ActorType.MEMBER) {
			s.memberId = actorId;
		} else {
			s.adminId = actorId;
		}
		s.refreshTokenHash = refreshTokenHash;
		s.deviceHash = client.deviceHash();
		s.ipHash = client.ipHash();
		s.userAgent = client.userAgent();
		s.createdAt = now;
		s.updatedAt = now;
		s.expiresAt = expiresAt;
		return s;
	}

	public long actorId() {
		return actorType == ActorType.MEMBER ? memberId : adminId;
	}

	public boolean isActive(LocalDateTime now) {
		return revokedAt == null && expiresAt.isAfter(now);
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.isNew = false;
	}

	@Override
	public String getId() {
		return sessionId;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	public String getSessionId() {
		return sessionId;
	}

	public ActorType getActorType() {
		return actorType;
	}

	public String getTokenFamilyId() {
		return tokenFamilyId;
	}

	public LocalDateTime getExpiresAt() {
		return expiresAt;
	}

	public LocalDateTime getRevokedAt() {
		return revokedAt;
	}
}
