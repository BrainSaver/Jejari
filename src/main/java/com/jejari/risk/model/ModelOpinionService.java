package com.jejari.risk.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jejari.risk.admin.CaseAdminService;
import com.jejari.risk.admin.CaseAdminService.Account;
import com.mongodb.client.model.Filters;

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
	 * @param by     가장 높게 본 모델 GBM / IF / GRU (GRU면 근거 숫자로는 이유가 잘 안 보여 화면 녹화 재생을 권함)
	 * @param scores 모델별 점수
	 */
	public record AccountScore(String label, long memberId, double score, boolean overThreshold, String by,
			Map<String, Object> scores, List<String> reasons, String source, Instant scoredAt) {
	}

	private record ActiveModel(String version, BigDecimal threshold, Instant trainedAt, String trainSource) {
	}

	public Opinion forCase(long caseId) {
		List<Account> accounts = cases.caseAccounts(caseId); // 없는 케이스면 여기서 404
		if (!show) {
			return hidden("관리자 화면에 모델 점수를 보이지 않게 설정돼 있어요 (jejari.model.show=false, 봇 대회 중 등)");
		}
		List<ActiveModel> active = jdbc.query("""
				SELECT model_version, threshold, trained_at, train_source FROM model_run
				 WHERE algorithm = 'ENSEMBLE' AND is_active = 'Y' FETCH FIRST 1 ROWS ONLY""",
				(rs, i) -> new ActiveModel(rs.getString(1), rs.getBigDecimal(2),
						rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getString(4)));
		if (active.isEmpty()) {
			return hidden("사용 중인 모델이 없어요 (tools/ml/load_scores.sh --activate 로 켬)");
		}
		ActiveModel m = active.get(0);
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
				out.add(new AccountScore(labelOf.get(memberId), memberId, score,
						m.threshold() != null && score >= m.threshold().doubleValue(), d.getString("by"),
						scores instanceof Map<?, ?> sm ? toMap(sm) : Map.of(), d.getList("reasons", String.class, List.of()),
						d.getString("source"), at == null ? null : at.toInstant()));
			}
		}
		out.sort(Comparator.comparingDouble(AccountScore::score).reversed());
		return new Opinion(true, null, m.version(), m.threshold(), m.trainedAt(), m.trainSource(), out,
				accounts.size() - out.size());
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
