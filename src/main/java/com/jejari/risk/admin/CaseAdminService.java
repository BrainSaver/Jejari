package com.jejari.risk.admin;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.common.pii.PiiMasker;
import com.jejari.risk.SubjectType;
import com.jejari.risk.batch.RiskInputReader;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;

/**
 * ADM-05 의심 케이스 목록·상세·타임라인 (A-06). 읽기 전용, 관리자 VIEWER 이상.
 *
 * <ul>
 *   <li><b>같은 대상의 모든 계정</b>: 타임라인은 케이스 대상(CI·결제 수단·기기)에 연결된 계정 전부의 행동 로그를 한 줄로 합친다.
 *       다계정 봇이 계정을 바꿔 가며 같은 좌석을 노리는 모습이 한 화면에 보여야 하기 때문이다 (기획서 차별점).</li>
 *   <li><b>계정은 "계정 1, 2 …"와 마스킹 이름</b>으로만 구분한다. 이메일·전화는 내보내지 않는다.</li>
 *   <li><b>범위 제한</b>: 기본은 케이스의 의심 구간. 넓혀도 {@value #MAX_SPAN_HOURS}시간, {@value #MAX_EVENTS}건까지.
 *       관리자 요청 하나가 MongoDB를 오래 붙잡지 않게. 잘리면 {@code truncated = true}.</li>
 *   <li>행동 로그 조회는 {member_id, ts} 인덱스(ADM-05용, ERD)를 탄다.</li>
 * </ul>
 */
@Service
public class CaseAdminService {

	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;
	static final int MAX_SPAN_HOURS = 24;
	static final int MAX_EVENTS = 2000;
	static final int MAX_ACCOUNTS = 50;
	static final int MAX_HISTORY = 200;

	private final JdbcTemplate jdbc;
	private final MongoTemplate mongo;

	public CaseAdminService(JdbcTemplate jdbc, MongoTemplate mongo) {
		this.jdbc = jdbc;
		this.mongo = mongo;
	}

	// ------------------------------------------------------------ 목록

	public record CaseSummary(long caseId, String subjectType, String subjectHash, String triggerType,
			String triggerDetail, BigDecimal scoreAtOpen, BigDecimal currentScore, String reviewStatus, String status,
			String verdict, Instant openedAt, Instant closedAt) {
	}

	public record CasePage(List<CaseSummary> items, int page, boolean hasNext) {
	}

	private static final String CASE_SELECT = """
			SELECT c.case_id, c.subject_type, c.subject_hash, c.trigger_type, c.trigger_detail, c.risk_score_at_open,
			       s.final_score, s.review_status, c.status, c.verdict, c.opened_at, c.closed_at, c.window_from, c.window_to
			  FROM suspicious_case c
			  LEFT JOIN risk_score s ON s.subject_type = c.subject_type AND s.subject_hash = c.subject_hash""";

	public CasePage list(Set<String> statuses, int page, int size) {
		StringBuilder sql = new StringBuilder(CASE_SELECT).append(" WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (statuses != null && !statuses.isEmpty()) {
			sql.append(" AND c.status IN (").append(String.join(", ", Collections.nCopies(statuses.size(), "?")))
					.append(')');
			args.addAll(statuses);
		}
		sql.append(" ORDER BY c.opened_at DESC, c.case_id DESC OFFSET ").append((long) page * size)
				.append(" ROWS FETCH NEXT ").append(size + 1).append(" ROWS ONLY");
		List<CaseSummary> rows = jdbc.query(sql.toString(), (rs, i) -> summary(rs), args.toArray());
		boolean hasNext = rows.size() > size;
		return new CasePage(List.copyOf(rows.subList(0, Math.min(size, rows.size()))), page, hasNext);
	}

