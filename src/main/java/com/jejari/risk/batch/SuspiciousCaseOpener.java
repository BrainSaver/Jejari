package com.jejari.risk.batch;

import org.bson.BsonType;
import java.util.Map;
import java.util.LinkedHashMap;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.jejari.behavior.BehaviorType;
import com.jejari.risk.Reason;
import com.jejari.risk.RiskPolicy;
import com.jejari.risk.RiskResult;
import com.jejari.risk.RiskRule;
import com.jejari.risk.SubjectType;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;

/**
 * RISK-04 의심 케이스 자동 생성. 배치가 점수를 저장할 때 같은 트랜잭션에서 부른다.
 *
 * <pre>
 * 열 조건: 새 증거가 점수를 만들었고(규칙 점수 ≥ 감쇠한 이전 점수)
 *          규칙 점수 ≥ 80 → RISK_THRESHOLD, 아니면 마커 규칙(FAST_CLICK 등 5개, RiskRule.marker)이 하나라도 걸림 → RULE
 * 열지 않음: 이 대상에 OPEN·REVIEWING 케이스가 이미 있음 / 감쇠만으로 저장한 경우 / 같은 증거로 다시 계산한 경우(저장 안 함)
 * 함께: case_marker(AUTO) 규칙마다 1개, risk_score.review_status NONE → PENDING
 * </pre>
 *
 * <ul>
 *   <li><b>왜 "새 증거"일 때만</b>: 감쇠한 이전 점수만으로 80을 넘는 동안 매번 열면, 관리자가 판정(CONFIRMED)해 닫은 뒤에도
 *       같은 증거로 케이스가 다시 열린다. 새 증거가 생겨야 새 조사 건이 된다.</li>
 *   <li><b>의심 구간</b>: 마커 규칙에 해당하는 이벤트의 처음~끝 시각 앞뒤로 {@link #PAD}를 붙인다 (재생 범위 기본값).</li>
 *   <li><b>마커 세션</b>: 그 규칙의 마지막 이벤트 세션. auth_session에 없으면(만료 정리 등) 비운다 (FK 위반 방지).
 *       구매 직후 취소는 Oracle 기록이라 세션이 없다.</li>
 *   <li>CLEARED·CONFIRMED는 PENDING으로 바꾸지 않는다. CLEARED를 풀면 엔진이 CLEAR 이전 증거를 다시 센다.</li>
 * </ul>
 */
@Component
public class SuspiciousCaseOpener {
	private static final Logger log = LoggerFactory.getLogger(SuspiciousCaseOpener.class);

	/** 의심 구간 앞뒤 여유 */
	static final Duration PAD = Duration.ofMinutes(1);
	static final int DETAIL_MAX = 200;

	private final JdbcTemplate jdbc;
	private final MongoTemplate mongo;

	public SuspiciousCaseOpener(JdbcTemplate jdbc, MongoTemplate mongo) {
		this.jdbc = jdbc;
		this.mongo = mongo;
	}

	/** 마커 하나 (case_marker 행) */
	public record Marker(RiskRule rule, Instant at, String sessionId, String label, String severity) {
	}

	/** 의심 구간 안에서 활동한 연결 계정의 로그인 세션 (case_session 후보) */
	public record LinkedSession(String sessionId, long memberId) {
	}

	/** 열 케이스 (suspicious_case 행 + 마커 + 묶인 세션) */
	public record Plan(RiskSubject subject, String triggerType, String triggerDetail, BigDecimal scoreAtOpen,
			Instant windowFrom, Instant windowTo, Instant openedAt, List<Marker> markers, List<LinkedSession> sessions) {
	}

	/** 케이스 하나에 묶어 두는 세션 상한 (매크로가 세션을 수천 개 만들어도 케이스 열기가 무거워지지 않게) */
	static final int MAX_CASE_SESSIONS = 200;

	/** 케이스 종류. 새 증거가 아니거나 조건을 못 넘으면 null */
	static String triggerOf(RiskResult r, RiskPolicy policy) {
		if (r.ruleScore().compareTo(r.finalScore()) < 0) {
			return null; // 감쇠한 이전 점수가 지배: 새 증거가 아님
		}
		if (r.ruleScore().compareTo(BigDecimal.valueOf(policy.caseThreshold())) >= 0) {
			return "RISK_THRESHOLD";
		}
		return r.markerReasons().isEmpty() ? null : "RULE";
	}

