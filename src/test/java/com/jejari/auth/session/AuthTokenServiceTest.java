package com.jejari.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.jejari.auth.ActorType;
import com.jejari.auth.AdminRole;
import com.jejari.auth.AuthErrorCode;
import com.jejari.auth.AuthPrincipal;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.auth.jwt.AccessTokenIssuer.IssuedAccessToken;
import com.jejari.auth.session.AuthTokenService.IssuedTokens;
import com.jejari.common.error.BusinessException;
import com.jejari.support.MutableClock;
import com.jejari.support.TestJwt;

/** 발급·회전·재사용 감지·로그아웃 판정 (DB는 목, 조건부 UPDATE 결과만 흉내). */
class AuthTokenServiceTest {

	static final String SID = "3f2b8c1e-1111-4222-8333-444455556666";

	final AuthSessionRepository repository = mock(AuthSessionRepository.class);
	final AccessTokenIssuer issuer = mock(AccessTokenIssuer.class);
	final MutableClock clock = new MutableClock();
	/** 활성 관리자 adminId → 권한. 없으면 비활성·삭제 */
	final Map<Long, AdminRole> activeAdmins = new HashMap<>();
	final AuthTokenService service = new AuthTokenService(repository, issuer, TestJwt.props(), clock,
			id -> Optional.ofNullable(activeAdmins.get(id)));

	LocalDateTime now() {
		return LocalDateTime.now(clock);
	}

	AuthSession activeSession(RefreshToken current) {
		return AuthSession.open(SID, "family-1", ActorType.MEMBER, 1001L, current.hash(), ClientInfo.none(),
				now(), now().plusDays(14));
	}

