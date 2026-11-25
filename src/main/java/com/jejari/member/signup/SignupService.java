package com.jejari.member.signup;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.auth.AuthErrorCode;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.common.pii.Encrypted;
import com.jejari.common.pii.PiiCipher;
import com.jejari.common.pii.PiiField;
import com.jejari.common.pii.PiiMasker;
import com.jejari.member.AuthLimiter;
import com.jejari.member.AuthPolicy;
import com.jejari.member.MemberAuthEnabled;
import com.jejari.member.MemberStatusGuard;
import com.jejari.member.password.PasswordHasher;
import com.jejari.member.password.PasswordHasher.Hashed;
import com.jejari.member.password.PasswordPolicy;
import com.jejari.member.verify.MockIdentityProvider;
import com.jejari.member.verify.MockIdentityProvider.Provider;
import com.jejari.member.verify.VerificationTokens;
import com.jejari.member.verify.VerifiedIdentity;

/**
 * AUTH-01·02 본인인증 + 회원가입.
 *
 * <pre>
 * verify : IP 제한 → 모의 본인인증 → 1회용 토큰 발급
 * signup : IP 제한 → 이메일·비밀번호 규칙 → 토큰 확인(peek) → 정지 CI 거절 → Argon2id 해싱 → 토큰 소비(GETDEL)
 *          → 한 트랜잭션: member + member_credential + identity_verification
 * </pre>
 * 토큰 소비를 해싱 뒤에 두어, 비밀번호 규칙 위반 같은 입력 실수로 토큰을 잃지 않게 한다. 소비는 원자적이라
 * 같은 토큰으로 동시에 두 번 가입해도 하나만 성공한다.
 */
@Service
@MemberAuthEnabled
public class SignupService {
	private static final Logger log = LoggerFactory.getLogger(SignupService.class);

	/** 실무에서 쓰는 수준의 보수적 형식 (따옴표·주석·IP 리터럴 등 RFC의 특이한 형식은 받지 않음) */
	private static final Pattern EMAIL = Pattern.compile(
			"^[a-z0-9](?:[a-z0-9._%+-]{0,62}[a-z0-9_%+-])?@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}$");

	private final MockIdentityProvider identity;
	private final VerificationTokens tokens;
	private final PasswordHasher hasher;
	private final PiiCipher cipher;
	private final Pseudonymizer pseudonymizer;
	private final AuthLimiter limiter;
	private final AuthPolicy policy;
	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final MemberStatusGuard statusGuard;

	public SignupService(MockIdentityProvider identity, VerificationTokens tokens, PasswordHasher hasher,
			PiiCipher cipher, Pseudonymizer pseudonymizer, AuthLimiter limiter, AuthPolicy policy, JdbcTemplate jdbc,
			TransactionTemplate tx, Clock clock, MemberStatusGuard statusGuard) {
		this.identity = identity;
		this.tokens = tokens;
		this.hasher = hasher;
		this.cipher = cipher;
		this.pseudonymizer = pseudonymizer;
		this.limiter = limiter;
		this.policy = policy;
		this.jdbc = jdbc;
		this.tx = tx;
		this.clock = clock;
		this.statusGuard = statusGuard;
	}

	public VerificationTokens.Issued verify(String name, String phone, Provider provider, String ipHash) {
		checkSignupRate(ipHash);
		VerifiedIdentity id;
		try {
			id = identity.verify(name, phone, provider);
		} catch (BusinessException e) {
			log.info("본인인증 실패 ip={}", shortHash(ipHash)); // 이름·번호는 남기지 않는다
			throw e;
		}
		return tokens.issue(id);
	}

	/** @return 마스킹한 이메일 (응답용) */
	public String signup(String verificationToken, String rawEmail, String password, String ipHash) {
		checkSignupRate(ipHash);
		String email = normalizeEmail(rawEmail);
		PasswordPolicy.validate(password, email);
		// 정지된 명의(CI)는 다른 이메일로도 다시 가입할 수 없다. 비회원 입장(GuestService.checkAllowed)과 같은 기준.
		// 토큰을 쓰기 전에 거절한다 (보안 점검 2026-11-25 §2.1)
		statusGuard.forbidBannedCi(tokens.peek(verificationToken).ciHash());
		// 이미 가입된 이메일이면 토큰을 쓰기 전에 거절한다. 토큰을 먼저 써 버리면 이메일만 바꿔 다시 눌러도
		// "본인인증 실패"가 나와 처음부터 다시 해야 한다. 최종 판정은 아래 UNIQUE 위반 (동시 가입 두 건)
		Integer taken = jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE email = ?", Integer.class, email);
		if (taken != null && taken > 0) {
			throw new BusinessException(AuthErrorCode.AUTH_EMAIL_DUPLICATED);
		}
		Hashed hashed = hasher.hash(password);
		VerifiedIdentity id = tokens.consume(verificationToken);

		try {
			tx.executeWithoutResult(st -> insert(id, email, hashed));
		} catch (DuplicateKeyException e) {
			// 미리 조회하지 않고 UNIQUE 위반으로 판단한다 (동시 가입 두 건이 둘 다 통과하는 틈이 없음)
			throw new BusinessException(AuthErrorCode.AUTH_EMAIL_DUPLICATED);
		} catch (RuntimeException e) {
			// 저장 실패(가입 몰림에 DB 연결을 못 얻어 503 등) → 트랜잭션이 롤백돼 회원이 없으니 토큰을 되돌린다.
			// 안 그러면 503의 "잠시 후 다시 시도"를 해도 토큰이 이미 지워져 본인인증부터 다시 해야 한다 (11-23)
			try {
				tokens.restore(verificationToken, id);
			} catch (RuntimeException restoreFailed) {
				e.addSuppressed(restoreFailed);
			}
			throw e;
		}
		return PiiMasker.email(email);
	}