	/**
	 * 케이스를 열어야 하면 증거(시각·세션)를 읽어 계획을 만든다. 트랜잭션 밖에서 부른다 (MongoDB 조회가 있어서).
	 *
	 * @param behaviorFrom 배치가 행동 로그를 센 시작 시각
	 * @param purchaseFrom 배치가 구매·취소를 센 시작 시각
	 */
	public Plan plan(RiskPolicy policy, RiskSubject s, RiskResult r, Instant behaviorFrom, Instant purchaseFrom) {
		String trigger = triggerOf(r, policy);
		if (trigger == null) {
			return null;
		}
		Instant now = r.calculatedAt();
		List<Marker> markers = new ArrayList<>();
		Instant first = null;
		Instant last = null;
		for (Reason reason : r.markerReasons()) {
			Span span = evidence(policy, s, reason.rule(), behaviorFrom, purchaseFrom);
			Instant at = span == null ? now : span.last();
			markers.add(new Marker(reason.rule(), at, span == null ? null : span.sessionId(), cut(label(policy, reason)),
					reason.severity().name()));
			if (span != null) {
				first = first == null || span.first().isBefore(first) ? span.first() : first;
				last = last == null || span.last().isAfter(last) ? span.last() : last;
			}
		}
		if (first == null) {
			// 마커 근거 시각을 못 찾음 (점수만 넘은 경우 등): 행동 로그를 센 기간 전체
			first = behaviorFrom;
			last = now;
		}
		List<String> parts = new ArrayList<>();
		if ("RISK_THRESHOLD".equals(trigger)) {
			parts.add("위험 점수 " + r.finalScore().stripTrailingZeros().toPlainString() + "점");
		}
		markers.forEach(m -> parts.add(m.label()));
		Instant wf = first.minus(PAD);
		Instant wt = last.plus(PAD);
		return new Plan(s, trigger, cut(String.join(" · ", parts)), r.finalScore(), wf, wt, now, markers,
				linkedSessions(s, wf, wt));
	}

	/**
	 * 대상에 연결된 계정(위험 점수와 같은 기준, {@link RiskInputReader#membersOf})이 의심 구간 안에서 쓴 로그인 세션.
	 * 케이스를 열 때 {@code case_session}에 박아 두어, 나중에 기기 연결이 바뀌어도 "조사 시점에 누가 왜 묶였나"가 남는다.
	 */
	public List<LinkedSession> linkedSessions(RiskSubject s, Instant from, Instant to) {
		List<Long> members = jdbc.queryForList(RiskInputReader.membersOf(s.type()), Long.class, s.hash());
		if (members.isEmpty()) {
			return List.of();
		}
		Map<String, Long> out = new LinkedHashMap<>();
		Bson filter = Filters.and(Filters.in("member_id", members), Filters.gte("ts", Date.from(from)),
				Filters.lte("ts", Date.from(to)), Filters.type("session_id", BsonType.STRING));
		for (Document d : mongo.getCollection("behavior_log").find(filter)
				.projection(Projections.include("session_id", "member_id")).sort(Sorts.ascending("ts"))) {
			if (out.size() >= MAX_CASE_SESSIONS) {
				break;
			}
			Object m = d.get("member_id");
			if (m instanceof Number n) {
				out.putIfAbsent(d.getString("session_id"), n.longValue());
			}
		}
		List<LinkedSession> list = new ArrayList<>();
		out.forEach((sid, mid) -> list.add(new LinkedSession(sid, mid)));
		return list;
	}

