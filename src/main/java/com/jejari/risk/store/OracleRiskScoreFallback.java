package com.jejari.risk.store;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;
import com.jejari.queue.port.RiskScoreFallbackPort;
import com.jejari.risk.SubjectType;
import com.jejari.risk.cache.RiskScoreCache;

/**
 * 대기열(A)의 {@link RiskScoreFallbackPort} 구현. Redis 캐시가 준비되지 않았을 때(risk:meta:synced_at 없음)
 * Oracle {@code risk_score}에서 회원의 CI·결제 수단·기기 점수 중 최댓값을 읽는다. 없으면 0.
 *
 * <p>해시 형식이 잘못됐거나 조회가 실패하면 예외를 던지고, 대기열은 최대 패널티(100)로 처리한다 (모르면 막는 쪽).
 * 개발 모드에서는 Oracle 점수 원본이 없으므로 A의 {@code DevRiskScoreFallbackPort}가 대신한다.
 */
@Component
@ConditionalOnProperty(name = "jejari.dev-mode", havingValue = "false", matchIfMissing = true)
public class OracleRiskScoreFallback implements RiskScoreFallbackPort {

	private final JdbcTemplate jdbc;

	public OracleRiskScoreFallback(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public double maxScore(MemberIdentity m) {
		StringBuilder where = new StringBuilder();
		List<Object> args = new ArrayList<>();
		add(where, args, SubjectType.CI, m.ciHash() == null ? List.of() : List.of(m.ciHash()));
		add(where, args, SubjectType.PAYMENT, m.paymentHashes());
		add(where, args, SubjectType.DEVICE, m.deviceHashes());
		if (args.isEmpty()) {
			return 0;
		}
		BigDecimal max = jdbc.queryForObject("SELECT MAX(final_score) FROM risk_score WHERE " + where,
				BigDecimal.class, args.toArray());
		return max == null ? 0 : max.doubleValue();
	}

	/** (subject_type = ? AND subject_hash IN (?, …)) 조건을 붙인다. SQL에는 값이 아니라 자리표시자만 들어간다. */
	private static void add(StringBuilder where, List<Object> args, SubjectType type, List<String> hashes) {
		if (hashes == null || hashes.isEmpty()) {
			return;
		}
		hashes.forEach(h -> RiskScoreCache.key(type, h)); // 형식 검사 (잘못되면 예외 → 대기열이 100점)
		if (!where.isEmpty()) {
			where.append(" OR ");
		}
		where.append("(subject_type = ? AND subject_hash IN (")
				.append(String.join(", ", java.util.Collections.nCopies(hashes.size(), "?")))
				.append("))");
		args.add(type.name());
		args.addAll(hashes);
	}
}
