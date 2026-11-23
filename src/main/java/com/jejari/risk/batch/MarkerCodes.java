package com.jejari.risk.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jejari.risk.RiskRule;

/**
 * case_marker.rule_code CHECK가 지금 받아 주는 규칙 코드 (DB에서 읽음, 5분 캐시).
 *
 * <p>새 마커 규칙(예: INHUMAN_STEP)은 코드가 먼저 나가고 ERD 패치(CHECK에 값 추가)가 뒤에 올 수 있다. 그 사이에
 * CHECK에 없는 코드로 마커를 넣으면 ORA-02290으로 <b>점수 저장까지 롤백</b>되고, 다음 배치도 같은 실패를 되풀이한다.
 * 그래서 케이스를 열기 전에 받아 주는 코드인지 보고, 아니면 그 마커만 빼서 점수는 저장한다.
 * ERD 패치가 들어오면 5분 안에 재배포 없이 케이스 규칙으로 켜진다 (예매 완료 알림 CONFIRMED 때와 같은 방식).
 *
 * <ul>
 *   <li>제약을 못 읽으면(권한·DB 장애) <b>기존 마커 규칙만</b> 허용한다 — 모르는 값으로 넣다 롤백되는 것보다 안전</li>
 *   <li>제약이 아예 없으면(CHECK 없는 DB) 모두 허용</li>
 * </ul>
 */
@Component
public class MarkerCodes {
	private static final Logger log = LoggerFactory.getLogger(MarkerCodes.class);
	static final Duration TTL = Duration.ofMinutes(5);
	/** ERD v24까지 CHECK에 있던 마커 규칙. 제약을 못 읽을 때 이것만 허용 */
	static final Set<String> KNOWN = Set.of("FAST_CLICK", "SAME_SEAT_RETRY", "CANCEL_AFTER_PAY", "MULTI_ACCOUNT_SYNC",
			"REGULAR_INTERVAL", "LINEAR_MOUSE");
	private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

	private final JdbcTemplate jdbc;
	private final Clock clock;
	private volatile Set<String> allowed;
	private volatile Instant readAt = Instant.EPOCH;

	public MarkerCodes(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	public boolean allowed(RiskRule rule) {
		Instant now = clock.instant();
		if (allowed == null || now.isAfter(readAt.plus(TTL))) {
			allowed = read();
			readAt = now;
		}
		return allowed == null || allowed.contains(rule.name());
	}

	/** @return 허용 코드. CHECK가 없으면 null(모두 허용) */
	private Set<String> read() {
		try {
			List<String> conds = jdbc.queryForList("""
					SELECT search_condition FROM user_constraints
					 WHERE table_name = 'CASE_MARKER' AND constraint_type = 'C'
					   AND constraint_name = 'CK_CASE_MARKER_RULE_CODE'""", String.class);
			if (conds.isEmpty() || conds.get(0) == null) {
				return null;
			}
			return parse(conds.get(0));
		} catch (RuntimeException e) {
			log.warn("case_marker.rule_code 제약을 읽지 못해 기존 마커 규칙만 케이스로 엶: {}", e.toString());
			return KNOWN;
		}
	}

	/** "rule_code IN ('FAST_CLICK', 'SAME_SEAT_RETRY')" → {FAST_CLICK, SAME_SEAT_RETRY} */
	static Set<String> parse(String condition) {
		Set<String> out = new LinkedHashSet<>();
		Matcher m = QUOTED.matcher(condition);
		while (m.find()) {
			out.add(m.group(1));
		}
		return out;
	}
}
