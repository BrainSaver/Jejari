package com.jejari.risk.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jejari.risk.admin.CaseAdminService;
import com.jejari.risk.SubjectType;
import com.jejari.risk.admin.CaseAdminService.Account;
import com.jejari.risk.batch.RiskInputReader;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;

/**
 * RISK-05 모델 보조 의견 (설계: docs/강다인/모델-보조의견-설계.md, ERD v25).
 *
 * <pre>
 * 오프라인 Python(tools/ml/score.py) → tools/ml/load_scores.sh → MongoDB model_score (계정마다 점수·근거)
 * 이 서비스: 케이스의 연결 계정 → 사용 중인 합성 모델(model_run ENSEMBLE, is_active = Y) 버전의 점수 → 관리자 화면
 * </pre>
 *
 * <ul>
 *   <li><b>보조 의견일 뿐</b>: 대기열 순번·이용 제한·케이스 열기에는 쓰지 않는다. 그건 규칙 점수(risk_score)와 사람 판정.
 *       규칙과 모델이 다르게 볼 때 관리자가 한 번 더 보게 하는 것이 목적.</li>
 *   <li><b>숨기기</b>: {@code jejari.model.show=false}면 내보내지 않는다. 봇 대회 1차 동안 수비(사람)의 판정이 모델에
 *       끌려가지 않게 (제안서의 "규칙·점수 그대로 대회를 맞음").</li>
 *   <li>정답 라벨(봇·사람)은 적재 단계에서 빠져 있다. 근거는 특징 이름·숫자뿐이라 개인정보가 없다.</li>
 * </ul>
 */
@Service
public class ModelOpinionService {

	private final JdbcTemplate jdbc;
	private final MongoTemplate mongo;
	private final CaseAdminService cases;
	private final boolean show;

	public ModelOpinionService(JdbcTemplate jdbc, MongoTemplate mongo, CaseAdminService cases,
			@Value("${jejari.model.show:true}") boolean show) {
		this.jdbc = jdbc;
		this.mongo = mongo;
		this.cases = cases;
		this.show = show;
	}

	/**
	 * @param shown     false면 아래 칸은 비어 있음 (숨김 설정 또는 사용 중인 모델 없음) · reason이 이유
	 * @param threshold 모델 문턱 (사람 검증 세트 오탐 5%). 이 이상이면 "모델은 봇 쪽으로 봄"
	 * @param accounts  점수가 있는 계정만, 높은 순
	 */
	public record Opinion(boolean shown, String reason, String modelVersion, BigDecimal threshold, Instant trainedAt,
			String trainSource, List<AccountScore> accounts, int unscored) {
	}

	/**
	 * @param by       가장 높게 본 모델 GBM / IF / GRU (GRU면 근거 숫자로는 이유가 잘 안 보여 화면 녹화 재생을 권함)
	 * @param scores   모델별 점수
	 * @param type     가장 닮은 봇 수법 SPEED / RETRY / MULTI_ACCOUNT / EVASIVE / CANCEL / HOARD (별도 유형 모델의 추정,
	 *                 ERD v29). 문턱 아래(사람 쪽으로 봄)이면 null — 사람에게 수법 이름을 붙이지 않는다
	 * @param typeProb 그 유형의 확률 (0~1). type이 null이면 null
	 */
	public record AccountScore(String label, long memberId, double score, boolean overThreshold, String by,
			Map<String, Object> scores, List<String> reasons, String source, Instant scoredAt, String type,
			Double typeProb) {
	}

	private record ActiveModel(String version, BigDecimal threshold, Instant trainedAt, String trainSource) {
	}

	public Opinion forCase(long caseId) {
		List<Account> accounts = cases.caseAccounts(caseId); // 없는 케이스면 여기서 404
		if (!show) {
			return hidden(HIDDEN_BY_SETTING);
		}
		ActiveModel m = activeModel();
		if (m == null) {
			return hidden(NO_ACTIVE);
		}
		Map<Long, String> labelOf = new HashMap<>();
		accounts.forEach(a -> labelOf.put(a.memberId(), a.label()));
		List<AccountScore> out = new ArrayList<>();
		if (!labelOf.isEmpty()) {
			for (Document d : mongo.getCollection("model_score").find(Filters.and(
					Filters.in("member_id", new ArrayList<>(labelOf.keySet())), Filters.eq("model_version", m.version())))) {
				long memberId = ((Number) d.get("member_id")).longValue();
				double score = ((Number) d.get("score")).doubleValue();
				Object scores = d.get("scores");
				Date at = d.getDate("scored_at");
				boolean over = m.threshold() != null && score >= m.threshold().doubleValue();
				Object tp = d.get("type_prob");
				out.add(new AccountScore(labelOf.get(memberId), memberId, score, over, d.getString("by"),
						scores instanceof Map<?, ?> sm ? toMap(sm) : Map.of(), d.getList("reasons", String.class, List.of()),
						d.getString("source"), at == null ? null : at.toInstant(),
						over ? d.getString("type") : null, over && tp instanceof Number n ? n.doubleValue() : null));
			}
		}
		out.sort(Comparator.comparingDouble(AccountScore::score).reversed());
		return new Opinion(true, null, m.version(), m.threshold(), m.trainedAt(), m.trainSource(), out,
				accounts.size() - out.size());
	}

