package com.jejari.risk.model;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.risk.SubjectType;
import com.jejari.risk.batch.RiskSubject;
import com.jejari.risk.batch.SuspiciousCaseOpener;
import com.jejari.risk.batch.SuspiciousCaseOpener.Plan;

/**
 * ② 무작위 점검 표본 (A-09 "점검 표본 뽑기"). 최근 7일에 활동한 계정 중 <b>무작위로</b> 몇 명을 골라 케이스로 연다.
 *
 * <p>왜: 모델이 배우는 정답(관리자 판정)은 규칙·모델이 연 케이스에만 붙는다. 그것만으로 다시 학습하면 "이미 잡던 봇"만 더 잘
 * 잡고 처음 보는 수법은 계속 놓친다 (자기 확신 고리 — 봇 대회에서 규칙이 0/12였던 봇처럼, 판정을 받을 기회가 없다).
 * 의심과 상관없이 고른 계정의 판정은 치우침이 없는 정답이라, 주간 재학습(tools/ml/retrain.py)의 오탐·탐지 시험에 가장 귀하다.
 *
 * <ul>
 *   <li>케이스는 trigger_type = ADMIN, trigger_detail = "무작위 점검 표본 …"으로 연다 (tools/ml/export_labels.sh가 이 글자로 구분).
 *       판정·타임라인·재생은 보통 케이스와 같다</li>
 *   <li><b>의심 근거가 아니다.</b> 케이스를 여는 것만으로는 제한·순번에 아무 영향이 없고, 판정은 관리자가 한다</li>
 *   <li>이미 열린 케이스가 있는 명의(CI)는 건너뛴다</li>
 * </ul>
 */
@Service
public class ModelAuditService {

	private static final Logger log = LoggerFactory.getLogger(ModelAuditService.class);
	static final String DETAIL = "무작위 점검 표본 — 의심 근거 아님, 모델 학습용 판정";
	static final int MAX_COUNT = 20;
	static final Duration WINDOW = Duration.ofDays(7);

	private final JdbcTemplate jdbc;
	private final MongoTemplate mongo;
	private final SuspiciousCaseOpener opener;
	private final Clock clock;

	public ModelAuditService(JdbcTemplate jdbc, MongoTemplate mongo, SuspiciousCaseOpener opener, Clock clock) {
		this.jdbc = jdbc;
		this.mongo = mongo;
		this.opener = opener;
		this.clock = clock;
	}

	/** open = 판정 대기, judged = 판정 끝 (bots·humans로 나눔) */
	public record AuditStats(int open, int judged, int bots, int humans) {
	}

	public record AuditResult(List<Long> opened, int skipped, AuditStats stats) {
	}

	public AuditStats stats() {
		int[] c = new int[4];
		jdbc.query("""
				SELECT status, verdict FROM suspicious_case WHERE trigger_type = 'ADMIN' AND trigger_detail LIKE ?""", rs -> {
			String v = rs.getString(2);
			if (v == null) {
				c[0]++;
			} else {
				c[1]++;
				c["NORMAL".equals(v) ? 3 : 2]++;
			}
		}, "무작위 점검%");
		return new AuditStats(c[0], c[1], c[2], c[3]);
	}

	@Transactional
	public AuditResult sample(long adminId, int count) {
		if (count < 1 || count > MAX_COUNT) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		Instant now = clock.instant();
		Instant from = now.minus(WINDOW);
		// 최근 활동 계정에서 무작위 (Mongo $sample). 열린 케이스·중복 명의를 빼면 모자랄 수 있어 넉넉히 뽑는다
		List<Long> picked = new ArrayList<>();
		for (Document d : mongo.getCollection("behavior_log").aggregate(List.of(
				new Document("$match", new Document("ts", new Document("$gte", java.util.Date.from(from)))
						.append("member_id", new Document("$ne", null))),
				new Document("$group", new Document("_id", "$member_id")),
				new Document("$sample", new Document("size", count * 5))))) {
			if (d.get("_id") instanceof Number n) {
				picked.add(n.longValue());
			}
		}
		Set<String> cis = new LinkedHashSet<>();
		int skipped = 0;
		for (long m : picked) {
			if (cis.size() >= count) {
				break;
			}
			List<String> ci = jdbc.queryForList("SELECT ci_hash FROM member WHERE member_id = ? AND ci_hash IS NOT NULL",
					String.class, m);
			if (ci.isEmpty() || cis.contains(ci.get(0)) || !ci.get(0).matches("[0-9a-f]{64}")) {
				continue;
			}
			Integer open = jdbc.queryForObject("""
					SELECT COUNT(*) FROM suspicious_case
					 WHERE subject_type = 'CI' AND subject_hash = ? AND status IN ('OPEN', 'REVIEWING')""", Integer.class, ci.get(0));
			if (open != null && open > 0) {
				skipped++;
				continue;
			}
			cis.add(ci.get(0));
		}
		List<Long> opened = new ArrayList<>();
		for (String ci : cis) {
			RiskSubject s = new RiskSubject(SubjectType.CI, ci);
			List<BigDecimal> score = jdbc.queryForList(
					"SELECT final_score FROM risk_score WHERE subject_type = 'CI' AND subject_hash = ?", BigDecimal.class, ci);
			Plan p = new Plan(s, "ADMIN", DETAIL + " (관리자 #" + adminId + ")", score.isEmpty() ? BigDecimal.ZERO : score.get(0),
					from, now, now, List.of(), opener.linkedSessions(s, from, now));
			Long id = opener.open(p);
			if (id == null) {
				skipped++;
			} else {
				opened.add(id);
			}
		}
		log.info("무작위 점검 표본 {}건 열림 (요청 {}, 건너뜀 {}, 관리자 #{})", opened.size(), count, skipped, adminId);
		return new AuditResult(opened, skipped, stats());
	}
}
