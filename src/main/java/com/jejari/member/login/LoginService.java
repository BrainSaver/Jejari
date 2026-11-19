package com.jejari.member.login;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jejari.auth.ActorType;
import com.jejari.auth.AuthErrorCode;
import com.jejari.auth.session.AuthTokenService;
import com.jejari.auth.session.AuthTokenService.IssuedTokens;
import com.jejari.auth.session.ClientInfo;
import com.jejari.common.error.BusinessException;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.member.AuthLimiter;
import com.jejari.member.AuthPolicy;
import com.jejari.member.MemberAuthEnabled;
import com.jejari.member.login.LoginAttemptLog.Result;
import com.jejari.member.password.PasswordHasher;
import com.jejari.member.password.PasswordHasher.Hashed;
import com.jejari.member.password.PasswordPolicy;
import com.jejari.member.signup.SignupService;

/**
 * AUTH-03 로그인. 순서 (기능설계서 4.1):
 *
 * <ol>
 *   <li>IP당 분당 시도 제한 {@code rate:login:{ipHash}} → 넘으면 {@code AUTH_RATE_LIMITED} (해시 계산 없음)</li>
 *   <li>이메일별 연속 실패 잠금 {@code login:fail:{emailHash}} → 잠겼으면 {@code AUTH_LOCKED} (해시 계산 없음).
 *       <b>없는 이메일도 똑같이 세고 잠근다</b>: "잠김" 응답이 계정이 있다는 신호가 되지 않게.</li>
 *   <li>계정 조회. 없거나 탈퇴했으면 더미 해시를 검증해 같은 시간을 쓴 뒤 실패 (응답 시간으로 존재 여부 노출 방지)</li>
 *   <li>Argon2id 검증 (동시 수 제한)</li>
 *   <li>실패: 실패 수 +1 (기준 도달 시 잠금), {@code AUTH_INVALID_CREDENTIALS}. 아이디·비밀번호 중 무엇이 틀렸는지 구분 안 함</li>
 *   <li>성공: 비밀번호가 맞을 때만 정지 여부를 알려 준다 ({@code AUTH_BANNED}) — 비밀번호를 모르는 사람은 정지 사실도 모름</li>
 *   <li>실패 수 초기화, 필요하면 재해싱, 세션 발급</li>
 * </ol>
 * 모든 시도를 login_attempt에 남긴다.
 */
@Service
@MemberAuthEnabled
public class LoginService {
	private static final Logger log = LoggerFactory.getLogger(LoginService.class);

	private final PasswordHasher hasher;
	private final Pseudonymizer pseudonymizer;
	private final AuthLimiter limiter;
	private final AuthPolicy policy;
	private final AuthTokenService tokens;
	private final LoginAttemptLog attempts;
	private final JdbcTemplate jdbc;
	private final Clock clock;

