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

	public MyAccountService(JdbcTemplate jdbc, TransactionTemplate tx, PiiCipher cipher, PasswordHasher hasher,
			Pseudonymizer pseudonymizer, AuthLimiter limiter, AuthPolicy policy, Clock clock) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.cipher = cipher;
		this.hasher = hasher;
		this.pseudonymizer = pseudonymizer;
		this.limiter = limiter;
		this.policy = policy;
		this.clock = clock;
	}

	// ------------------------------------------------------------ 내 정보

	/** 이름·휴대폰은 마스킹. 이메일은 로그인 ID라 그대로 */
	public record Me(long memberId, String email, String name, String phone, boolean identityVerified, String status,
			Instant joinedAt) {
	}

	public Me me(long memberId) {
		List<Me> rows = jdbc.query("""
				SELECT m.member_id, m.email, m.name, m.phone_enc, m.enc_key_id, m.status, m.created_at,
				       (SELECT COUNT(*) FROM identity_verification v WHERE v.member_id = m.member_id)
				  FROM member m WHERE m.member_id = ? AND m.withdrawn_at IS NULL""",
				(rs, i) -> new Me(rs.getLong(1), rs.getString(2), PiiMasker.name(rs.getString(3)),
						maskedPhone(rs.getLong(1), rs.getString(4), rs.getString(5)), rs.getLong(8) > 0, rs.getString(6),
						rs.getTimestamp(7).toInstant()),
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
