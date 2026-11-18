package com.jejari.risk.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.common.pii.Encrypted;
import com.jejari.common.pii.PiiCipher;
import com.jejari.common.pii.PiiField;
import com.jejari.risk.SubjectType;
import com.jejari.risk.batch.RiskInputReader;
import com.jejari.risk.store.RiskScoreStore;

/**
 * ADM-06 판정·조치, 마스킹 해제 (A-06).
 *
 * <pre>
 * 판정 NORMAL(오탐)          + CLEAR   → 점수 0·CLEARED, 케이스 DISMISSED, RESTRICTED였던 연결 계정은 ACTIVE로
 * 판정 RESELLER·MACRO·CANCEL_ABUSE + CONFIRM → 점수 CONFIRMED, 케이스 CONFIRMED
 *                                   + RESTRICT → 위 + 연결 계정 ACTIVE → RESTRICTED (예매는 되지만 대기열 최대 패널티, 취소표 불가)
 *                                   + BAN      → 위 + 연결 계정 BANNED, 로그인 세션 폐기
 * 한 트랜잭션: 케이스 잠금 → admin_action → 케이스 → risk_score(+이력) → member.status(·세션) → 커밋 뒤 risk:* 캐시 즉시 갱신
 * </pre>
 *
 * <ul>
 *   <li><b>사유 필수</b>: 감사 추적 (admin_action.memo). 판정과 맞지 않는 조치(NORMAL인데 BAN 등)는 거절.</li>
 *   <li><b>CLEAR 시각 = admin_action.acted_at</b>: 1분 배치는 이 시각 이후 증거만 다시 센다 ({@code RiskInputReader.stored}).
 *       그래서 해제한 오탐이 같은 증거로 다음 배치에 되살아나지 않는다.</li>
 *   <li><b>BANNED는 복구하지 않는다</b>: 오탐 해제가 정지까지 자동으로 풀면, 다른 케이스로 정지된 계정이 풀릴 수 있다.</li>
 *   <li><b>마스킹 해제는 기록이 먼저</b>: UNMASK 기록을 같은 트랜잭션에 INSERT한 뒤에만 원문을 읽는다. 기록이 실패하면 원문도 없다.</li>
 * </ul>
 */
@Service
public class CaseActionService {
	private static final Logger log = LoggerFactory.getLogger(CaseActionService.class);

	static final Set<String> VERDICTS = Set.of("RESELLER", "MACRO", "CANCEL_ABUSE", "NORMAL");
	static final Set<String> ACTIONS = Set.of("CONFIRM", "CLEAR", "RESTRICT", "BAN");
	static final int MEMO_MIN = 5;
	static final int MEMO_MAX = 500;

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final RiskScoreStore scores;
	private final ObjectProvider<PiiCipher> cipher;
	private final Clock clock;

