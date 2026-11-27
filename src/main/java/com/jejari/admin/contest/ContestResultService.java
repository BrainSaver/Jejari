package com.jejari.admin.contest;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jejari.admin.contest.ContestTally.Case;
import com.jejari.admin.contest.ContestTally.Member;
import com.jejari.admin.contest.ContestTally.Subject;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;

/**
 * 봇 대회 결과 (읽기 전용). 대회는 집계자 PC의 진행 화면(loadtest/contest/web)이 돌리고, 이 화면은 그 결과를 DB에서 다시 읽어 보여 준다.
 * 아무것도 쓰지 않는다 — eval_contest.sh가 하는 synthetic_label 기록도 하지 않는다.
 *
 * <p>참가자는 이메일에서 읽는다: {@code lt{태그}.{종류}.g{묶음}.{번호}@loadtest.example.com},
 * 종류는 {@code bot…}(봇) 또는 {@code human…}(사람). 서버는 이 라벨을 탐지에 쓰지 않는다 (이 화면에서만 읽음).
 */
@Service
public class ContestResultService {
	private static final Logger log = LoggerFactory.getLogger(ContestResultService.class);
	private static final Pattern TAG = Pattern.compile("^[a-z0-9]{1,8}$");
	/** 이메일 → 종류. 이 모양이 아닌 부하 시험 계정(lt{태그}x{번호}@…)은 대회 참가자가 아니다 */
	private static final String KIND = "REGEXP_SUBSTR(email, '^lt[a-z0-9]+\\.([a-z]+)\\.g[0-9]+\\.', 1, 1, NULL, 1)";
	private static final String IS_PARTICIPANT = "(REGEXP_LIKE(" + KIND + ", '^human[a-z]*$') OR REGEXP_LIKE(" + KIND + ", '^bot[a-z]+$'))";
	private static final int CHUNK = 500; // Oracle IN 목록 1,000개 제한 아래로

	private final JdbcTemplate jdbc;
	private final MongoTemplate mongo;

	public ContestResultService(JdbcTemplate jdbc, MongoTemplate mongo) {
		this.jdbc = jdbc;
		this.mongo = mongo;
	}

	public record ContestSummary(String tag, int bots, int humans, LocalDateTime firstJoinedAt, Long mainRunId) {
	}

	public record RunInfo(long runId, LocalDate runDate, String trainNo, String status, String saleEventName) {
	}

	public record ContestResult(String tag, RunInfo run, List<RunChoice> runs, long penaltyMsPerPoint,
			boolean behaviorAvailable, ContestTally.Result result) {
	}

	/** 이 대회 참가자가 예약한 운행 (보통 하나. 여럿이면 화면에서 고른다) */
	public record RunChoice(long runId, int reservations) {
	}

	/** 대회 목록: 참가자 이메일 태그별, 최근 것부터 (최대 30개) */
	public List<ContestSummary> list() {
		List<ContestSummary> out = new ArrayList<>();
		jdbc.query("""
				SELECT tag, SUM(CASE WHEN kind LIKE 'bot%%' THEN 1 ELSE 0 END) bots,
				       SUM(CASE WHEN kind LIKE 'human%%' THEN 1 ELSE 0 END) humans, MIN(created_at) first_at
				  FROM (SELECT REGEXP_SUBSTR(email, '^lt([a-z0-9]+)\\.', 1, 1, NULL, 1) tag, %s kind, created_at
				          FROM member WHERE email LIKE 'lt%%' AND %s)
				 GROUP BY tag ORDER BY MIN(created_at) DESC FETCH FIRST 30 ROWS ONLY""".formatted(KIND, IS_PARTICIPANT),
				rs -> {
					String tag = rs.getString("tag");
					Timestamp at = rs.getTimestamp("first_at");
					List<RunChoice> runs = runsOf(tag);
					out.add(new ContestSummary(tag, rs.getInt("bots"), rs.getInt("humans"),
							at == null ? null : at.toLocalDateTime(), runs.isEmpty() ? null : runs.get(0).runId()));
				});
		return out;
	}

