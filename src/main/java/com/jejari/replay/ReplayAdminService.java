package com.jejari.replay;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jejari.common.config.AppPolicy;
import com.jejari.common.error.BusinessException;
import com.jejari.risk.SubjectType;
import com.jejari.risk.admin.RiskAdminErrorCode;
import com.jejari.risk.batch.RiskInputReader;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;

/**
 * ADM-11 관리자 재생. 녹화는 <b>케이스를 통해서만</b> 본다: 케이스 대상(CI·기기·결제수단)에 연결된 계정의 세션 중
 * 의심 구간과 겹치는 것만. 아무 세션이나 ID로 열어 볼 수 없게 (개인정보 최소 열람).
 *
 * <pre>
 * sessions(caseId)          → 케이스와 겹치는 녹화 세션 목록
 * replay(caseId, sessionId) → 화면 이벤트(replay_event) + 같은 세션의 서버 이벤트(behavior_log: 선점·결제·취소…)
 * hold(caseId)              → 그 세션들의 삭제 보류 (케이스가 열릴 때 1분 배치가 부름)
 * </pre>
 */
@Service
public class ReplayAdminService {
	private static final Logger log = LoggerFactory.getLogger(ReplayAdminService.class);
	static final int MAX_SESSIONS = 50;

	private final JdbcTemplate jdbc;
	private final MongoTemplate mongo;
	private final AppPolicy policy;

	public ReplayAdminService(JdbcTemplate jdbc, MongoTemplate mongo, AppPolicy policy) {
		this.jdbc = jdbc;
		this.mongo = mongo;
		this.policy = policy;
	}

	public record Session(String sessionId, Long memberId, Date startedAt, Date endedAt, long eventCount, String mode,
			boolean legalHold, List<Object> runIds) {
	}

	public record Replay(Session session, List<Map<String, Object>> events, List<Map<String, Object>> server) {
	}

	private record CaseWindow(SubjectType type, String hash, Timestamp from, Timestamp to) {
	}

	public List<Session> sessions(long caseId) {
		CaseWindow w = window(caseId);
		List<Session> out = new ArrayList<>();
		for (Document d : mongo.getCollection("replay_session").find(filter(w)).sort(Sorts.ascending("started_at"))
				.limit(MAX_SESSIONS)) {
			out.add(toSession(d));
		}
		return out;
	}

	public Replay replay(long caseId, String sessionId) {
		CaseWindow w = window(caseId);
		Document s = mongo.getCollection("replay_session").find(Filters.and(filter(w), Filters.eq("_id", sessionId)))
				.first();
		if (s == null) {
			// 없는 세션과 이 케이스에 속하지 않는 세션을 구분하지 않는다
			throw new BusinessException(RiskAdminErrorCode.CASE_NOT_FOUND);
		}
		int limit = (int) policy.getLong(AppPolicy.REPLAY_MAX_EVENTS_PER_SESSION);
		List<Map<String, Object>> events = new ArrayList<>();
		for (Document d : mongo.getCollection("replay_event").find(Filters.eq("session_id", sessionId))
				.projection(Projections.exclude("_id", "session_id", "expire_at")).sort(Sorts.ascending("seq"))
				.limit(limit)) {
			events.add(d);
		}
		List<Map<String, Object>> server = new ArrayList<>();
		for (Document d : mongo.getCollection("behavior_log").find(Filters.eq("session_id", sessionId))
				.projection(Projections.include("ts", "event_type", "response_status", "run_id"))
				.sort(Sorts.ascending("ts")).limit(1000)) {
			d.remove("_id");
			server.add(d);
		}
		return new Replay(toSession(s), events, server);
	}

	/** 케이스와 겹치는 세션의 삭제 보류. 실패해도 케이스 생성은 그대로 (다음 열람 때 다시 할 수 있음) */
	public int hold(long caseId) {
		try {
			CaseWindow w = window(caseId);
			List<String> ids = new ArrayList<>();
			for (Document d : mongo.getCollection("replay_session").find(filter(w)).projection(Projections.include("_id"))) {
				ids.add(d.getString("_id"));
			}
			if (ids.isEmpty()) {
				return 0;
			}
			mongo.getCollection("replay_session").updateMany(Filters.in("_id", ids),
					Updates.combine(Updates.set("legal_hold", true), Updates.unset("expire_at")));
			mongo.getCollection("replay_event").updateMany(Filters.in("session_id", ids), Updates.unset("expire_at"));
			return ids.size();
		} catch (RuntimeException e) {
			log.warn("케이스 {} 녹화 삭제 보류 실패: {}", caseId, e.toString());
			return 0;
		}
	}

	private CaseWindow window(long caseId) {
		List<CaseWindow> r = jdbc.query("""
				SELECT subject_type, subject_hash, window_from, window_to FROM suspicious_case WHERE case_id = ?""",
				(rs, i) -> new CaseWindow(SubjectType.valueOf(rs.getString(1)), rs.getString(2), rs.getTimestamp(3),
						rs.getTimestamp(4)), caseId);
		if (r.isEmpty()) {
			throw new BusinessException(RiskAdminErrorCode.CASE_NOT_FOUND);
		}
		return r.get(0);
	}

	/** 대상의 계정(또는 같은 CI·기기) 세션 중 의심 구간과 겹치는 것 */
	private Bson filter(CaseWindow w) {
		List<Long> members = jdbc.queryForList(RiskInputReader.membersOf(w.type()), Long.class, w.hash());
		List<Bson> who = new ArrayList<>();
		who.add(Filters.in("member_id", members));
		if (w.type() == SubjectType.CI) {
			who.add(Filters.eq("ci_hash", w.hash()));
		} else if (w.type() == SubjectType.DEVICE) {
			who.add(Filters.eq("device_hash", w.hash()));
		}
		List<Bson> and = new ArrayList<>();
		and.add(Filters.or(who));
		if (w.to() != null) {
			and.add(Filters.lte("started_at", new Date(w.to().getTime())));
		}
		if (w.from() != null) {
			and.add(Filters.gte("ended_at", new Date(w.from().getTime())));
		}
		return Filters.and(and);
	}

	@SuppressWarnings("unchecked")
	private static Session toSession(Document d) {
		Object runs = d.get("run_ids");
		return new Session(d.getString("_id"), d.get("member_id") instanceof Number n ? n.longValue() : null,
				d.getDate("started_at"), d.getDate("ended_at"),
				d.get("event_count") instanceof Number c ? c.longValue() : 0, d.getString("recording_mode"),
				Boolean.TRUE.equals(d.getBoolean("legal_hold")), runs instanceof List<?> l ? (List<Object>) l : List.of());
	}
}