	public CaseActionService(JdbcTemplate jdbc, TransactionTemplate tx, RiskScoreStore scores,
			ObjectProvider<PiiCipher> cipher, Clock clock) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.scores = scores;
		this.cipher = cipher;
		this.clock = clock;
	}

	public record ActionCommand(String verdict, String action, String memo) {
	}

	public record ActionResult(long caseId, String caseStatus, String verdict, String action, long actionId,
			int membersChanged) {
	}

	private record Subject(SubjectType type, String hash) {
	}

	// ------------------------------------------------------------ 판정·조치

	public ActionResult act(long adminId, long caseId, ActionCommand cmd) {
		String verdict = upper(cmd == null ? null : cmd.verdict());
		String action = upper(cmd == null ? null : cmd.action());
		if (!VERDICTS.contains(verdict) || !ACTIONS.contains(action)) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		String memo = memo(cmd.memo());
		if ("NORMAL".equals(verdict) != "CLEAR".equals(action)) {
			throw new BusinessException(RiskAdminErrorCode.ACTION_NOT_ALLOWED); // 오탐은 해제만, 해제는 오탐일 때만
		}

		Instant now = clock.instant();
		ActionResult result = tx.execute(st -> {
			Subject s = lockOpenCase(caseId);
			long actionId = adminAction(adminId, s, action, memo, now);
			String caseStatus = "NORMAL".equals(verdict) ? "DISMISSED" : "CONFIRMED";
			jdbc.update("""
					UPDATE suspicious_case SET status = ?, verdict = ?, assignee_id = ?, admin_action_id = ?,
					       closed_at = ?, updated_at = SYSTIMESTAMP
					 WHERE case_id = ? AND status IN ('OPEN', 'REVIEWING')""",
					caseStatus, verdict, adminId, actionId, Timestamp.from(now), caseId);

			int changed;
			if ("CLEAR".equals(action)) {
				clearScore(s, now);
				changed = memberStatus(s, "RESTRICTED", "ACTIVE"); // 오탐으로 제한된 계정 복구 (BANNED는 그대로)
			} else {
				jdbc.update("""
						UPDATE risk_score SET review_status = 'CONFIRMED', updated_at = SYSTIMESTAMP
						 WHERE subject_type = ? AND subject_hash = ?""", s.type().name(), s.hash());
				changed = switch (action) {
					case "RESTRICT" -> memberStatus(s, "ACTIVE", "RESTRICTED");
					case "BAN" -> ban(s);
					default -> 0;
				};
			}
			scores.refreshCache(s.type(), s.hash()); // 커밋 뒤 Redis risk:* 를 Oracle 값으로 (재동기화 10분을 기다리지 않음)
			return new ActionResult(caseId, caseStatus, verdict, action, actionId, changed);
		});
		log.info("케이스 {} 판정 {} 조치 {} (관리자 {}, 계정 {}개 상태 변경)", caseId, verdict, action, adminId,
				result.membersChanged());
		return result;
	}

	/** 열린 케이스만 잠근다. 없으면 404, 이미 닫혔으면 409 */
	private Subject lockOpenCase(long caseId) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT subject_type, subject_hash, status FROM suspicious_case WHERE case_id = ? FOR UPDATE", caseId);
		if (rows.isEmpty()) {
			throw new BusinessException(RiskAdminErrorCode.CASE_NOT_FOUND);
		}
		Map<String, Object> r = rows.get(0);
		String status = (String) r.get("STATUS");
		if (!"OPEN".equals(status) && !"REVIEWING".equals(status)) {
			throw new BusinessException(RiskAdminErrorCode.CASE_CLOSED);
		}
		return new Subject(SubjectType.valueOf((String) r.get("SUBJECT_TYPE")), (String) r.get("SUBJECT_HASH"));
	}

	private long adminAction(long adminId, Subject s, String action, String memo, Instant at) {
		long id = jdbc.queryForObject("SELECT admin_action_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO admin_action (action_id, admin_id, subject_type, subject_hash, action_type, memo, acted_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)""", id, adminId, s.type().name(), s.hash(), action, memo, Timestamp.from(at));
		return id;
	}

	/** 점수 0·CLEARED + 이력. calculated_at = CLEAR 시각 (배치가 이 뒤 증거만 셈) */
	private void clearScore(Subject s, Instant at) {
		int n = jdbc.update("""
				UPDATE risk_score SET final_score = 0, rule_score = 0, reasons = '[]', review_status = 'CLEARED',
				       calculated_at = ?, updated_at = SYSTIMESTAMP
				 WHERE subject_type = ? AND subject_hash = ?""", Timestamp.from(at), s.type().name(), s.hash());
		if (n == 1) {
			jdbc.update("""
					INSERT INTO risk_score_history (history_id, subject_type, subject_hash, final_score, reasons, calculated_at)
					VALUES (risk_score_history_seq.NEXTVAL, ?, ?, 0, '[]', ?)""", s.type().name(), s.hash(), Timestamp.from(at));
		}
	}

	/** 연결 계정 중 from 상태인 것만 to로 (탈퇴 계정 제외) */
	private int memberStatus(Subject s, String from, String to) {
		return jdbc.update("UPDATE member SET status = ?, updated_at = SYSTIMESTAMP WHERE status = ? AND withdrawn_at IS NULL"
				+ " AND member_id IN (" + RiskInputReader.membersOf(s.type()) + ")", to, from, s.hash());
	}

	/** 정지 + 살아 있는 로그인 세션 폐기 (액세스 토큰은 만료(15분)까지 남는다) */
	private int ban(Subject s) {
		int n = jdbc.update("UPDATE member SET status = 'BANNED', updated_at = SYSTIMESTAMP WHERE status <> 'BANNED'"
				+ " AND withdrawn_at IS NULL AND member_id IN (" + RiskInputReader.membersOf(s.type()) + ")", s.hash());
		jdbc.update("""
				UPDATE auth_session SET revoked_at = SYSTIMESTAMP, revoke_reason = 'ADMIN', updated_at = SYSTIMESTAMP
				 WHERE revoked_at IS NULL AND actor_type = 'MEMBER' AND member_id IN (""" + RiskInputReader.membersOf(s.type()) + ")",
				s.hash());
		return n;
	}

	// ------------------------------------------------------------ 마스킹 해제

	public record UnmaskedAccount(long memberId, String name, String phone, String email, String status) {
	}

	public record Unmasked(long caseId, long actionId, List<UnmaskedAccount> accounts) {
	}

	/** SUPERVISOR만 (컨트롤러). 기록(UNMASK)을 먼저 남기고, 같은 트랜잭션에서 원문을 읽는다 */
	public Unmasked unmask(long adminId, long caseId, String rawMemo) {
		String memo = memo(rawMemo);
		Instant now = clock.instant();
		return tx.execute(st -> {
			List<Map<String, Object>> rows = jdbc.queryForList(
					"SELECT subject_type, subject_hash FROM suspicious_case WHERE case_id = ?", caseId);
			if (rows.isEmpty()) {
				throw new BusinessException(RiskAdminErrorCode.CASE_NOT_FOUND);
			}
			Subject s = new Subject(SubjectType.valueOf((String) rows.get(0).get("SUBJECT_TYPE")),
					(String) rows.get(0).get("SUBJECT_HASH"));
			long actionId = adminAction(adminId, s, "UNMASK", memo, now);
			PiiCipher c = cipher.getIfAvailable();
			List<UnmaskedAccount> out = new ArrayList<>();
			jdbc.query("""
					SELECT member_id, name, phone_enc, enc_key_id, email, status FROM member
					 WHERE member_id IN (""" + RiskInputReader.membersOf(s.type()) + """
					 ) ORDER BY created_at, member_id FETCH FIRST %d ROWS ONLY""".formatted(CaseAdminService.MAX_ACCOUNTS),
					rs -> {
						long id = rs.getLong(1);
						out.add(new UnmaskedAccount(id, rs.getString(2), phone(c, id, rs.getString(3), rs.getString(4)),
								rs.getString(5), rs.getString(6)));
					}, s.hash());
			log.info("관리자 {} 케이스 {} 마스킹 해제 (계정 {}개, 기록 {})", adminId, caseId, out.size(), actionId);
			return new Unmasked(caseId, actionId, out);
		});
	}

	private static String phone(PiiCipher c, long memberId, String cipherText, String keyId) {
		if (cipherText == null) {
			return null;
		}
		if (c == null) {
			return RiskAdminService.HIDDEN;
		}
		try {
			return c.decrypt(new Encrypted(cipherText, keyId), PiiField.MEMBER_PHONE, memberId);
		} catch (RuntimeException e) {
			log.warn("회원 {} 전화번호 복호화 실패 ({})", memberId, e.getClass().getSimpleName());
			return RiskAdminService.HIDDEN;
		}
	}

	// ------------------------------------------------------------ 입력

	private static String upper(String s) {
		return s == null ? null : s.strip().toUpperCase(java.util.Locale.ROOT);
	}

	/** 사유: 앞뒤 공백 제거 후 5~500자, 제어 문자 제거 (로그·화면 위조 방지). 예약 강제 취소(ADM-07)도 같은 규칙 */
	public static String memo(String raw) {
		String m = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ").strip();
		if (m.codePointCount(0, m.length()) < MEMO_MIN) {
			throw new BusinessException(RiskAdminErrorCode.ACTION_REASON_REQUIRED);
		}
		if (m.codePointCount(0, m.length()) > MEMO_MAX || m.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 500) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "사유는 500바이트 이내로 입력해 주세요.");
		}
		return m;
	}
}
