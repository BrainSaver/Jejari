package com.jejari.risk.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jejari.common.error.BusinessException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;

/**
 * A-09 모델 관리 (RISK-05 이어서). 합성 모델 버전(model_run, algorithm = ENSEMBLE)을 실데이터 점수로 비교하고, 관리자 화면에
 * 보여 줄 버전을 고른다.
 *
 * <ul>
 *   <li><b>학습은 하지 않는다.</b> 점수는 서버의 매시간 작업(deploy/model-refresh.sh)이 버전마다 따로 매겨 model_score에 넣는다.
 *       서버 CPU가 이미 바빠(PERF-01) 화면 요청으로 학습을 돌리지 않는다.</li>
 *   <li><b>정답이 없는 실데이터</b>라 "맞힌 비율"은 관리자 판정(suspicious_case.verdict)이 있는 계정으로만 센다. 봇 판정
 *       (RESELLER·MACRO·CANCEL_ABUSE)이 있으면 봇, NORMAL이면 사람. 규칙이 연 케이스에만 판정이 있어 규칙 쪽으로 치우친다.</li>
 *   <li>사용 중으로 바꾸는 것은 관리자 화면의 보조 의견 카드에 어느 버전을 보여 줄지일 뿐, 대기열 순번·제한에는 아무 영향이 없다.</li>
 * </ul>
 */
@Service
public class ModelAdminService {

	private static final Logger log = LoggerFactory.getLogger(ModelAdminService.class);
	static final int MAX_DIFF = 50;

	private final JdbcTemplate jdbc;
	private final MongoTemplate mongo;

	public ModelAdminService(JdbcTemplate jdbc, MongoTemplate mongo) {
		this.jdbc = jdbc;
		this.mongo = mongo;
	}

	/**
	 * @param scored       점수가 있는 계정 수 (model_score)
	 * @param flagged      문턱 이상 = "봇 쪽으로 봄" 계정 수
	 * @param types        봇 쪽으로 본 계정의 닮은 유형별 수 (ERD v29)
	 * @param verdicts     관리자 판정이 있는 계정 기준 맞힌 수
	 */
	public record ModelVersion(String version, String trainSource, Instant trainedAt, BigDecimal threshold, boolean active,
			long scored, long flagged, Instant lastScoredAt, Map<String, Long> types, Verdicts verdicts) {
	}

	/** 판정으로 봇인 계정 bots 중 봇 쪽으로 본 수, 판정으로 사람인 계정 humans 중 봇 쪽으로 본 수(= 오탐) */
	public record Verdicts(int bots, int botsFlagged, int humans, int humansFlagged) {
	}

	/** 한쪽만 봇 쪽으로 본 계정. verdict = BOT / HUMAN / null(판정 없음) */
	public record Diff(long memberId, Double scoreA, Double scoreB, String typeA, String typeB, String verdict) {
	}

	public record Comparison(String a, String b, long both, long onlyA, long onlyB, List<Diff> onlyAList,
			List<Diff> onlyBList) {
	}

	private record Run(String version, String trainSource, Instant trainedAt, BigDecimal threshold, boolean active) {
	}

	private record Score(double score, String type) {
	}

	public List<ModelVersion> list() {
		Map<Long, Boolean> judged = judged();
		List<ModelVersion> out = new ArrayList<>();
		for (Run r : runs()) {
			Bson v = Filters.eq("model_version", r.version());
			long scored = col().countDocuments(v);
			Map<Long, Score> s = scores(r.version());
			double thr = threshold(r);
			Map<String, Long> types = new TreeMap<>();
			long flagged = 0;
			for (Score x : s.values()) {
				if (x.score() >= thr) {
					flagged++;
					types.merge(x.type() == null ? "UNKNOWN" : x.type(), 1L, Long::sum);
				}
			}
			int bots = 0, botsFlagged = 0, humans = 0, humansFlagged = 0;
			for (Map.Entry<Long, Boolean> j : judged.entrySet()) {
				Score x = s.get(j.getKey());
				if (x == null) {
					continue;
				}
				boolean f = x.score() >= thr;
				if (j.getValue()) {
					bots++;
					botsFlagged += f ? 1 : 0;
				} else {
					humans++;
					humansFlagged += f ? 1 : 0;
				}
			}
			Document last = col().find(v).projection(Projections.include("scored_at"))
					.sort(Sorts.descending("scored_at")).limit(1).first();
			Date at = last == null ? null : last.getDate("scored_at");
			out.add(new ModelVersion(r.version(), r.trainSource(), r.trainedAt(), r.threshold(), r.active(), scored, flagged,
					at == null ? null : at.toInstant(), types, new Verdicts(bots, botsFlagged, humans, humansFlagged)));
		}
		return out;
	}