	public ContestResult result(String tag, Long runId) {
		if (tag == null || !TAG.matcher(tag).matches()) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		List<RunChoice> runs = runsOf(tag);
		Long run = runId != null ? runId : runs.isEmpty() ? null : runs.get(0).runId();

		// 1) 참가 계정
		Map<Long, String> kind = new LinkedHashMap<>();
		Map<Long, String> ci = new HashMap<>();
		jdbc.query("SELECT member_id, " + KIND + " kind, ci_hash FROM member WHERE email LIKE ? AND " + IS_PARTICIPANT,
				rs -> {
					kind.put(rs.getLong(1), rs.getString(2));
					ci.put(rs.getLong(1), rs.getString(3));
				}, "lt" + tag + ".%");
		if (kind.isEmpty()) {
			throw new BusinessException(CommonErrorCode.NOT_FOUND);
		}
		String members = "SELECT member_id FROM member WHERE email LIKE ? AND " + IS_PARTICIPANT;
		String like = "lt" + tag + ".%";

		// 2) 결제한 좌석 · 잡고 안 낸 좌석 (그 운행)
		Map<Long, int[]> seats = new HashMap<>();
		if (run != null) {
			jdbc.query("""
					SELECT r.member_id, SUM(CASE WHEN r.status = 'CONFIRMED' THEN 1 ELSE 0 END),
					       SUM(CASE WHEN r.status IN ('HOLD', 'PAYMENT_PENDING') THEN 1 ELSE 0 END)
					  FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.reservation_id
					 WHERE r.run_id = ? AND r.member_id IN (""" + members + ") GROUP BY r.member_id",
					rs -> {
						seats.put(rs.getLong(1), new int[] { rs.getInt(2), rs.getInt(3) });
					}, run, like);
		}

		// 3) 대상: CI · 결제수단 (Oracle) · 기기 (행동 로그)
		Map<Long, Set<Subject>> subjects = new HashMap<>();
		kind.keySet().forEach(m -> {
			Set<Subject> s = new LinkedHashSet<>();
			if (ci.get(m) != null) {
				s.add(new Subject("CI", ci.get(m)));
			}
			subjects.put(m, s);
		});
		jdbc.query("SELECT member_id, method_hash FROM payment_method WHERE method_hash IS NOT NULL AND member_id IN (" + members + ")",
				rs -> {
					subjects.get(rs.getLong(1)).add(new Subject("PAYMENT", rs.getString(2)));
				}, like);

		Map<Long, int[]> holds = new HashMap<>(); // [선점 시도, 매수 제한 거절]
		boolean behavior = true;
		try {
			List<Long> ids = new ArrayList<>(kind.keySet());
			for (Document d : mongo.getCollection("behavior_log").aggregate(List.of(
					new Document("$match", new Document("member_id", new Document("$in", ids))
							.append("device_hash", new Document("$type", "string"))),
					new Document("$group", new Document("_id", new Document("m", "$member_id").append("d", "$device_hash")))))) {
				Document id = (Document) d.get("_id");
				if (id.get("m") instanceof Number m && subjects.containsKey(m.longValue())) {
					subjects.get(m.longValue()).add(new Subject("DEVICE", id.getString("d")));
				}
			}
			if (run != null) {
				for (Document d : mongo.getCollection("behavior_log").aggregate(List.of(
						new Document("$match", new Document("member_id", new Document("$in", ids))
								.append("run_id", run).append("event_type", "SEAT_HOLD")),
						new Document("$group", new Document("_id", "$member_id")
								.append("n", new Document("$sum", 1))
								.append("lim", new Document("$sum", new Document("$cond", List.of(
										new Document("$regexMatch", new Document("input",
												new Document("$ifNull", List.of("$meta.errorCode", ""))).append("regex", "^LIMIT_")),
										1, 0)))))))) {
					if (d.get("_id") instanceof Number m) {
						holds.put(m.longValue(), new int[] { ((Number) d.get("n")).intValue(), ((Number) d.get("lim")).intValue() });
					}
				}
			}
		} catch (RuntimeException e) {
			// MongoDB가 멈춰도 Oracle 쪽 결과(좌석·케이스·점수)는 보여 준다
			behavior = false;
			log.warn("대회 결과: 행동 로그를 읽지 못해 선점 시도·매수 제한·기기 연결을 빼고 보여 줍니다 ({})", e.getClass().getSimpleName());
		}

		// 4) 대상에 걸린 의심 케이스 · 위험 점수
		Map<String, Set<String>> hashesByType = new HashMap<>();
		subjects.values().forEach(set -> set.forEach(s -> hashesByType.computeIfAbsent(s.type(), k -> new HashSet<>()).add(s.hash())));
		Map<Long, Case> cases = new LinkedHashMap<>();
		Map<Subject, Double> scores = new HashMap<>();
		hashesByType.forEach((type, hashes) -> {
			for (List<String> part : chunks(hashes)) {
				String in = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
				Object[] args = new Object[part.size() + 1];
				args[0] = type;
				for (int i = 0; i < part.size(); i++) {
					args[i + 1] = part.get(i);
				}
				jdbc.query("""
						SELECT c.case_id, c.subject_hash, c.status, NVL(cm.rule_code, c.trigger_type) rule
						  FROM suspicious_case c LEFT JOIN case_marker cm ON cm.case_id = c.case_id
						 WHERE c.subject_type = ? AND c.subject_hash IN (""" + in + ")",
						rs -> {
							long id = rs.getLong(1);
							Case c = cases.get(id);
							if (c == null) {
								c = new Case(id, new Subject(type, rs.getString(2)), rs.getString(3), new ArrayList<>());
								cases.put(id, c);
							}
							String rule = rs.getString(4);
							if (rule != null && !c.rules().contains(rule)) {
								c.rules().add(rule);
							}
						}, args);
				jdbc.query("SELECT subject_hash, final_score FROM risk_score WHERE subject_type = ? AND subject_hash IN (" + in + ")",
						rs -> {
							scores.put(new Subject(type, rs.getString(1)), rs.getDouble(2));
						}, args);
			}
		});

		long penalty = penaltyMsPerPoint();
		List<Member> list = new ArrayList<>();
		kind.forEach((m, k) -> {
			int[] st = seats.getOrDefault(m, new int[2]);
			int[] h = holds.getOrDefault(m, new int[2]);
			list.add(new Member(m, k, ci.get(m), st[0], st[1], h[0], h[1], subjects.get(m)));
		});
		return new ContestResult(tag, run == null ? null : runInfo(run), runs, penalty, behavior,
				ContestTally.tally(list, new ArrayList<>(cases.values()), scores, penalty));
	}

