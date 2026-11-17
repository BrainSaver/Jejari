package com.jejari.auth.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jejari.auth.ActorType;
import com.jejari.auth.AdminRole;
import com.jejari.auth.AuthErrorCode;
import com.jejari.auth.admin.AdminAccountRepository.AdminAccount;
import com.jejari.auth.session.AuthTokenService;
import com.jejari.auth.session.AuthTokenService.IssuedTokens;
import com.jejari.common.error.BusinessException;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.common.hmac.Purpose;
import com.jejari.member.AuthLimiter;
import com.jejari.member.AuthPolicy;
import com.jejari.member.login.LoginAttemptLog;
import com.jejari.member.login.LoginAttemptLog.Result;
import com.jejari.member.password.PasswordHasher;
import com.jejari.support.MutableClock;
import com.jejari.support.TestJwt;

/** 관리자 로그인 판정 (DB·Redis·Mongo·Argon2는 목). 회원 로그인과 같은 보호 장치가 걸리는지 봅니다. */
class AdminLoginServiceTest {

	static final String IP = "ip-hash";
	static final String UA = "test-agent";
	static final AdminAccount ACTIVE = new AdminAccount(7L, "$argon2id$stored", AdminRole.SUPERVISOR, "ACTIVE");

	final AdminAccountRepository accounts = mock(AdminAccountRepository.class);
	final PasswordHasher hasher = mock(PasswordHasher.class);
	final Pseudonymizer pseudonymizer = Pseudonymizer.fromBase64(TestJwt.K2); // 테스트용 키
	final AuthLimiter limiter = mock(AuthLimiter.class);
	final AuthPolicy policy = mock(AuthPolicy.class);
	final AuthTokenService tokens = mock(AuthTokenService.class);
	final LoginAttemptLog attempts = mock(LoginAttemptLog.class);
	final AdminLoginService service = new AdminLoginService(accounts, hasher, pseudonymizer, limiter, policy, tokens,
			attempts, new MutableClock(), "p1");

	final String adminHash = pseudonymizer.hash(Purpose.ADMIN_LOGIN, "admin");

	@BeforeEach
	void defaults() {
		when(policy.number(AuthPolicy.LOGIN_RATE_PER_MIN)).thenReturn(20L);
		when(policy.number(AuthPolicy.LOCK_AFTER_FAILURES)).thenReturn(5L);
		when(policy.number(AuthPolicy.LOCK_MINUTES)).thenReturn(15L);
		when(limiter.hit(anyString(), any())).thenReturn(1L);
		when(accounts.findByLoginId("admin")).thenReturn(Optional.of(ACTIVE));
	}

	@Test
	void 성공하면_관리자_세션을_발급하고_실패_수를_지운다() {
		when(hasher.verify("Correct-Horse-9", ACTIVE.passwordHash(), "p1")).thenReturn(true);
		IssuedTokens issued = new IssuedTokens("sid-1", "access", Instant.now(), "rt", Instant.now());
		when(tokens.issue(eq(ActorType.ADMIN), eq(7L), any())).thenReturn(issued);

		assertThat(service.login("  Admin ", "Correct-Horse-9", IP, UA)).isSameAs(issued); // 앞뒤 공백·대문자 정규화

		verify(limiter).clearFailures(adminHash);
		verify(accounts).markLogin(eq(7L), any());
		verify(attempts).recordAdmin(eq(7L), eq(adminHash), eq(Result.SUCCESS), eq(IP), eq(UA), eq("sid-1"), any());
	}

	@Test
	void 비밀번호가_틀리면_실패_수를_올리고_구분하지_않는_오류() {
		when(hasher.verify(anyString(), anyString(), eq("p1"))).thenReturn(false);

		assertThatThrownBy(() -> service.login("admin", "wrong-password-1", IP, UA))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
		verify(limiter).recordFailure(eq(adminHash), any(), eq(5L));
		verify(attempts).recordAdmin(eq(7L), eq(adminHash), eq(Result.WRONG_PASSWORD), eq(IP), eq(UA), isNull(), any());
		verify(tokens, never()).issue(any(), anyLong(), any());
	}

	@Test
	void 없는_ID도_더미_검증으로_같은_시간을_쓰고_같은_오류() {
		when(accounts.findByLoginId("ghost")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.login("ghost", "whatever-pass-1", IP, UA))
				.extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
		verify(hasher).dummyVerify("whatever-pass-1");
		verify(attempts).recordAdmin(isNull(), anyString(), eq(Result.NOT_FOUND), eq(IP), eq(UA), isNull(), any());
	}

	@Test
	void 형식이_틀린_ID는_DB를_보지_않고_같은_오류() {
		assertThatThrownBy(() -> service.login("a b;--", "whatever-pass-1", IP, UA))
				.extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
		verify(accounts, never()).findByLoginId(anyString());
		verify(hasher).dummyVerify("whatever-pass-1");
	}

	@Test
	void 연속_실패로_잠기면_해시_계산_없이_AUTH_LOCKED() {
		when(limiter.failures(adminHash)).thenReturn(5L);

		assertThatThrownBy(() -> service.login("admin", "Correct-Horse-9", IP, UA))
				.extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_LOCKED);
		verify(hasher, never()).verify(anyString(), anyString(), anyString());
		verify(hasher, never()).dummyVerify(anyString());
	}

	@Test
	void IP_시도_제한을_넘으면_AUTH_RATE_LIMITED() {
		when(limiter.hit(anyString(), any())).thenReturn(21L);

		assertThatThrownBy(() -> service.login("admin", "Correct-Horse-9", IP, UA))
				.extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_RATE_LIMITED);
		verify(accounts, never()).findByLoginId(anyString());
	}

	@Test
	void 비활성_관리자는_비밀번호가_맞을_때만_정지를_알린다() {
		AdminAccount disabled = new AdminAccount(8L, "$argon2id$x", AdminRole.VIEWER, "DISABLED");
		when(accounts.findByLoginId("old-admin")).thenReturn(Optional.of(disabled));
		when(hasher.verify("Correct-Horse-9", disabled.passwordHash(), "p1")).thenReturn(true);

		assertThatThrownBy(() -> service.login("old-admin", "Correct-Horse-9", IP, UA))
				.extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_BANNED);
		verify(tokens, never()).issue(any(), anyLong(), any());
	}

	@Test
	void 해시_파라미터가_바뀌었으면_로그인_때_다시_해시한다() {
		when(hasher.verify(anyString(), anyString(), eq("p1"))).thenReturn(true);
		when(hasher.needsRehash(ACTIVE.passwordHash(), "p1")).thenReturn(true);
		when(hasher.hash("Correct-Horse-9")).thenReturn(new PasswordHasher.Hashed("$argon2id$new", "m=1", "p1"));
		when(tokens.issue(any(), anyLong(), any())).thenReturn(new IssuedTokens("s", "a", Instant.now(), "r", Instant.now()));

		service.login("admin", "Correct-Horse-9", IP, UA);

		verify(accounts).rehash(7L, ACTIVE.passwordHash(), "$argon2id$new");
	}
}
