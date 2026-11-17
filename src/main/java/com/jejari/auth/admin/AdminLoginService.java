package com.jejari.auth.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.jejari.auth.ActorType;
import com.jejari.auth.AuthErrorCode;
import com.jejari.auth.admin.AdminAccountRepository.AdminAccount;
import com.jejari.auth.session.AuthTokenService;
import com.jejari.auth.session.AuthTokenService.IssuedTokens;
import com.jejari.auth.session.ClientInfo;
import com.jejari.common.error.BusinessException;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.common.hmac.Purpose;
import com.jejari.member.AuthLimiter;
import com.jejari.member.AuthPolicy;
import com.jejari.member.MemberAuthEnabled;
import com.jejari.member.login.LoginAttemptLog;
import com.jejari.member.login.LoginAttemptLog.Result;
import com.jejari.member.password.PasswordHasher;
import com.jejari.member.password.PasswordPolicy;

/**
 * AUTH-05 관리자 로그인. 회원 로그인(LoginService, 강다인)과 같은 순서·같은 보호 장치를 씁니다.
 *
 * <ol>
 *   <li>IP당 분당 시도 제한 {@code rate:login:{ipHash}} (회원과 같은 카운터) → {@code AUTH_RATE_LIMITED}</li>
 *   <li>로그인 ID별 연속 실패 잠금 {@code login:fail:{hash}} → {@code AUTH_LOCKED}. 없는 ID도 똑같이 세고 잠금</li>
 *   <li>계정이 없으면 더미 해시를 검증해 같은 시간을 쓴 뒤 실패 (응답 시간으로 계정 존재 여부 노출 방지)</li>
 *   <li>Argon2id 검증. admin_user에 pepper_key_id가 없어 현재(active) pepper로만 검증</li>
 *   <li>비밀번호가 맞을 때만 비활성 여부를 알려 줌 ({@code AUTH_BANNED})</li>
 *   <li>성공: 실패 수 초기화, 필요하면 재해싱, 세션 발급 (토큰에 세부 권한 adm)</li>
 * </ol>
 * 모든 시도를 login_attempt에 actor_type = ADMIN으로 남깁니다.
 */
@Service
@MemberAuthEnabled
public class AdminLoginService {

	private static final Logger log = LoggerFactory.getLogger(AdminLoginService.class);

	/** admin_user.login_id: 영문 소문자·숫자·.-_ 3~50자 (대소문자 구분 없음) */
	static final Pattern LOGIN_ID = Pattern.compile("^[a-z0-9._-]{3,50}$");
	private static final String INVALID_ID_KEY = "invalid-admin-id";

	private final AdminAccountRepository accounts;
	private final PasswordHasher hasher;
	private final Pseudonymizer pseudonymizer;
	private final AuthLimiter limiter;
	private final AuthPolicy policy;
	private final AuthTokenService tokens;
	private final LoginAttemptLog attempts;
	private final Clock clock;
	private final String activePepper;

	public AdminLoginService(AdminAccountRepository accounts, PasswordHasher hasher, Pseudonymizer pseudonymizer,
			AuthLimiter limiter, AuthPolicy policy, AuthTokenService tokens, LoginAttemptLog attempts, Clock clock,
			@Value("${jejari.auth.pepper-active:}") String activePepper) {
		this.accounts = accounts;
		this.hasher = hasher;
		this.pseudonymizer = pseudonymizer;
		this.limiter = limiter;
		this.policy = policy;
		this.tokens = tokens;
		this.attempts = attempts;
		this.clock = clock;
		this.activePepper = activePepper.strip();
	}

	public IssuedTokens login(String rawLoginId, String password, String ipHash, String userAgent) {
		if (limiter.hit(AuthLimiter.loginRateKey(ipHash), Duration.ofMinutes(1))
				> policy.number(AuthPolicy.LOGIN_RATE_PER_MIN)) {
			attempts.recordAdmin(null, null, Result.RATE_LIMITED, ipHash, userAgent, null, null);
			throw new BusinessException(AuthErrorCode.AUTH_RATE_LIMITED);
		}

		String loginId = normalizeOrNull(rawLoginId);
		// 형식이 틀린 ID도 같은 경로로 (잠금 카운터를 공유하는 고정 키)
		String idHash = pseudonymizer.hash(Purpose.ADMIN_LOGIN, loginId != null ? loginId : INVALID_ID_KEY);
		long threshold = policy.number(AuthPolicy.LOCK_AFTER_FAILURES);
		Duration lockFor = Duration.ofMinutes(policy.number(AuthPolicy.LOCK_MINUTES));

		if (limiter.failures(idHash) >= threshold) {
			attempts.recordAdmin(null, idHash, Result.LOCKED, ipHash, userAgent, null, null);
			throw new BusinessException(AuthErrorCode.AUTH_LOCKED);
		}

		AdminAccount account = loginId == null ? null : accounts.findByLoginId(loginId).orElse(null);
		boolean passwordUsable = password != null && password.length() <= PasswordPolicy.MAX_LENGTH * 2;
		long started = System.nanoTime();
		boolean ok;
		if (account == null || !passwordUsable) {
			hasher.dummyVerify(passwordUsable ? password : "");
			ok = false;
		} else {
			ok = hasher.verify(password, account.passwordHash(), activePepper);
		}
		long verifyMs = (System.nanoTime() - started) / 1_000_000;

		if (!ok) {
			limiter.recordFailure(idHash, lockFor, threshold);
			attempts.recordAdmin(account == null ? null : account.adminId(), idHash,
					account == null ? Result.NOT_FOUND : Result.WRONG_PASSWORD, ipHash, userAgent, null, verifyMs);
			throw new BusinessException(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
		}

		if (!account.active()) {
			attempts.recordAdmin(account.adminId(), idHash, Result.BANNED, ipHash, userAgent, null, verifyMs);
			throw new BusinessException(AuthErrorCode.AUTH_BANNED);
		}

		limiter.clearFailures(idHash);
		if (hasher.needsRehash(account.passwordHash(), activePepper)) {
			accounts.rehash(account.adminId(), account.passwordHash(), hasher.hash(password).hash());
			log.info("관리자 비밀번호 재해싱 admin={}", account.adminId());
		}
		accounts.markLogin(account.adminId(), Timestamp.from(clock.instant()));
		IssuedTokens issued = tokens.issue(ActorType.ADMIN, account.adminId(), new ClientInfo(null, ipHash, userAgent));
		attempts.recordAdmin(account.adminId(), idHash, Result.SUCCESS, ipHash, userAgent, issued.sessionId(), verifyMs);
		return issued;
	}

	/** 앞뒤 공백 제거·소문자. 형식이 틀리면 null (응답은 "아이디 또는 비밀번호가 올바르지 않습니다"로 같게) */
	static String normalizeOrNull(String raw) {
		if (raw == null) {
			return null;
		}
		String v = raw.strip().toLowerCase(Locale.ROOT);
		return LOGIN_ID.matcher(v).matches() ? v : null;
	}
}
