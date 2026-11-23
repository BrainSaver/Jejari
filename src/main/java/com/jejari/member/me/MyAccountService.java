package com.jejari.member.me;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.auth.AuthErrorCode;
import com.jejari.common.error.BusinessException;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.common.pii.Encrypted;
import com.jejari.common.pii.PiiCipher;
import com.jejari.common.pii.PiiField;
import com.jejari.common.pii.PiiMasker;
import com.jejari.member.AuthLimiter;
import com.jejari.member.AuthPolicy;
import com.jejari.member.GuestGuard;
import com.jejari.member.MemberAuthEnabled;
import com.jejari.member.password.PasswordHasher;
import com.jejari.member.password.PasswordPolicy;

/**
 * AUTH-06 내 정보 조회·탈퇴 (U-09).
 *
 * <pre>
 * 탈퇴: 비밀번호 재확인 (로그인과 같은 잠금 카운터) → [트랜잭션] 회원 행 잠금 → 진행 중 예약 있으면 거절
 *       → 기다리던 취소표 대기 취소 → 개인정보 지움(이름·이메일·휴대폰) + withdrawn_at → 비밀번호 행 삭제 → 로그인 세션 전부 폐기
 * </pre>
 *
 * <ul>
 *   <li><b>비밀번호 재확인</b>: 액세스 토큰만 훔쳐 남의 계정을 지우지 못하게. 틀리면 <b>로그인과 같은 실패 카운터</b>가 올라
 *       5번이면 잠긴다 → 탈퇴 API로 비밀번호를 무차별 대입하는 우회가 안 된다.</li>
 *   <li><b>진행 중 예약이 있으면 거절</b> (결제 전·결제 중·출발 전 확정): 표와 환불이 주인 없이 남지 않게. 먼저 취소하라고 안내.</li>
 *   <li><b>소프트 삭제 + 개인정보 최소화</b>: 이름·이메일·휴대폰 암호문·해시를 지운다. 이메일 자리는 회원 ID로 만든 값이라
 *       같은 이메일로 다시 가입할 수 있다.</li>
 *   <li><b>CI 해시·기기·결제 수단 해시는 남긴다</b>: 탈퇴 후 재가입으로 위험 점수·매수 제한을 세탁하지 못하게
 *       (ERD: 위험 이력은 보존 기간 후 파기). 위험 점수 배치도 탈퇴 계정을 연결 계정으로 센다.</li>
 * </ul>
 */
@Service
@MemberAuthEnabled
public class MyAccountService {
	private static final Logger log = LoggerFactory.getLogger(MyAccountService.class);

	static final String WITHDRAWN_NAME = "탈퇴회원";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final PiiCipher cipher;
	private final PasswordHasher hasher;
	private final Pseudonymizer pseudonymizer;
	private final AuthLimiter limiter;
	private final AuthPolicy policy;
	private final Clock clock;
	private final GuestGuard guestGuard;