	/**
	 * 계획대로 케이스·마커를 넣고 review_status를 PENDING으로. 점수 저장과 <b>같은 트랜잭션</b>에서 부른다:
	 * 따로 하면 점수만 저장되고 케이스가 빠졌을 때 다음 배치는 "바뀐 게 없다"며 저장도 케이스도 건너뛴다.
	 *
	 * @return 열었으면 케이스 ID, 이미 열린 케이스가 있으면 null
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Long open(Plan p) {
		RiskSubject s = p.subject();
		Integer openCount = jdbc.queryForObject("""
				SELECT COUNT(*) FROM suspicious_case
				 WHERE subject_type = ? AND subject_hash = ? AND status IN ('OPEN', 'REVIEWING')""",
				Integer.class, s.type().name(), s.hash());
		if (openCount != null && openCount > 0) {
			return null;
		}
		long caseId = jdbc.queryForObject("SELECT suspicious_case_seq.NEXTVAL FROM dual", Long.class);
		try {
			jdbc.update("""
					INSERT INTO suspicious_case (case_id, subject_type, subject_hash, trigger_type, trigger_detail,
					  risk_score_at_open, window_from, window_to, status, opened_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)""",
					caseId, s.type().name(), s.hash(), p.triggerType(), p.triggerDetail(), p.scoreAtOpen(),
					Timestamp.from(p.windowFrom()), Timestamp.from(p.windowTo()),
					Timestamp.from(p.openedAt()));
		} catch (DuplicateKeyException e) {
			return null; // 같은 순간 다른 쪽(관리자 지정 등)이 열었음 (열린 케이스 유니크 인덱스)
		}
		for (Marker m : p.markers()) {
			jdbc.update("""
					INSERT INTO case_marker (marker_id, case_id, session_id, at_ts, marker_type, rule_code, label, severity)
					VALUES (case_marker_seq.NEXTVAL, ?, ?, ?, 'AUTO', ?, ?, ?)""",
					caseId, existingSession(m.sessionId()), Timestamp.from(m.at()), m.rule().name(), m.label(),
					m.severity());
		}
		int linked = linkSessions(caseId, p);
		jdbc.update("""
				UPDATE risk_score SET review_status = 'PENDING', updated_at = SYSTIMESTAMP
				 WHERE subject_type = ? AND subject_hash = ? AND review_status = 'NONE'""",
				s.type().name(), s.hash());
		log.info("의심 케이스 {} 열림 ({}, {}, 마커 {}개, 묶인 세션 {}개)", caseId, s.type(), p.triggerType(),
				p.markers().size(), linked);
		return caseId;
	}

	/**
	 * case_session: 마커 근거가 된 세션은 TRIGGER, 나머지는 대상 종류대로 SAME_CI / SAME_DEVICE / SAME_PAYMENT.
	 * auth_session에 없는 세션(가상 데이터·옛 로그)은 FK 때문에 넣지 않는다. 케이스와 같은 트랜잭션.
	 */
	int linkSessions(long caseId, Plan p) {
		if (p.sessions() == null || p.sessions().isEmpty()) {
			return 0;
		}
		java.util.Set<String> trigger = new java.util.HashSet<>();
		p.markers().forEach(m -> {
			if (m.sessionId() != null) {
				trigger.add(m.sessionId());
			}
		});
		String same = switch (p.subject().type()) {
			case CI -> "SAME_CI";
			case DEVICE -> "SAME_DEVICE";
			case PAYMENT -> "SAME_PAYMENT";
		};
		List<String> ids = p.sessions().stream().map(LinkedSession::sessionId).toList();
		java.util.Set<String> existing = new java.util.HashSet<>();
		for (int i = 0; i < ids.size(); i += 500) {
			List<String> chunk = ids.subList(i, Math.min(ids.size(), i + 500));
			existing.addAll(jdbc.queryForList("SELECT session_id FROM auth_session WHERE session_id IN ("
					+ String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ")", String.class,
					chunk.toArray()));
		}
		int n = 0;
		for (LinkedSession ls : p.sessions()) {
			if (!existing.contains(ls.sessionId())) {
				continue;
			}
			jdbc.update("""
					INSERT INTO case_session (case_id, session_id, member_id, link_reason) VALUES (?, ?, ?, ?)""",
					caseId, ls.sessionId(), ls.memberId(), trigger.contains(ls.sessionId()) ? "TRIGGER" : same);
			n++;
		}
		return n;
	}