	private static CaseSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new CaseSummary(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
				rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getString(8), rs.getString(9), rs.getString(10),
				instant(rs.getTimestamp(11)), instant(rs.getTimestamp(12)));
	}

	private static Instant instant(Timestamp t) {
		return t == null ? null : t.toInstant();
	}

	// ------------------------------------------------------------ 상세

	public record Marker(long markerId, String ruleCode, String label, String severity, String markerType,
			Instant at, String sessionId) {
	}

	/**
	 * 연결 계정. label은 화면 표시용 "계정 1"…, 이름은 마스킹.
	 *
	 * @param linkReason 왜 묶였나: 케이스를 열 때 박아 둔 {@code case_session.link_reason} 중 가장 강한 것
	 *                   (TRIGGER &gt; SAME_CI·SAME_DEVICE·SAME_PAYMENT). 열 때는 없었고 지금 연결돼 있으면 {@code AFTER_OPEN}
	 */
	public record Account(String label, long memberId, String maskedName, String status, Instant createdAt,
			String linkReason) {
	}

	public record ScorePoint(Instant at, BigDecimal score) {
	}

	public record CaseDetail(CaseSummary summary, Instant windowFrom, Instant windowTo, String reasons,
			List<Marker> markers, List<Account> accounts, List<ScorePoint> scoreHistory) {
	}

	private record CaseRow(CaseSummary summary, Instant windowFrom, Instant windowTo) {
	}

	private CaseRow load(long caseId) {
		List<CaseRow> rows = jdbc.query(CASE_SELECT + " WHERE c.case_id = ?",
				(rs, i) -> new CaseRow(summary(rs), instant(rs.getTimestamp(13)), instant(rs.getTimestamp(14))), caseId);
		if (rows.isEmpty()) {
			throw new BusinessException(RiskAdminErrorCode.CASE_NOT_FOUND);
		}
		return rows.get(0);
	}

	public CaseDetail detail(long caseId) {
		CaseRow c = load(caseId);
		SubjectType type = SubjectType.valueOf(c.summary().subjectType());
		String hash = c.summary().subjectHash();
		List<String> reasons = jdbc.queryForList(
				"SELECT reasons FROM risk_score WHERE subject_type = ? AND subject_hash = ?", String.class, type.name(), hash);
		List<Marker> markers = detailMarkers(caseId);
		List<ScorePoint> history = jdbc.query("""
				SELECT calculated_at, final_score FROM (
				  SELECT calculated_at, final_score FROM risk_score_history
				   WHERE subject_type = ? AND subject_hash = ? ORDER BY calculated_at DESC FETCH FIRST %d ROWS ONLY)
				 ORDER BY calculated_at""".formatted(MAX_HISTORY),
				(rs, i) -> new ScorePoint(instant(rs.getTimestamp(1)), rs.getBigDecimal(2)), type.name(), hash);
		return new CaseDetail(c.summary(), c.windowFrom(), c.windowTo(), reasons.isEmpty() ? null : reasons.get(0),
				markers, accounts(caseId, type, hash), history);
	}

	/**
	 * 케이스의 연결 계정 (가입 순) = 열 때 묶어 둔 계정(case_session, 조사 시점 보존) ∪ 지금 연결된 계정
	 * (위험 점수 배치와 같은 기준 RiskInputReader.membersOf — 연 뒤에 추가된 계정도 보이게).
	 */
	/** 케이스의 연결 계정 (없는 케이스면 CASE_NOT_FOUND). 모델 보조 의견이 같은 계정·같은 번호(계정 1…)를 쓰게 */
	public List<Account> caseAccounts(long caseId) {
		CaseRow c = load(caseId);
		return accounts(caseId, SubjectType.valueOf(c.summary().subjectType()), c.summary().subjectHash());
	}

	List<Account> accounts(long caseId, SubjectType type, String hash) {
		Map<Long, String> reasonOf = new java.util.HashMap<>();
		jdbc.query("SELECT member_id, link_reason FROM case_session WHERE case_id = ?", rs -> {
			reasonOf.merge(rs.getLong(1), rs.getString(2), (a, b) -> "TRIGGER".equals(a) ? a : b);
		}, caseId);
		List<Account> out = new ArrayList<>();
		String snapshot = reasonOf.isEmpty() ? "" : " OR member_id IN (SELECT member_id FROM case_session WHERE case_id = ?)";
		Object[] args = reasonOf.isEmpty() ? new Object[] { hash } : new Object[] { hash, caseId };
		jdbc.query("""
				SELECT member_id, name, status, created_at FROM member
				 WHERE member_id IN (""" + RiskInputReader.membersOf(type) + ")" + snapshot + """
				 ORDER BY created_at, member_id FETCH FIRST %d ROWS ONLY""".formatted(MAX_ACCOUNTS),
				rs -> {
					long id = rs.getLong(1);
					out.add(new Account("계정 " + (out.size() + 1), id, PiiMasker.name(rs.getString(2)),
							rs.getString(3), instant(rs.getTimestamp(4)), reasonOf.getOrDefault(id, "AFTER_OPEN")));
				}, args);
		return out;
	}

	// ------------------------------------------------------------ 타임라인

	/**
	 * @param inWindow 의심 구간 안이면 true (화면 하이라이트)
	 * @param meta     좌석 ID·오류 코드 같은 이벤트별 값 (적재 때 이미 거른 값)
	 */
	public record Event(Instant ts, String account, long memberId, String eventType, Integer responseStatus,
			Long intervalMs, Long runId, boolean inWindow, Map<String, Object> meta) {
	}

	public record Timeline(long caseId, Instant from, Instant to, Instant windowFrom, Instant windowTo,
			List<Account> accounts, List<Event> events, List<Marker> markers, boolean truncated) {
	}

	public Timeline timeline(long caseId, Instant from, Instant to) {
		CaseRow c = load(caseId);
		SubjectType type = SubjectType.valueOf(c.summary().subjectType());
		Instant wf = c.windowFrom() != null ? c.windowFrom() : c.summary().openedAt().minus(Duration.ofHours(1));
		Instant wt = c.windowTo() != null ? c.windowTo() : c.summary().openedAt();
		Instant f = from != null ? from : wf;
		Instant t = to != null ? to : wt;
		if (!f.isBefore(t) || Duration.between(f, t).compareTo(Duration.ofHours(MAX_SPAN_HOURS)) > 0) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "조회 범위는 " + MAX_SPAN_HOURS + "시간 이내여야 합니다.");
		}

		List<Account> accounts = accounts(caseId, type, c.summary().subjectHash());
		Map<Long, String> labelOf = new HashMap<>();
		accounts.forEach(a -> labelOf.put(a.memberId(), a.label()));

		List<Event> events = new ArrayList<>();
		boolean truncated = false;
		if (!accounts.isEmpty()) {
			Bson filter = Filters.and(Filters.in("member_id", new ArrayList<>(labelOf.keySet())),
					Filters.gte("ts", Date.from(f)), Filters.lte("ts", Date.from(t)));
			try (MongoCursor<Document> cur = mongo.getCollection("behavior_log").find(filter)
					.projection(Projections.include("ts", "member_id", "event_type", "response_status", "interval_ms",
							"run_id", "meta"))
					.sort(Sorts.ascending("ts")).limit(MAX_EVENTS + 1).iterator()) {
				while (cur.hasNext()) {
					Document d = cur.next();
					if (events.size() == MAX_EVENTS) {
						truncated = true;
						break;
					}
					Instant ts = d.getDate("ts").toInstant();
					long memberId = ((Number) d.get("member_id")).longValue();
					events.add(new Event(ts, labelOf.get(memberId), memberId, d.getString("event_type"),
							num(d.get("response_status")) == null ? null : num(d.get("response_status")).intValue(),
							num(d.get("interval_ms")), num(d.get("run_id")), !ts.isBefore(wf) && !ts.isAfter(wt),
							meta(d.get("meta"))));
				}
			}
		}
		List<Marker> markers = detailMarkers(caseId);
		return new Timeline(caseId, f, t, wf, wt, accounts, events, markers, truncated);
	}

	private List<Marker> detailMarkers(long caseId) {
		return jdbc.query("""
				SELECT marker_id, rule_code, label, severity, marker_type, at_ts, session_id FROM case_marker
				 WHERE case_id = ? ORDER BY at_ts, marker_id""",
				(rs, i) -> new Marker(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
						instant(rs.getTimestamp(6)), rs.getString(7)),
				caseId);
	}

	private static Long num(Object o) {
		return o instanceof Number n ? n.longValue() : null;
	}

	private static Map<String, Object> meta(Object o) {
		if (!(o instanceof Document d)) {
			return Map.of();
		}
		return new LinkedHashMap<>(d);
	}
}