	public LoginService(PasswordHasher hasher, Pseudonymizer pseudonymizer, AuthLimiter limiter, AuthPolicy policy,
			AuthTokenService tokens, LoginAttemptLog attempts, JdbcTemplate jdbc, Clock clock) {
		this.hasher = hasher;
		this.pseudonymizer = pseudonymizer;
		this.limiter = limiter;
		this.policy = policy;
		this.tokens = tokens;
		this.attempts = attempts;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	public IssuedTokens login(String rawEmail, String password, String ipHash, String userAgent) {
		return login(rawEmail, password, ipHash, userAgent, false);
	}

	/** @param remember "로그인 상태 유지" (고르면 14일·브라우저를 닫아도 유지, 아니면 12시간·닫으면 로그아웃) */
	public IssuedTokens login(String rawEmail, String password, String ipHash, String userAgent, boolean remember) {
		if (limiter.hit(AuthLimiter.loginRateKey(ipHash), Duration.ofMinutes(1))
				> policy.number(AuthPolicy.LOGIN_RATE_PER_MIN)) {
			attempts.record(null, null, Result.RATE_LIMITED, ipHash, userAgent, null, null);
			throw new BusinessException(AuthErrorCode.AUTH_RATE_LIMITED);
		}

		String email = normalizeOrNull(rawEmail);
		// 형식이 틀린 이메일도 같은 경로로 처리한다 (해시 키를 따로 두어 잠금 카운터를 공유)
		String emailHash = pseudonymizer.email(email != null ? email : "invalid@invalid.invalid");
		long threshold = policy.number(AuthPolicy.LOCK_AFTER_FAILURES);
		Duration lockFor = Duration.ofMinutes(policy.number(AuthPolicy.LOCK_MINUTES));

		if (limiter.failures(emailHash) >= threshold) {
			attempts.record(null, emailHash, Result.LOCKED, ipHash, userAgent, null, null);
			throw new BusinessException(AuthErrorCode.AUTH_LOCKED);
		}

		Account account = email == null ? null : find(email);
		// DB 잠금도 본다: Redis가 재시작돼 실패 카운터가 사라져도 잠금이 풀리지 않게 (막는 쪽)
		if (account != null && account.lockedUntil != null && account.lockedUntil.toInstant().isAfter(clock.instant())) {
			attempts.record(account.memberId, emailHash, Result.LOCKED, ipHash, userAgent, null, null);
			throw new BusinessException(AuthErrorCode.AUTH_LOCKED);
		}
		boolean passwordUsable = password != null && password.length() <= PasswordPolicy.MAX_LENGTH * 2;
		long started = System.nanoTime();
		boolean ok;
		if (account == null || !passwordUsable) {
			hasher.dummyVerify(passwordUsable ? password : "");
			ok = false;
		} else {
			ok = hasher.verify(password, account.passwordHash, account.pepperKeyId);
		}
		long verifyMs = (System.nanoTime() - started) / 1_000_000;

		if (!ok) {
			long n = limiter.recordFailure(emailHash, lockFor, threshold);
			if (account != null) {
				markFailure(account.memberId, n >= threshold ? lockFor : null);
			}
			attempts.record(account == null ? null : account.memberId, emailHash,
					account == null ? Result.NOT_FOUND : Result.WRONG_PASSWORD, ipHash, userAgent, null, verifyMs);
			throw new BusinessException(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
		}

		if ("BANNED".equals(account.status)) {
			attempts.record(account.memberId, emailHash, Result.BANNED, ipHash, userAgent, null, verifyMs);
			throw new BusinessException(AuthErrorCode.AUTH_BANNED);
		}

		limiter.clearFailures(emailHash);
		markSuccess(account, password);
		IssuedTokens issued = tokens.issue(ActorType.MEMBER, account.memberId, new ClientInfo(null, ipHash, userAgent),
				remember);
		attempts.record(account.memberId, emailHash, Result.SUCCESS, ipHash, userAgent, issued.sessionId(), verifyMs);
		return issued;
	}

	private Account find(String email) {
		List<Account> rows = jdbc.query("""
				SELECT m.member_id, m.status, c.password_hash, c.pepper_key_id, c.locked_until
				  FROM member m JOIN member_credential c ON c.member_id = m.member_id
				 WHERE m.email = ? AND m.withdrawn_at IS NULL""",
				(rs, i) -> new Account(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getTimestamp(5)), email);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** DB에도 실패 수·잠금 시각을 남긴다 (관리자 화면·감사용. 잠금 판단은 Redis가 함) */
	private void markFailure(long memberId, Duration lockFor) {
		if (lockFor == null) {
			jdbc.update("""
					UPDATE member_credential
					   SET failed_count = LEAST(failed_count + 1, 999), updated_at = SYSTIMESTAMP
					 WHERE member_id = ?""", memberId);
		} else {
			jdbc.update("""
					UPDATE member_credential
					   SET failed_count = LEAST(failed_count + 1, 999), locked_until = ?, updated_at = SYSTIMESTAMP
					 WHERE member_id = ?""", Timestamp.from(clock.instant().plus(lockFor)), memberId);
		}
	}

	private void markSuccess(Account account, String password) {
		Timestamp now = Timestamp.from(clock.instant());
		if (hasher.needsRehash(account.passwordHash, account.pepperKeyId)) {
			Hashed h = hasher.hash(password);
			// 조건부: 그 사이 비밀번호가 바뀌었으면(해시가 다르면) 덮어쓰지 않는다
			jdbc.update("""
					UPDATE member_credential
					   SET password_hash = ?, hash_params = ?, pepper_key_id = ?, needs_rehash = 'N',
					       failed_count = 0, locked_until = NULL, last_login_at = ?, updated_at = SYSTIMESTAMP
					 WHERE member_id = ? AND password_hash = ?""",
					h.hash(), h.params(), h.pepperKeyId(), now, account.memberId, account.passwordHash);
			log.info("비밀번호 재해싱 member={}", account.memberId);
		} else {
			jdbc.update("""
					UPDATE member_credential
					   SET failed_count = 0, locked_until = NULL, last_login_at = ?, updated_at = SYSTIMESTAMP
					 WHERE member_id = ?""", now, account.memberId);
		}
	}

	private static String normalizeOrNull(String raw) {
		try {
			return SignupService.normalizeEmail(raw);
		} catch (BusinessException e) {
			return null;
		}
	}

	private record Account(long memberId, String status, String passwordHash, String pepperKeyId,
			Timestamp lockedUntil) {
		@Override
		public String toString() {
			return "Account[" + memberId + "]";
		}
	}
}
