package com.jejari.risk.batch;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jejari.risk.RiskPolicy;

/**
 * app_policy의 {@code risk.rule.*} 값으로 만든 규칙 정책. 배치마다 다시 읽는다 (1분에 한 번이라 캐시가 필요 없음).
 *
 * <p>값이 잘못됐거나(단계 역전, NaN, 0 …) DB를 못 읽으면 <b>마지막으로 성공한 정책을 그대로 쓴다</b>.
 * 기본값으로 돌아가면 운영자가 일부러 강하게 바꾼 문턱이 조용히 풀릴 수 있기 때문이다. 처음부터 실패하면 기본값.
 */
@Component
public class RiskPolicySource {
	private static final Logger log = LoggerFactory.getLogger(RiskPolicySource.class);

	private final JdbcTemplate jdbc;
	private volatile RiskPolicy last = RiskPolicy.defaults();

	public RiskPolicySource(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public RiskPolicy current() {
		try {
			Map<String, String> values = new HashMap<>();
			jdbc.query("SELECT policy_key, policy_value FROM app_policy WHERE policy_key LIKE 'risk.rule.%'",
					rs -> {
						values.put(rs.getString(1), rs.getString(2));
					});
			last = RiskPolicy.fromMap(values);
		} catch (RuntimeException e) {
			log.error("위험 점수 규칙(app_policy risk.rule.*)을 쓸 수 없어 이전 정책을 유지: {}", e.getMessage());
		}
		return last;
	}
}