	private void insert(VerifiedIdentity id, String email, Hashed hashed) {
		// 위 확인과 저장 사이에 정지된 경우까지 트랜잭션 안에서 한 번 더 본다
		statusGuard.forbidBannedCi(id.ciHash());
		// 같은 CI로 비회원 예매를 했던 사람이 가입하면 그 게스트 행을 회원으로 바꾼다 (ERD v33).
		// 새 행을 만들면 같은 CI에 계정이 둘이라 위험 점수가 다계정으로 보고, 예매 이력도 끊긴다
		List<Long> guest = jdbc.queryForList(
				"SELECT member_id FROM member WHERE ci_hash = ? AND member_type = 'GUEST' ORDER BY member_id FOR UPDATE",
				Long.class, id.ciHash());
		boolean converted = !guest.isEmpty();
		long memberId = converted ? guest.get(0) : nextId("member_seq");
		Encrypted phone = cipher.encrypt(id.phone(), PiiField.MEMBER_PHONE, memberId);
		Timestamp now = Timestamp.from(clock.instant());
		if (converted) {
			jdbc.update("""
					UPDATE member SET email = ?, name = ?, phone_enc = ?, phone_hash = ?, enc_key_id = ?,
					       member_type = NULL, guest_expires_at = NULL, withdrawn_at = NULL, updated_at = SYSTIMESTAMP
					 WHERE member_id = ?""",
					email, id.name(), phone.cipherText(), pseudonymizer.phone(id.phone()), phone.keyId(), memberId);
		} else {
			jdbc.update("""
					INSERT INTO member (member_id, email, ci_hash, name, phone_enc, phone_hash, enc_key_id, status)
					VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')""",
					memberId, email, id.ciHash(), id.name(), phone.cipherText(), pseudonymizer.phone(id.phone()),
					phone.keyId());
		}
		jdbc.update("""
				INSERT INTO member_credential (member_id, password_hash, hash_algo, hash_params, pepper_key_id,
				    needs_rehash, failed_count, password_changed_at)
				VALUES (?, ?, ?, ?, ?, 'N', 0, ?)""",
				memberId, hashed.hash(), PasswordHasher.ALGO, hashed.params(), hashed.pepperKeyId(), now);
		jdbc.update("""
				INSERT INTO identity_verification (verification_id, member_id, ci_hash, provider, result, verified_at)
				VALUES (?, ?, ?, ?, 'SUCCESS', ?)""",
				nextId("identity_verification_seq"), memberId, id.ciHash(), id.provider().name(),
				Timestamp.from(id.verifiedAt()));
	}

	private long nextId(String sequence) {
		Long id = jdbc.queryForObject("SELECT " + sequence + ".NEXTVAL FROM dual", Long.class);
		if (id == null) {
			throw new IllegalStateException("시퀀스 값 없음: " + sequence);
		}
		return id;
	}

	private void checkSignupRate(String ipHash) {
		long n = limiter.hit(AuthLimiter.signupRateKey(ipHash), Duration.ofHours(1));
		if (n > policy.limitFor(AuthPolicy.SIGNUP_RATE_PER_HOUR, ipHash)) { // 임시 해제 IP면 해제 한도 (ERD v39)
			throw new BusinessException(CommonErrorCode.RATE_LIMITED);
		}
	}

	/** 앞뒤 공백 제거 + 소문자. 형식이 아니면 거절 (메시지에 입력값을 싣지 않음) */
	public static String normalizeEmail(String raw) {
		if (raw == null) {
			throw invalidEmail();
		}
		String e = raw.strip().toLowerCase(Locale.ROOT);
		if (e.length() > 100 || !EMAIL.matcher(e).matches() || e.contains("..")) {
			throw invalidEmail();
		}
		return e;
	}

	private static BusinessException invalidEmail() {
		return new BusinessException(CommonErrorCode.INVALID_REQUEST, "이메일 형식이 올바르지 않습니다.");
	}

	static String shortHash(String h) {
		return h == null ? "-" : h.substring(0, Math.min(12, h.length()));
	}
}