	private String existingSession(String sessionId) {
		if (sessionId == null) {
			return null;
		}
		Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE session_id = ?", Integer.class,
				sessionId);
		return n != null && n > 0 ? sessionId : null;
	}

	/** 근거 이벤트의 처음·끝 시각과 마지막 세션 */
	record Span(Instant first, Instant last, String sessionId) {
	}

	Span evidence(RiskPolicy policy, RiskSubject s, RiskRule rule, Instant behaviorFrom, Instant purchaseFrom) {
		if (rule == RiskRule.CANCEL_AFTER_PAY) {
			return cancelSpan(policy, s, purchaseFrom);
		}
		String field = switch (s.type()) {
			case CI -> "ci_hash";
			case DEVICE -> "device_hash";
			case PAYMENT -> null;
		};
		if (field == null) {
			return null;
		}
		Bson base = Filters.and(Filters.eq(field, s.hash()), Filters.gte("ts", Date.from(behaviorFrom)));
		Bson filter = switch (rule) {
			case FAST_CLICK -> Filters.and(base, Filters.lt("interval_ms", policy.fastIntervalMs()));
			case SAME_SEAT_RETRY -> Filters.and(base, Filters.eq("event_type", BehaviorType.SEAT_HOLD.name()),
					Filters.eq("response_status", 409));
			case MULTI_ACCOUNT_SYNC -> Filters.and(base, Filters.eq("event_type", BehaviorType.SEAT_HOLD.name()));
			// 규칙성 통계에 들어간 간격(긴 공백 제외)이 있는 요청들 = 일정하게 반복한 구간
			case REGULAR_INTERVAL -> Filters.and(base, Filters.exists("interval_ms"),
					Filters.lt("interval_ms", policy.statsMaxGapMs()));
			default -> null;
		};
		if (filter == null) {
			return null;
		}
		Document first = one(filter, Sorts.ascending("ts"));
		Document last = one(filter, Sorts.descending("ts"));
		if (first == null || last == null || first.getDate("ts") == null || last.getDate("ts") == null) {
			return null;
		}
		return new Span(first.getDate("ts").toInstant(), last.getDate("ts").toInstant(), last.getString("session_id"));
	}

	private Document one(Bson filter, Bson sort) {
		return mongo.getCollection("behavior_log").find(filter).projection(Projections.include("ts", "session_id"))
				.sort(sort).limit(1).first();
	}

	private Span cancelSpan(RiskPolicy policy, RiskSubject s, Instant from) {
		String resv = RiskInputReader.reservationsOf(s.type());
		if (resv == null) {
			return null;
		}
		List<Span> rows = jdbc.query("""
				SELECT MIN(canceled_at), MAX(canceled_at) FROM cancellation
				 WHERE cancel_type = 'USER' AND canceled_at >= ? AND minutes_after_purchase <= ?
				   AND reservation_id IN (""" + resv + ")",
				(rs, i) -> rs.getTimestamp(1) == null ? null
						: new Span(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(), null),
				Timestamp.from(from), policy.cancelAfterPayMin(), s.hash());
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** 관리자 화면 첫 줄에 보이는 설명. 숫자와 고정 문구만 (외부 입력이 들어갈 자리 없음) */
	static String label(RiskPolicy policy, Reason r) {
		long n = Math.round(r.observed());
		return switch (r.rule()) {
			case FAST_CLICK -> policy.fastIntervalMs() + "ms 미만 간격 요청 " + n + "회";
			case SAME_SEAT_RETRY -> "같은 좌석 선점 실패 " + n + "회";
			case CANCEL_AFTER_PAY -> "구매 후 " + policy.cancelAfterPayMin() + "분 안 취소 " + n + "건";
			case MULTI_ACCOUNT_SYNC -> policy.multiAccountWindowSec() + "초 안에 계정 " + n + "개가 좌석 선점";
			// observed는 변동계수(0.001 같은 작은 수)라 반올림하면 0이 된다 → 소수로
			case REGULAR_INTERVAL -> "요청 간격이 기계처럼 일정함 (변동계수 "
					+ String.format(java.util.Locale.ROOT, "%.3f", r.observed()) + ")";
			default -> r.rule().name() + " " + n;
		};
	}

	/** VARCHAR2(200)은 바이트 단위라 UTF-8 바이트로 자른다 (한글 한 글자 = 3바이트). 넘으면 "…"(3바이트)를 붙인다 */
	static String cut(String s) {
		if (s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= DETAIL_MAX) {
			return s;
		}
		StringBuilder out = new StringBuilder();
		int bytes = 0;
		for (int i = 0; i < s.length();) {
			int cp = s.codePointAt(i);
			int len = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
			if (bytes + len > DETAIL_MAX - 3) {
				break;
			}
			out.appendCodePoint(cp);
			bytes += len;
			i += Character.charCount(cp);
		}
		return out.append('…').toString();
	}

	/** 테스트·관리자 화면용: 대상의 열린 케이스 수 */
	public int openCases(SubjectType type, String hash) {
		Integer n = jdbc.queryForObject("""
				SELECT COUNT(*) FROM suspicious_case
				 WHERE subject_type = ? AND subject_hash = ? AND status IN ('OPEN', 'REVIEWING')""",
				Integer.class, type.name(), hash);
		return n == null ? 0 : n;
	}
}
