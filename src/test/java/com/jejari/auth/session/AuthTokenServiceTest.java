package com.jejari.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.jejari.auth.ActorType;
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
	final AuthTokenService service = new AuthTokenService(repository, issuer, TestJwt.props(), clock);

	LocalDateTime now() {
		return LocalDateTime.now(clock);
	}

	AuthSession activeSession(RefreshToken current) {
		return AuthSession.open(SID, "family-1", ActorType.MEMBER, 1001L, current.hash(), ClientInfo.none(),
				now(), now().plusDays(14));
	}

	@Test
	void 로그인_발급_시_해시만_저장하고_세션ID가_토큰_앞부분() {
		when(issuer.issue(eq(ActorType.MEMBER), eq(1001L), anyString()))
				.thenReturn(new IssuedAccessToken("access", Instant.now()));

		IssuedTokens tokens = service.issue(ActorType.MEMBER, 1001L, ClientInfo.none());

		ArgumentCaptor<AuthSession> saved = ArgumentCaptor.forClass(AuthSession.class);
		verify(repository).save(saved.capture());
		RefreshToken refresh = RefreshToken.parse(tokens.refreshToken()).orElseThrow();
		assertThat(refresh.sessionId()).isEqualTo(tokens.sessionId()).isEqualTo(saved.getValue().getSessionId());
		assertThat(saved.getValue()).extracting("refreshTokenHash").isEqualTo(refresh.hash());
		assertThat(saved.getValue()).extracting("refreshTokenHash").isNotEqualTo(refresh.secret());
		assertThat(tokens.refreshExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(14)));
	}

	@Test
	void 재발급_성공하면_같은_세션에_새_리프레시_토큰() {
		RefreshToken current = RefreshToken.generate(SID);
		when(repository.rotate(eq(SID), eq(current.hash()), anyString())).thenReturn(1);
		when(repository.findById(SID)).thenReturn(Optional.of(activeSession(current)));
		when(issuer.issue(ActorType.MEMBER, 1001L, SID)).thenReturn(new IssuedAccessToken("access-2", Instant.now()));

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
}
