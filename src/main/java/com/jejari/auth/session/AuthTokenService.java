package com.jejari.auth.session;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jejari.auth.ActorType;
import com.jejari.auth.AdminRole;
import com.jejari.auth.AdminRoleLookup;
import com.jejari.auth.AuthErrorCode;
import com.jejari.auth.AuthPrincipal;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.auth.jwt.AccessTokenIssuer.IssuedAccessToken;
import com.jejari.auth.jwt.JwtProperties;
import com.jejari.common.error.BusinessException;

/**
 * 토큰 발급·재발급·로그아웃 (AUTH-03 발급 부분, AUTH-04, AUTH-05).
 *
 * <pre>
 * 로그인 성공 → issue()   : auth_session 행 생성 + 액세스·리프레시 발급
 * 재발급     → refresh() : 같은 행의 해시를 새 값으로 바꿈(회전) + 새 액세스 발급
 * 로그아웃   → logout()  : 행 폐기 (LOGOUT)
 * </pre>
 *
 * 탈취 감지: 회전된 옛 리프레시 토큰이 다시 오면 해시가 맞지 않습니다. 세션이 아직 살아 있는데
 * 해시가 다르면 "누군가 옛 토큰을 갖고 있다"는 뜻이므로 계열 전체를 SUSPICIOUS로 폐기합니다.
 *
 * <p>관리자 세션은 발급·재발급 때마다 admin_user에서 현재 권한을 다시 읽어 토큰에 담습니다.
 * 비활성화된 관리자는 다음 재발급에서 세션이 폐기(ADMIN)됩니다.
 */
@Service
public class AuthTokenService {

	private static final Logger log = LoggerFactory.getLogger(AuthTokenService.class);

	private final AuthSessionRepository repository;
	private final AccessTokenIssuer accessTokenIssuer;
	private final JwtProperties props;
	private final Clock clock;
	private final AdminRoleLookup adminRoles;

	public AuthTokenService(AuthSessionRepository repository, AccessTokenIssuer accessTokenIssuer,
			JwtProperties props, Clock clock, AdminRoleLookup adminRoles) {
		this.repository = repository;
		this.accessTokenIssuer = accessTokenIssuer;
		this.props = props;
		this.clock = clock;
		this.adminRoles = adminRoles;
	}

	/**
	 * 로그인 검증이 끝난 뒤 호출합니다 (비밀번호·잠금·정지 확인은 호출하는 쪽 책임).
	 * 회원 로그인: {@code issue(ActorType.MEMBER, memberId, client)}, 관리자 로그인: {@code issue(ActorType.ADMIN, adminId, client)}
	 */
	@Transactional
	public IssuedTokens issue(ActorType actorType, long actorId, ClientInfo client) {
		AdminRole adminRole = actorType == ActorType.ADMIN
				? adminRoles.activeRole(actorId).orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_INVALID_CREDENTIALS))
				: null;
		String sessionId = UUID.randomUUID().toString();
		RefreshToken refreshToken = RefreshToken.generate(sessionId);
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDateTime expiresAt = now.plus(props.refreshTtl());

		repository.save(AuthSession.open(sessionId, UUID.randomUUID().toString(), actorType, actorId,
				refreshToken.hash(), client, now, expiresAt));

		IssuedAccessToken access = accessTokenIssuer.issue(actorType, actorId, sessionId, adminRole);
		return new IssuedTokens(sessionId, access.value(), access.expiresAt(),
				refreshToken.value(), toInstant(expiresAt));
	}

	/**
	 * 리프레시 토큰 회전. 실패 원인(없음·만료·폐기·재사용)은 응답에서 구분하지 않고 모두 AUTH_REFRESH_INVALID.
	 * 재사용 감지 시 폐기를 커밋해야 하므로 BusinessException에도 롤백하지 않습니다.
	 */
	@Transactional(noRollbackFor = BusinessException.class)
	public IssuedTokens refresh(String rawRefreshToken) {
		RefreshToken presented = RefreshToken.parse(rawRefreshToken)
				.orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_REFRESH_INVALID));
		String sessionId = presented.sessionId();
		RefreshToken next = RefreshToken.generate(sessionId);

		if (repository.rotate(sessionId, presented.hash(), next.hash()) == 1) {
			AuthSession session = repository.findById(sessionId).orElseThrow();
			AdminRole adminRole = null;
			if (session.getActorType() == ActorType.ADMIN) {
				adminRole = adminRoles.activeRole(session.actorId()).orElse(null);
				if (adminRole == null) {
					// 그 사이 비활성화·삭제된 관리자: 세션을 닫고 다시 로그인하게 함
					repository.revoke(sessionId, RevokeReason.ADMIN.name());
					throw new BusinessException(AuthErrorCode.AUTH_REFRESH_INVALID);
				}
			}
			IssuedAccessToken access = accessTokenIssuer.issue(session.getActorType(), session.actorId(), sessionId,
					adminRole);
			return new IssuedTokens(sessionId, access.value(), access.expiresAt(),
					next.value(), toInstant(session.getExpiresAt()));
		}

		// 회전 실패: 원인 확인
		AuthSession session = repository.findById(sessionId)
				.orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_REFRESH_INVALID));
		if (session.isActive(LocalDateTime.now(clock))) {
			// 살아 있는 세션인데 해시가 다름 = 이미 회전된 옛 토큰 재사용 → 탈취 의심
			int revoked = repository.revokeFamily(session.getTokenFamilyId(), RevokeReason.SUSPICIOUS.name());
			log.warn("refresh token reuse detected: sessionId={}, revoked={}", sessionId, revoked);
		}
		throw new BusinessException(AuthErrorCode.AUTH_REFRESH_INVALID);
	}

	/** 현재 세션 로그아웃. 여러 번 호출해도 결과가 같습니다. 액세스 토큰은 남은 TTL(최대 15분) 동안 유효합니다. */
	@Transactional
	public void logout(AuthPrincipal principal) {
		repository.revoke(principal.sessionId(), RevokeReason.LOGOUT.name());
	}

	private Instant toInstant(LocalDateTime dateTime) {
		return dateTime.atZone(clock.getZone()).toInstant();
	}

	/**
	 * 발급 결과. refreshToken은 쿠키로만 내보내고 응답 본문·로그에 넣지 않습니다.
	 */
	public record IssuedTokens(String sessionId, String accessToken, Instant accessExpiresAt,
			String refreshToken, Instant refreshExpiresAt) {

		@Override
		public String toString() {
			return "IssuedTokens[sessionId=" + sessionId + ", accessExpiresAt=" + accessExpiresAt
					+ ", refreshExpiresAt=" + refreshExpiresAt + "]";
		}
	}
}
