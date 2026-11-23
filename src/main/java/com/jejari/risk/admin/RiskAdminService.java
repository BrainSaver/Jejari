package com.jejari.risk.admin;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jejari.common.pii.Encrypted;
import com.jejari.common.pii.PiiCipher;
import com.jejari.common.pii.PiiField;
import com.jejari.common.pii.PiiMasker;
import com.jejari.risk.PreviousScore.ReviewStatus;
import com.jejari.risk.RiskGrade;
import com.jejari.risk.RiskRule;
import com.jejari.risk.SubjectType;
import com.jejari.risk.batch.RiskInputReader;
import com.jejari.risk.batch.RiskPolicySource;
import com.jejari.risk.model.ModelOpinionService;
import com.jejari.risk.model.ModelOpinionService.ListModel;
import com.jejari.risk.model.ModelOpinionService.SubjectKey;
import com.jejari.risk.model.ModelOpinionService.SubjectOpinions;
import com.jejari.risk.model.ModelOpinionService.SubjectScore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADM-04 위험 사용자 목록 (A-05). 대상(CI·결제 수단·기기)을 점수 순으로, 개인정보는 <b>마스킹해서만</b> 보여 준다.
 *
 * <ul>
 *   <li><b>원문이 응답에 나갈 길이 없다</b>: 전화번호는 복호화한 즉시 {@link PiiMasker}로 가리고 원문은 변수 밖으로 내보내지 않는다.
 *       복호화가 실패하면(키 없음·변조) 오류를 내지 않고 "***"로 보여 준다 (목록 전체가 깨지지 않게, 원인은 로그).</li>
 *   <li>마스킹 해제는 이 목록이 아니라 케이스 상세(ADM-06, SUPERVISOR, 사유·이력)에서만.</li>
 *   <li>대표 계정 = 그 대상에 연결된 가장 오래된 계정. 연결 계정 수는 risk_score에 저장된 값(배치 계산 시점).</li>
 *   <li>모델 보조 의견(RISK-05): 대상에 묶인 계정 중 가장 높은 모델 점수. 규칙 점수와 나란히 보여 주기만 하고 필터·정렬에는 안 쓴다.</li>
 *   <li>페이지는 최대 {@value #MAX_SIZE}건. 한 페이지에서 행마다 몇 번씩 더 읽지만 관리자 화면이라 건수가 작다.</li>
 * </ul>
 */
@Service
public class RiskAdminService {
	private static final Logger log = LoggerFactory.getLogger(RiskAdminService.class);

	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;
	static final String HIDDEN = "***";

	private final JdbcTemplate jdbc;
	private final ObjectProvider<PiiCipher> cipher;
	private final JsonMapper json;
	private final ModelOpinionService models;
	private final RiskPolicySource policies;

	public RiskAdminService(JdbcTemplate jdbc, ObjectProvider<PiiCipher> cipher, JsonMapper json,
			ModelOpinionService models, RiskPolicySource policies) {
		this.jdbc = jdbc;
		this.cipher = cipher;
		this.json = json;
		this.models = models;
		this.policies = policies;
	}

	public record Filter(BigDecimal minScore, BigDecimal maxScore, Set<ReviewStatus> statuses, Set<SubjectType> types,
			int page, int size) {
	}

	/** 화면에 보여 줄 대표 계정 (모두 마스킹) */
	public record Holder(String name, String phone, String email, String cardLast4) {
	}

	public record CaseRef(long caseId, String status, String triggerType, Instant openedAt) {
	}

	/** @param grade 위험 점수 등급 NORMAL / WARNING / DANGER ({@link RiskGrade}, 경고 30·위험 80 기본) */
	public record Row(String subjectType, String subjectHash, BigDecimal finalScore, String grade, String reviewStatus,
			List<String> markers, long linkedAccounts, Holder holder, CaseRef latestCase, Instant calculatedAt,
			SubjectScore model) {
	}

	/** @param model 모델 보조 의견 머리 (사용 중인 버전·문턱, 숨김이면 이유) */
	public record Page(List<Row> items, int page, boolean hasNext, ListModel model) {
	}

	public Page list(Filter f) {
		StringBuilder sql = new StringBuilder("""
				SELECT subject_type, subject_hash, final_score, review_status, reasons, linked_account_cnt, calculated_at
				  FROM risk_score WHERE 1 = 1""");
		List<Object> args = new ArrayList<>();
		if (f.minScore() != null) {
			sql.append(" AND final_score >= ?");
			args.add(f.minScore());
		}
		if (f.maxScore() != null) {
			sql.append(" AND final_score <= ?");
			args.add(f.maxScore());
		}
		inEnum(sql, args, "review_status", f.statuses(), EnumSet.allOf(ReviewStatus.class).size());
		inEnum(sql, args, "subject_type", f.types(), SubjectType.values().length);
		sql.append(" ORDER BY final_score DESC, calculated_at DESC, subject_type, subject_hash")
				.append(" OFFSET ").append((long) f.page() * f.size()).append(" ROWS FETCH NEXT ").append(f.size() + 1)
				.append(" ROWS ONLY");

		record Base(String type, String hash, BigDecimal score, String review, String reasons, long linked,
				Timestamp at) {
		}
		List<Base> rows = jdbc.query(sql.toString(), (rs, i) -> new Base(rs.getString(1), rs.getString(2),
				rs.getBigDecimal(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getTimestamp(7)),
				args.toArray());
		boolean hasNext = rows.size() > f.size();
		List<Base> page = rows.subList(0, Math.min(rows.size(), f.size()));
		SubjectOpinions opinions = models
				.forSubjects(page.stream().map(b -> new SubjectKey(SubjectType.valueOf(b.type()), b.hash())).toList());
		var policy = policies.current();
		List<Row> out = new ArrayList<>();
		for (Base b : page) {
			SubjectType type = SubjectType.valueOf(b.type());
			out.add(new Row(b.type(), b.hash(), b.score(), RiskGrade.of(b.score(), policy).name(), b.review(),
					markers(b.reasons()), b.linked(),
					holder(type, b.hash()), latestCase(type, b.hash()), b.at() == null ? null : b.at().toInstant(),
					opinions.scores().get(new SubjectKey(type, b.hash()))));
		}
		return new Page(out, f.page(), hasNext, opinions.model());
	}

	/** 값이 enum 이름뿐이라 자리표시자로만 붙인다. 전부 고르면 조건 없음 */
	private static void inEnum(StringBuilder sql, List<Object> args, String column, Set<? extends Enum<?>> values,
			int all) {
		if (values == null || values.isEmpty() || values.size() == all) {
			return;
		}
		sql.append(" AND ").append(column).append(" IN (")
				.append(String.join(", ", Collections.nCopies(values.size(), "?"))).append(')');
		values.forEach(v -> args.add(v.name()));
	}

	/** reasons JSON에서 마커 규칙(케이스를 여는 규칙, RiskRule.marker)만 점수 큰 순서 그대로 */
	List<String> markers(String reasons) {
		Set<String> out = new LinkedHashSet<>();
		if (reasons == null || reasons.isBlank()) {
			return List.of();
		}
		try {
			for (JsonNode r : json.readTree(reasons)) {
				String rule = r.path("rule").asString("");
				try {
					if (RiskRule.valueOf(rule).marker()) {
						out.add(rule);
					}
				} catch (IllegalArgumentException ignore) {
					// 모르는 규칙 이름 (이전 버전 기록) 은 건너뜀
				}
			}
		} catch (RuntimeException e) {
			log.warn("risk_score.reasons를 읽지 못함: {}", e.toString());
		}
		return List.copyOf(out);
	}

	private Holder holder(SubjectType type, String hash) {
		List<Holder> rows = jdbc.query("""
				SELECT m.member_id, m.name, m.phone_enc, m.enc_key_id, m.email
				  FROM member m WHERE m.member_id IN (""" + RiskInputReader.membersOf(type) + """
				 ) ORDER BY m.created_at, m.member_id FETCH FIRST 1 ROWS ONLY""",
				(rs, i) -> new Holder(PiiMasker.name(rs.getString(2)),
						maskedPhone(rs.getLong(1), rs.getString(3), rs.getString(4)), PiiMasker.email(rs.getString(5)),
						null),
				hash);
		Holder h = rows.isEmpty() ? new Holder(null, null, null, null) : rows.get(0);
		if (type == SubjectType.PAYMENT) {
			List<String> last4 = jdbc.queryForList(
					"SELECT card_last4 FROM payment_method WHERE method_hash = ? AND card_last4 IS NOT NULL FETCH FIRST 1 ROWS ONLY",
					String.class, hash);
			h = new Holder(h.name(), h.phone(), h.email(), last4.isEmpty() ? null : "****" + last4.get(0));
		}
		return h;
	}

	/** 복호화 → 즉시 마스킹. 실패하면 "***" (원문·암호문 모두 로그에 남기지 않음) */
	private String maskedPhone(long memberId, String cipherText, String keyId) {
		if (cipherText == null) {
			return null;
		}
		PiiCipher c = cipher.getIfAvailable();
		if (c == null) {
			return HIDDEN;
		}
		try {
			return PiiMasker.phone(c.decrypt(new Encrypted(cipherText, keyId), PiiField.MEMBER_PHONE, memberId));
		} catch (RuntimeException e) {
			log.warn("회원 {} 전화번호 복호화 실패 → 가림 ({})", memberId, e.getClass().getSimpleName());
			return HIDDEN;
		}
	}

	private CaseRef latestCase(SubjectType type, String hash) {
		List<CaseRef> rows = jdbc.query("""
				SELECT case_id, status, trigger_type, opened_at FROM suspicious_case
				 WHERE subject_type = ? AND subject_hash = ? ORDER BY opened_at DESC, case_id DESC FETCH FIRST 1 ROWS ONLY""",
				(rs, i) -> new CaseRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()),
				type.name(), hash);
		return rows.isEmpty() ? null : rows.get(0);
	}
}