	/** A-05 목록 머리: 숨김이면 shown=false와 이유. 행마다의 점수는 {@link SubjectScore} */
	public record ListModel(boolean shown, String reason, String modelVersion, BigDecimal threshold) {
	}

	/**
	 * A-05 한 행(대상)의 모델 의견: 대상에 묶인 계정 중 <b>가장 높은</b> 점수 (ERD model_score 비고 "최댓값").
	 *
	 * @param by             그 계정을 가장 높게 본 모델 GBM / IF / GRU
	 * @param scoredAccounts 점수가 있는 연결 계정 수 (나머지는 아직 안 매겨짐)
	 */
	public record SubjectScore(double score, boolean overThreshold, String by, int scoredAccounts) {
	}

	public record SubjectKey(SubjectType type, String hash) {
	}

	public record SubjectOpinions(ListModel model, Map<SubjectKey, SubjectScore> scores) {
	}

	/**
	 * A-05 위험 사용자 목록용. 대상마다 Oracle에서 연결 계정을 찾고, 사용 중인 버전의 model_score를 한 번에 읽어 대상별 최댓값.
	 * 숨김 설정·사용 중인 모델 없음이면 점수는 비운다 (케이스 화면과 같은 규칙).
	 */
	public SubjectOpinions forSubjects(List<SubjectKey> subjects) {
		if (!show) {
			return new SubjectOpinions(new ListModel(false, HIDDEN_BY_SETTING, null, null), Map.of());
		}
		ActiveModel m = activeModel();
		if (m == null) {
			return new SubjectOpinions(new ListModel(false, NO_ACTIVE, null, null), Map.of());
		}
		ListModel header = new ListModel(true, null, m.version(), m.threshold());
		Map<SubjectKey, List<Long>> membersOf = new HashMap<>();
		Set<Long> all = new HashSet<>();
		for (SubjectKey k : subjects) {
			if (!membersOf.containsKey(k)) {
				List<Long> ids = jdbc.queryForList(RiskInputReader.membersOf(k.type()), Long.class, k.hash());
				membersOf.put(k, ids);
				all.addAll(ids);
			}
		}
		if (all.isEmpty()) {
			return new SubjectOpinions(header, Map.of());
		}
		record Best(double score, String by) {
		}
		Map<Long, Best> byMember = new HashMap<>();
		for (Document d : mongo.getCollection("model_score")
				.find(Filters.and(Filters.in("member_id", new ArrayList<>(all)), Filters.eq("model_version", m.version())))
				.projection(Projections.include("member_id", "score", "by"))) {
			byMember.put(((Number) d.get("member_id")).longValue(),
					new Best(((Number) d.get("score")).doubleValue(), d.getString("by")));
		}
		Map<SubjectKey, SubjectScore> out = new HashMap<>();
		membersOf.forEach((k, ids) -> {
			Best top = null;
			int scored = 0;
			for (long id : ids) {
				Best b = byMember.get(id);
				if (b != null) {
					scored++;
					if (top == null || b.score() > top.score()) {
						top = b;
					}
				}
			}
			if (top != null) {
				boolean over = m.threshold() != null && top.score() >= m.threshold().doubleValue();
				out.put(k, new SubjectScore(top.score(), over, top.by(), scored));
			}
		});
		return new SubjectOpinions(header, out);
	}

	private static final String HIDDEN_BY_SETTING =
			"관리자 화면에 모델 점수를 보이지 않게 설정돼 있어요 (jejari.model.show=false, 봇 대회 중 등)";
	private static final String NO_ACTIVE = "사용 중인 모델이 없어요 (tools/ml/load_scores.sh --activate 로 켬)";

	/** 사용 중인 합성 모델 버전 (ENSEMBLE에서 is_active = Y는 하나뿐, ERD v25). 없으면 null */
	private ActiveModel activeModel() {
		List<ActiveModel> active = jdbc.query("""
				SELECT model_version, threshold, trained_at, train_source FROM model_run
				 WHERE algorithm = 'ENSEMBLE' AND is_active = 'Y' FETCH FIRST 1 ROWS ONLY""",
				(rs, i) -> new ActiveModel(rs.getString(1), rs.getBigDecimal(2),
						rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getString(4)));
		return active.isEmpty() ? null : active.get(0);
	}

	private static Opinion hidden(String reason) {
		return new Opinion(false, reason, null, null, null, null, List.of(), 0);
	}

	private static Map<String, Object> toMap(Map<?, ?> m) {
		Map<String, Object> out = new HashMap<>();
		m.forEach((k, v) -> out.put(String.valueOf(k), v));
		return out;
	}
}