	public MyAccountService(JdbcTemplate jdbc, TransactionTemplate tx, PiiCipher cipher, PasswordHasher hasher,
			Pseudonymizer pseudonymizer, AuthLimiter limiter, AuthPolicy policy, Clock clock, GuestGuard guestGuard) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.cipher = cipher;
		this.hasher = hasher;
		this.pseudonymizer = pseudonymizer;
		this.limiter = limiter;
		this.policy = policy;
		this.clock = clock;
		this.guestGuard = guestGuard;
	}

	// ------------------------------------------------------------ 내 정보

	/** 이름·휴대폰은 마스킹. 이메일은 로그인 ID라 그대로 */
	public record Me(long memberId, String email, String name, String phone, boolean identityVerified, String status,
			Instant joinedAt, boolean guest) {
	}

	public Me me(long memberId) {
		List<Me> rows = jdbc.query("""
				SELECT m.member_id, m.email, m.name, m.phone_enc, m.enc_key_id, m.status, m.created_at,
				       (SELECT COUNT(*) FROM identity_verification v WHERE v.member_id = m.member_id), m.member_type
				  FROM member m WHERE m.member_id = ? AND m.withdrawn_at IS NULL""",
				(rs, i) -> new Me(rs.getLong(1), rs.getString(2), PiiMasker.name(rs.getString(3)),
						maskedPhone(rs.getLong(1), rs.getString(4), rs.getString(5)), rs.getLong(8) > 0, rs.getString(6),
						rs.getTimestamp(7).toInstant(), "GUEST".equals(rs.getString(9))),
				memberId);
		if (rows.isEmpty()) {
			throw new BusinessException(MyAccountErrorCode.MEMBER_NOT_FOUND); // 탈퇴했거나 없는 회원 (토큰만 남은 경우)
		}
		return rows.get(0);
	}

	private String maskedPhone(long memberId, String cipherText, String keyId) {
		if (cipherText == null) {
			return null;
		}
		try {
			return PiiMasker.phone(cipher.decrypt(new Encrypted(cipherText, keyId), PiiField.MEMBER_PHONE, memberId));
		} catch (RuntimeException e) {
			log.warn("회원 {} 전화번호 복호화 실패 ({})", memberId, e.getClass().getSimpleName());
			return "***";
		}
	}

	// ------------------------------------------------------------ 탈퇴

	public void withdraw(long memberId, String password) {
		guestGuard.forbidGuest(memberId); // 게스트는 기한이 지나면 자동으로 지워진다
		verifyPassword(memberId, password);

		Instant now = clock.instant();
		tx.executeWithoutResult(st -> {
			List<Long> locked = jdbc.queryForList(
					"SELECT member_id FROM member WHERE member_id = ? AND withdrawn_at IS NULL FOR UPDATE", Long.class,
					memberId);
			if (locked.isEmpty()) {
				throw new BusinessException(MyAccountErrorCode.MEMBER_NOT_FOUND);
			}
			Integer active = jdbc.queryForObject("""
					SELECT COUNT(*) FROM reservation r
					  LEFT JOIN train_stop d ON d.run_id = r.run_id AND d.station_id = r.from_station_id
					 WHERE r.member_id = ?
					   AND (r.status IN ('HOLD', 'PAYMENT_PENDING')
					        OR (r.status = 'CONFIRMED' AND (d.departure_time IS NULL OR d.departure_time > ?)))""",
					Integer.class, memberId, Timestamp.from(now));
			if (active != null && active > 0) {
				throw new BusinessException(MyAccountErrorCode.WITHDRAW_ACTIVE_RESERVATION);
			}
			jdbc.update("""
					UPDATE waitlist SET status = 'CANCELED', updated_at = SYSTIMESTAMP
					 WHERE member_id = ? AND status = 'WAITING'""", memberId);
			jdbc.update("""
					UPDATE member SET name = ?, email = ?, phone_enc = NULL, phone_hash = NULL, enc_key_id = NULL,
					       withdrawn_at = ?, updated_at = SYSTIMESTAMP
					 WHERE member_id = ? AND withdrawn_at IS NULL""",
					WITHDRAWN_NAME, "withdrawn-" + memberId + "@withdrawn.invalid", Timestamp.from(now), memberId);
			jdbc.update("DELETE FROM member_credential WHERE member_id = ?", memberId);
			jdbc.update("""
					UPDATE auth_session SET revoked_at = SYSTIMESTAMP, revoke_reason = 'LOGOUT', updated_at = SYSTIMESTAMP
					 WHERE member_id = ? AND revoked_at IS NULL""", memberId);
		});
		log.info("회원 {} 탈퇴", memberId);
	}

	// ------------------------------------------------------------ 원본 보기

	/** 비밀번호를 다시 확인한 뒤에만 가리지 않은 이름·휴대폰을 준다 (화면 어깨너머·토큰 탈취 대비) */
	public record Revealed(String name, String phone) {
	}

	public Revealed reveal(long memberId, String password) {
		guestGuard.forbidGuest(memberId);
		verifyPassword(memberId, password);
		List<Revealed> rows = jdbc.query("""
				SELECT m.name, m.phone_enc, m.enc_key_id FROM member m
				 WHERE m.member_id = ? AND m.withdrawn_at IS NULL""",
				(rs, i) -> new Revealed(rs.getString(1), plainPhone(memberId, rs.getString(2), rs.getString(3))), memberId);
		if (rows.isEmpty()) {
			throw new BusinessException(MyAccountErrorCode.MEMBER_NOT_FOUND);
		}
		return rows.get(0);
	}

	private String plainPhone(long memberId, String cipherText, String keyId) {
		if (cipherText == null) {
			return null;
		}
		try {
			return cipher.decrypt(new Encrypted(cipherText, keyId), PiiField.MEMBER_PHONE, memberId);
		} catch (RuntimeException e) {
			log.warn("회원 {} 전화번호 복호화 실패 ({})", memberId, e.getClass().getSimpleName());
			return null;
		}
	}

	// ------------------------------------------------------------ 정보 변경

	/**
	 * 이름·휴대폰·비밀번호 변경. 바꿀 것만 채운다. 모두 현재 비밀번호 재확인이 먼저다.
	 * 이름·휴대폰은 화면에 보이는 연락처일 뿐 본인인증 값(ci_hash)은 그대로라서, 바꿔도 위험 점수·매수 제한의 "같은 사람"은 유지된다.
	 * 비밀번호를 바꾸면 모든 기기의 로그인 세션을 끊는다 (다시 로그인).
	 */
	public record UpdateRequest(String currentPassword, String name, String phone, String newPassword) {
	}

	public record UpdateResult(Me me, boolean reloginRequired) {
	}

	public UpdateResult update(long memberId, UpdateRequest req) {
		guestGuard.forbidGuest(memberId);
		boolean wantName = req.name() != null && !req.name().isBlank();
		boolean wantPhone = req.phone() != null && !req.phone().isBlank();
		boolean wantPassword = req.newPassword() != null && !req.newPassword().isEmpty();
		if (!wantName && !wantPhone && !wantPassword) {
			throw new BusinessException(MyAccountErrorCode.PROFILE_NOTHING_TO_CHANGE);
		}
		// 입력 모양부터 확인한다 (비밀번호 확인 전에 형식 오류를 알려 줘도 새는 정보가 없다)
		String name = wantName ? profileName(req.name()) : null;
		String phone = wantPhone ? profilePhone(req.phone()) : null;
		verifyPassword(memberId, req.currentPassword());
		if (wantPassword) {
			String email = jdbc.queryForObject("SELECT email FROM member WHERE member_id = ? AND withdrawn_at IS NULL",
					String.class, memberId);
			PasswordPolicy.validate(req.newPassword(), email);
			if (req.newPassword().equals(req.currentPassword())) {
				throw new BusinessException(MyAccountErrorCode.PASSWORD_UNCHANGED);
			}
		}
		PasswordHasher.Hashed hashed = wantPassword ? hasher.hash(req.newPassword()) : null; // 느린 해시는 트랜잭션 밖에서
		Encrypted enc = wantPhone ? cipher.encrypt(phone, PiiField.MEMBER_PHONE, memberId) : null;
		String phoneHash = wantPhone ? pseudonymizer.phone(phone) : null;

		Instant now = clock.instant();
		tx.executeWithoutResult(st -> {
			List<Long> locked = jdbc.queryForList(
					"SELECT member_id FROM member WHERE member_id = ? AND withdrawn_at IS NULL FOR UPDATE", Long.class,
					memberId);
			if (locked.isEmpty()) {
				throw new BusinessException(MyAccountErrorCode.MEMBER_NOT_FOUND);
			}
			if (wantName) {
				jdbc.update("UPDATE member SET name = ?, updated_at = SYSTIMESTAMP WHERE member_id = ?", name, memberId);
			}
			if (wantPhone) {
				jdbc.update("""
						UPDATE member SET phone_enc = ?, phone_hash = ?, enc_key_id = ?, updated_at = SYSTIMESTAMP
						 WHERE member_id = ?""", enc.cipherText(), phoneHash, enc.keyId(), memberId);
			}
			if (wantPassword) {
				jdbc.update("""
						UPDATE member_credential SET password_hash = ?, hash_algo = ?, hash_params = ?, pepper_key_id = ?,
						       needs_rehash = 'N', password_changed_at = ?, updated_at = SYSTIMESTAMP
						 WHERE member_id = ?""", hashed.hash(), PasswordHasher.ALGO, hashed.params(), hashed.pepperKeyId(),
						Timestamp.from(now), memberId);
				jdbc.update("""
						UPDATE auth_session SET revoked_at = SYSTIMESTAMP, revoke_reason = 'LOGOUT', updated_at = SYSTIMESTAMP
						 WHERE member_id = ? AND revoked_at IS NULL""", memberId);
			}
		});
		log.info("회원 {} 정보 변경 (이름 {}, 휴대폰 {}, 비밀번호 {})", memberId, wantName, wantPhone, wantPassword);
		return new UpdateResult(me(memberId), wantPassword);
	}

	/** 앞뒤 공백 제거·연속 공백 하나로. 2~50자 (가입 본인인증과 같은 규칙) */
	private static String profileName(String raw) {
		String n = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
		int len = n.codePointCount(0, n.length());
		if (len < 2 || len > 50 || !n.matches("[\\p{L}\\p{M} .'-]+")) {
			throw new BusinessException(MyAccountErrorCode.PROFILE_INVALID, "이름은 2~50자의 글자로 입력해 주세요.");
		}
		return n;
	}

	/** 숫자만 남기고 +82는 0으로. 한국 휴대폰(010·011·016~019) 형식만 */
	private static String profilePhone(String raw) {
		if (!raw.strip().matches("[0-9 ()+\\-]+")) {
			throw new BusinessException(MyAccountErrorCode.PROFILE_INVALID, "휴대폰 번호 형식이 아니에요.");
		}
		String digits = raw.replaceAll("[^0-9]", "");
		if (digits.startsWith("82") && digits.length() >= 11 && digits.length() <= 12) {
			digits = "0" + digits.substring(2);
		}
		if (!digits.matches("01[016789][0-9]{7,8}")) {
			throw new BusinessException(MyAccountErrorCode.PROFILE_INVALID, "휴대폰 번호 형식이 아니에요. (예: 010-1234-5678)");
		}
		return digits;
	}

	/**
	 * 비밀번호 재확인 (탈퇴·원본 보기·정보 변경 공통). 틀리면 <b>로그인과 같은 실패 카운터</b>가 올라 5번이면 잠긴다
	 * → 이 API들로 비밀번호를 무차별 대입하는 우회가 안 된다. 맞으면 카운터를 지운다.
	 */
	private void verifyPassword(long memberId, String password) {
		Map<String, Object> acct = credential(memberId);
		String emailHash = pseudonymizer.email((String) acct.get("EMAIL"));
		long threshold = policy.number(AuthPolicy.LOCK_AFTER_FAILURES);
		Duration lockFor = Duration.ofMinutes(policy.number(AuthPolicy.LOCK_MINUTES));
		if (limiter.failures(emailHash) >= threshold) {
			throw new BusinessException(AuthErrorCode.AUTH_LOCKED);
		}
		boolean usable = password != null && !password.isEmpty() && password.length() <= PasswordPolicy.MAX_LENGTH * 2;
		boolean ok = usable && acct.get("PASSWORD_HASH") != null
				&& hasher.verify(password, (String) acct.get("PASSWORD_HASH"), (String) acct.get("PEPPER_KEY_ID"));
		if (!ok) {
			if (!usable) {
				hasher.dummyVerify(""); // 같은 비용 (응답 시간으로 구분되지 않게)
			}
			limiter.recordFailure(emailHash, lockFor, threshold);
			throw new BusinessException(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
		}
		limiter.clearFailures(emailHash);
	}

	private Map<String, Object> credential(long memberId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT m.email, c.password_hash, c.pepper_key_id FROM member m
				  LEFT JOIN member_credential c ON c.member_id = m.member_id
				 WHERE m.member_id = ? AND m.withdrawn_at IS NULL""", memberId);
		if (rows.isEmpty()) {
			throw new BusinessException(MyAccountErrorCode.MEMBER_NOT_FOUND);
		}
		return rows.get(0);
	}
}
