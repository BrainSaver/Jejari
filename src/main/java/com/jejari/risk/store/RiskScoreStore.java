package com.jejari.risk.store;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.risk.RiskResult;
import com.jejari.risk.RiskSignals;
import com.jejari.risk.SubjectType;
import com.jejari.risk.cache.RiskScoreCache;

/**
 * 위험 점수 저장 (RISK-02 결과 → Oracle risk_score → Redis risk:*). 순서는 항상 <b>Oracle 커밋 → Redis</b>.
 *
 * <ul>
 *   <li>Oracle이 원본이다. Redis 쓰기가 실패해도 Oracle 값은 남고, {@code RiskCacheResync}가 다음 주기에 맞춘다.</li>
 *   <li>Redis를 먼저 쓰면 Oracle이 롤백됐을 때 캐시에만 있는 점수가 생기므로 커밋 뒤에 쓴다.
 *       호출하는 쪽이 트랜잭션 안이면 그 트랜잭션이 커밋된 뒤에 쓴다.</li>
 *   <li>review_status(관리자 판정)는 덮어쓰지 않는다. 새 행만 NONE으로 만든다.</li>
 *   <li>같은 트랜잭션에서 risk_score_history에 한 행을 남긴다 (ERD: 감쇠 동작 검증·이의 제기 대응).</li>
 * </ul>
 */
@Service
public class RiskScoreStore {
	private static final Logger log = LoggerFactory.getLogger(RiskScoreStore.class);

	/** model_version은 model_run FK. 현재 켜진 규칙 모델 버전을 쓰고, 없으면 NULL */
	private static final String ACTIVE_RULE_MODEL =
			"(SELECT MAX(model_version) FROM model_run WHERE algorithm = 'RULE' AND is_active = 'Y')";

	private static final String MERGE = """
			MERGE INTO risk_score t
			USING (SELECT ? subject_type, ? subject_hash FROM dual) s
			   ON (t.subject_type = s.subject_type AND t.subject_hash = s.subject_hash)
			WHEN MATCHED THEN UPDATE SET
			     total_purchase_seats = ?, max_single_purchase = ?, cancel_ratio = ?, avg_minutes_to_cancel = ?,
			     last_minute_cancel_cnt = ?, linked_account_cnt = ?, avg_request_interval_ms = ?, account_age_days = ?,
			     rule_score = ?, final_score = ?, reasons = ?, calculated_at = ?, model_version = %1$s,
			     updated_at = SYSTIMESTAMP
			WHEN NOT MATCHED THEN INSERT
			     (subject_type, subject_hash, total_purchase_seats, max_single_purchase, cancel_ratio, avg_minutes_to_cancel,
			      last_minute_cancel_cnt, linked_account_cnt, avg_request_interval_ms, account_age_days,
			      rule_score, final_score, reasons, calculated_at, model_version, review_status)
			     VALUES (s.subject_type, s.subject_hash, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, %1$s, 'NONE')""".formatted(ACTIVE_RULE_MODEL);

	private static final String HISTORY = """
			INSERT INTO risk_score_history (history_id, subject_type, subject_hash, final_score, reasons, calculated_at)
			VALUES (risk_score_history_seq.NEXTVAL, ?, ?, ?, ?, ?)""";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final RiskScoreCache cache;

	public RiskScoreStore(JdbcTemplate jdbc, TransactionTemplate tx, RiskScoreCache cache) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.cache = cache;
	}

	/** 엔진 계산 결과를 저장하고, 커밋된 뒤 캐시를 갱신한다. */
	public void save(RiskResult r) {
		RiskSignals s = r.signals();
		Object[] values = {
				s.totalPurchaseSeats(), s.maxSinglePurchase(), r.cancelRatioForDb(), s.avgMinutesToCancel(),
				s.lastMinuteCancelCount(), s.linkedAccountCount(),
				s.intervalCount() == 0 ? null : Math.round(s.intervalMeanMs()), s.accountAgeDays(),
				r.ruleScore(), r.finalScore(), r.reasonsJson(), Timestamp.from(r.calculatedAt())};
		Object[] args = new Object[2 + values.length * 2];
		args[0] = s.subjectType().name();
		args[1] = s.subjectHash();
		System.arraycopy(values, 0, args, 2, values.length);
		System.arraycopy(values, 0, args, 2 + values.length, values.length);

		tx.executeWithoutResult(st -> {
			jdbc.update(MERGE, args);
			jdbc.update(HISTORY, s.subjectType().name(), s.subjectHash(), r.finalScore(), r.reasonsJson(),
					Timestamp.from(r.calculatedAt()));
		});
		afterCommit(() -> cache.put(s.subjectType(), s.subjectHash(), r.finalScore()));
	}

	/**
	 * Oracle의 현재 값을 캐시에 다시 쓴다. 관리자 판정(ADM-06, CLEAR 등)으로 risk_score를 바꾼 쪽이 커밋 뒤에 부른다.
	 * 다음 재동기화(최대 10분)를 기다리지 않고 바로 반영하기 위해서다. 행이 없으면 키를 지운다.
	 */
	public void refreshCache(SubjectType type, String hash) {
		RiskScoreCache.key(type, hash); // 형식 검사
		afterCommit(() -> {
			List<BigDecimal> rows = jdbc.queryForList(
					"SELECT final_score FROM risk_score WHERE subject_type = ? AND subject_hash = ?",
					BigDecimal.class, type.name(), hash);
			cache.put(type, hash, rows.isEmpty() ? BigDecimal.ZERO : rows.get(0));
		});
	}

	/** 바깥 트랜잭션이 있으면 그 커밋 뒤에, 없으면 바로 실행. Redis 실패는 로그만 남긴다 (재동기화가 복구). */
	private void afterCommit(Runnable cacheWrite) {
		Runnable safe = () -> {
			try {
				cacheWrite.run();
			} catch (RuntimeException e) {
				log.warn("위험 점수 캐시 쓰기 실패. 재동기화가 맞출 때까지 대기열에 이전 값이 보입니다", e);
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					safe.run();
				}
			});
		} else {
			safe.run();
		}
	}
}