	/** 참가자가 예약한 운행을 예약 수 많은 순으로 (대회 운행이 맨 앞) */
	private List<RunChoice> runsOf(String tag) {
		return jdbc.query("""
				SELECT r.run_id, COUNT(*) n FROM reservation r
				 WHERE r.member_id IN (SELECT member_id FROM member WHERE email LIKE ? AND %s)
				 GROUP BY r.run_id ORDER BY n DESC, r.run_id DESC FETCH FIRST 5 ROWS ONLY""".formatted(IS_PARTICIPANT),
				(rs, i) -> new RunChoice(rs.getLong(1), rs.getInt(2)), "lt" + tag + ".%");
	}

	private RunInfo runInfo(long runId) {
		List<RunInfo> r = jdbc.query("""
				SELECT r.run_id, r.run_date, t.train_no, r.status, e.name
				  FROM train_run r JOIN train t ON t.train_id = r.train_id
				  LEFT JOIN sale_event e ON e.sale_event_id = r.sale_event_id
				 WHERE r.run_id = ?""",
				(rs, i) -> new RunInfo(rs.getLong(1), rs.getDate(2) == null ? null : rs.getDate(2).toLocalDate(),
						rs.getString(3), rs.getString(4), rs.getString(5)), runId);
		return r.isEmpty() ? new RunInfo(runId, null, null, null, null) : r.get(0);
	}

	/** 점수 1점당 대기열에서 밀리는 ms. 없거나 숫자가 아니면 ERD 예시값 1000 (eval_contest.sh와 같은 출처) */
	private long penaltyMsPerPoint() {
		List<String> v = jdbc.queryForList("SELECT policy_value FROM app_policy WHERE policy_key = 'risk.penalty_ms_per_point'", String.class);
		try {
			return v.isEmpty() ? 1000 : Long.parseLong(v.get(0).strip());
		} catch (NumberFormatException e) {
			return 1000;
		}
	}

	private static List<List<String>> chunks(Collection<String> all) {
		List<String> l = new ArrayList<>(all);
		List<List<String>> out = new ArrayList<>();
		for (int i = 0; i < l.size(); i += CHUNK) {
			out.add(l.subList(i, Math.min(l.size(), i + CHUNK)));
		}
		return out;
	}
}