	public Comparison compare(String a, String b) {
		Run ra = run(a);
		Run rb = run(b);
		Map<Long, Score> sa = scores(a);
		Map<Long, Score> sb = scores(b);
		double ta = threshold(ra);
		double tb = threshold(rb);
		Map<Long, Boolean> judged = judged();
		long both = 0;
		List<Diff> onlyA = new ArrayList<>();
		List<Diff> onlyB = new ArrayList<>();
		for (Map.Entry<Long, Score> e : sa.entrySet()) {
			Score y = sb.get(e.getKey());
			boolean fa = e.getValue().score() >= ta;
			boolean fb = y != null && y.score() >= tb;
			if (fa && fb) {
				both++;
			} else if (fa) {
				onlyA.add(diff(e.getKey(), e.getValue(), y, judged));
			}
		}
		for (Map.Entry<Long, Score> e : sb.entrySet()) {
			Score x = sa.get(e.getKey());
			if (e.getValue().score() >= tb && (x == null || x.score() < ta)) {
				onlyB.add(diff(e.getKey(), x, e.getValue(), judged));
			}
		}
		onlyA.sort(Comparator.comparing((Diff d) -> d.scoreA() == null ? 0 : d.scoreA()).reversed());
		onlyB.sort(Comparator.comparing((Diff d) -> d.scoreB() == null ? 0 : d.scoreB()).reversed());
		return new Comparison(a, b, both, onlyA.size(), onlyB.size(), onlyA.subList(0, Math.min(MAX_DIFF, onlyA.size())),
				onlyB.subList(0, Math.min(MAX_DIFF, onlyB.size())));
	}

	/** 관리자 화면에 보여 줄 버전을 바꾼다. ENSEMBLE은 하나만 사용 중 (한 문장으로 바꿔 둘이 동시에 켜지지 않게) */
	@Transactional
	public List<ModelVersion> activate(long adminId, String version) {
		run(version);
		if (col().countDocuments(Filters.eq("model_version", version)) == 0) {
			throw new BusinessException(ModelErrorCode.MODEL_NOT_SCORED);
		}
		jdbc.update("""
				UPDATE model_run SET is_active = CASE WHEN model_version = ? THEN 'Y' ELSE 'N' END, updated_at = SYSTIMESTAMP
				 WHERE algorithm = 'ENSEMBLE'""", version);
		log.info("모델 보조 의견 버전 변경: {} (관리자 #{})", version, adminId);
		return list();
	}

	// ------------------------------------------------------------

	private MongoCollection<Document> col() {
		return mongo.getCollection("model_score");
	}

	private List<Run> runs() {
		return jdbc.query("""
				SELECT model_version, train_source, trained_at, threshold, is_active FROM model_run
				 WHERE algorithm = 'ENSEMBLE' ORDER BY trained_at DESC, model_version""",
				(rs, i) -> new Run(rs.getString(1), rs.getString(2),
						rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getBigDecimal(4),
						"Y".equals(rs.getString(5))));
	}

	private Run run(String version) {
		return runs().stream().filter(r -> r.version().equals(version)).findFirst()
				.orElseThrow(() -> new BusinessException(ModelErrorCode.MODEL_NOT_FOUND));
	}

	private static double threshold(Run r) {
		return r.threshold() == null ? Double.MAX_VALUE : r.threshold().doubleValue();
	}

	private Map<Long, Score> scores(String version) {
		Map<Long, Score> out = new HashMap<>();
		for (Document d : col().find(Filters.eq("model_version", version))
				.projection(Projections.include("member_id", "score", "type"))) {
			Object id = d.get("member_id");
			Object sc = d.get("score");
			if (id instanceof Number n && sc instanceof Number s) {
				out.put(n.longValue(), new Score(s.doubleValue(), d.getString("type")));
			}
		}
		return out;
	}

	/** 관리자 판정이 있는 계정: true = 봇 판정, false = 사람 판정. 한 계정에 둘 다 있으면 봇 */
	private Map<Long, Boolean> judged() {
		Map<Long, Boolean> out = new LinkedHashMap<>();
		jdbc.query("""
				SELECT DISTINCT cs.member_id, c.verdict FROM suspicious_case c JOIN case_session cs ON cs.case_id = c.case_id
				 WHERE c.verdict IS NOT NULL""", rs -> {
			out.merge(rs.getLong(1), !"NORMAL".equals(rs.getString(2)), Boolean::logicalOr);
		});
		return out;
	}

	private static Diff diff(long memberId, Score a, Score b, Map<Long, Boolean> judged) {
		Boolean j = judged.get(memberId);
		return new Diff(memberId, a == null ? null : a.score(), b == null ? null : b.score(), a == null ? null : a.type(),
				b == null ? null : b.type(), j == null ? null : j ? "BOT" : "HUMAN");
	}
}