	@Test
	void 로그인_발급_시_해시만_저장하고_세션ID가_토큰_앞부분() {
		when(issuer.issue(eq(ActorType.MEMBER), eq(1001L), anyString(), isNull()))
				.thenReturn(new IssuedAccessToken("access", Instant.now()));

		IssuedTokens tokens = service.issue(ActorType.MEMBER, 1001L, ClientInfo.none());

		ArgumentCaptor<AuthSession> saved = ArgumentCaptor.forClass(AuthSession.class);
		verify(repository).save(saved.capture());
		RefreshToken refresh = RefreshToken.parse(tokens.refreshToken()).orElseThrow();
		assertThat(refresh.sessionId()).isEqualTo(tokens.sessionId()).isEqualTo(saved.getValue().getSessionId());
		assertThat(saved.getValue()).extracting("refreshTokenHash").isEqualTo(refresh.hash());
		assertThat(saved.getValue()).extracting("refreshTokenHash").isNotEqualTo(refresh.secret());
		// "로그인 상태 유지"를 고르지 않으면 12시간 + 브라우저를 닫으면 사라지는 쿠키
		assertThat(tokens.refreshExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(12)));
		assertThat(tokens.persistent()).isFalse();
		assertThat(tokens.actorType()).isEqualTo(ActorType.MEMBER);
	}

	@Test
	void 로그인_상태_유지를_고른_회원만_14일_유지_쿠키() {
		when(issuer.issue(eq(ActorType.MEMBER), eq(1001L), anyString(), isNull()))
				.thenReturn(new IssuedAccessToken("access", Instant.now()));
		IssuedTokens tokens = service.issue(ActorType.MEMBER, 1001L, ClientInfo.none(), true);
		assertThat(tokens.refreshExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(14)));
		assertThat(tokens.persistent()).isTrue();
	}

	@Test
	void 관리자는_유지를_골라도_8시간_세션_쿠키() {
		activeAdmins.put(7L, AdminRole.SUPERVISOR);
		when(issuer.issue(eq(ActorType.ADMIN), eq(7L), anyString(), eq(AdminRole.SUPERVISOR)))
				.thenReturn(new IssuedAccessToken("admin-access", Instant.now()));
		IssuedTokens tokens = service.issue(ActorType.ADMIN, 7L, ClientInfo.none(), true);
		assertThat(tokens.refreshExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(8)));
		assertThat(tokens.persistent()).isFalse();
		assertThat(tokens.actorType()).isEqualTo(ActorType.ADMIN);
	}

	@Test
	void 회원_입구에_관리자_세션_토큰이_오면_회전도_폐기도_하지_않고_거절() {
		RefreshToken current = RefreshToken.generate(SID);
		AuthSession admin = AuthSession.open(SID, "family-1", ActorType.ADMIN, 7L, current.hash(), ClientInfo.none(),
				now(), now().plusHours(8));
		when(repository.findById(SID)).thenReturn(Optional.of(admin));

		assertThatThrownBy(() -> service.refresh(current.value(), ActorType.MEMBER))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_REFRESH_INVALID);
		verify(repository, never()).rotate(anyString(), anyString(), anyString()); // 관리자 세션은 그대로 살아 있음
		verify(repository, never()).revokeFamily(anyString(), anyString());
	}

	@Test
	void 재발급해도_유지_여부는_로그인_때_그대로() {
		RefreshToken current = RefreshToken.generate(SID);
		when(repository.rotate(eq(SID), eq(current.hash()), anyString())).thenReturn(1);
		when(issuer.issue(ActorType.MEMBER, 1001L, SID, null)).thenReturn(new IssuedAccessToken("a", Instant.now()));

		when(repository.findById(SID)).thenReturn(Optional.of(activeSession(current))); // 14일 세션
		assertThat(service.refresh(current.value(), ActorType.MEMBER).persistent()).isTrue();

		AuthSession shortOne = AuthSession.open(SID, "family-1", ActorType.MEMBER, 1001L, current.hash(),
				ClientInfo.none(), now(), now().plusHours(12));
		when(repository.findById(SID)).thenReturn(Optional.of(shortOne));
		assertThat(service.refresh(current.value(), ActorType.MEMBER).persistent()).isFalse();
	}

	@Test
	void 재발급_성공하면_같은_세션에_새_리프레시_토큰() {
		RefreshToken current = RefreshToken.generate(SID);
		when(repository.rotate(eq(SID), eq(current.hash()), anyString())).thenReturn(1);
		when(repository.findById(SID)).thenReturn(Optional.of(activeSession(current)));
		when(issuer.issue(ActorType.MEMBER, 1001L, SID, null)).thenReturn(new IssuedAccessToken("access-2", Instant.now()));

		IssuedTokens tokens = service.refresh(current.value());

		assertThat(tokens.sessionId()).isEqualTo(SID);
		assertThat(tokens.accessToken()).isEqualTo("access-2");
		assertThat(tokens.refreshToken()).startsWith(SID + ".").isNotEqualTo(current.value());
		verify(repository, never()).revokeFamily(anyString(), anyString());
	}

	@Test
	void 회전된_옛_토큰이_다시_오면_계열_전체_폐기() {
		RefreshToken latest = RefreshToken.generate(SID);
		RefreshToken old = RefreshToken.generate(SID);
		when(repository.rotate(eq(SID), eq(old.hash()), anyString())).thenReturn(0);
		when(repository.findById(SID)).thenReturn(Optional.of(activeSession(latest)));

		assertThatThrownBy(() -> service.refresh(old.value()))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_REFRESH_INVALID);
		verify(repository).revokeFamily("family-1", "SUSPICIOUS");
	}

	@Test
	void 만료된_세션이면_폐기_없이_거절() {
		RefreshToken current = RefreshToken.generate(SID);
		AuthSession expired = AuthSession.open(SID, "family-1", ActorType.MEMBER, 1001L, current.hash(),
				ClientInfo.none(), now().minusDays(15), now().minusDays(1));
		when(repository.rotate(anyString(), anyString(), anyString())).thenReturn(0);
		when(repository.findById(SID)).thenReturn(Optional.of(expired));

		assertThatThrownBy(() -> service.refresh(current.value())).isInstanceOf(BusinessException.class);
		verify(repository, never()).revokeFamily(anyString(), anyString());
	}

	@Test
	void 없는_세션이면_거절() {
		when(repository.rotate(anyString(), anyString(), anyString())).thenReturn(0);
		when(repository.findById(any())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.refresh(RefreshToken.generate(SID).value()))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 형식이_틀리면_DB를_보지_않고_거절() {
		assertThatThrownBy(() -> service.refresh("not-a-token")).isInstanceOf(BusinessException.class);
		verifyNoInteractions(repository);
	}

	@Test
	void 로그아웃은_현재_세션만_폐기() {
		service.logout(new AuthPrincipal(ActorType.MEMBER, 1001L, SID));
		verify(repository).revoke(SID, "LOGOUT");
	}

	// ---------- 관리자 (AUTH-05) ----------

	AuthSession adminSession(RefreshToken current) {
		return AuthSession.open(SID, "family-a", ActorType.ADMIN, 7L, current.hash(), ClientInfo.none(),
				now(), now().plusDays(14));
	}

	@Test
	void 관리자_발급_때_현재_권한을_토큰에_담는다() {
		activeAdmins.put(7L, AdminRole.REVIEWER);
		when(issuer.issue(eq(ActorType.ADMIN), eq(7L), anyString(), eq(AdminRole.REVIEWER)))
				.thenReturn(new IssuedAccessToken("admin-access", Instant.now()));

		assertThat(service.issue(ActorType.ADMIN, 7L, ClientInfo.none()).accessToken()).isEqualTo("admin-access");
	}

	@Test
	void 비활성_관리자에게는_발급하지_않는다() {
		assertThatThrownBy(() -> service.issue(ActorType.ADMIN, 7L, ClientInfo.none()))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
		verify(repository, never()).save(any());
	}

	@Test
	void 관리자_재발급은_DB의_최신_권한으로() {
		RefreshToken current = RefreshToken.generate(SID);
		activeAdmins.put(7L, AdminRole.SUPERVISOR); // 로그인 뒤 권한이 바뀐 상황
		when(repository.rotate(eq(SID), eq(current.hash()), anyString())).thenReturn(1);
		when(repository.findById(SID)).thenReturn(Optional.of(adminSession(current)));
		when(issuer.issue(ActorType.ADMIN, 7L, SID, AdminRole.SUPERVISOR))
				.thenReturn(new IssuedAccessToken("admin-access-2", Instant.now()));

		assertThat(service.refresh(current.value()).accessToken()).isEqualTo("admin-access-2");
	}

	@Test
	void 비활성화된_관리자는_재발급하면_세션이_닫힌다() {
		RefreshToken current = RefreshToken.generate(SID);
		when(repository.rotate(eq(SID), eq(current.hash()), anyString())).thenReturn(1);
		when(repository.findById(SID)).thenReturn(Optional.of(adminSession(current)));

		assertThatThrownBy(() -> service.refresh(current.value()))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_REFRESH_INVALID);
		verify(repository).revoke(SID, "ADMIN");
	}
}
