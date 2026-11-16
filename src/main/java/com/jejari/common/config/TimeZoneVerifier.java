package com.jejari.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 기동 시 시간대 점검 (concurrency.md §0.7, T34).
 *
 * <p>세 곳이 모두 +09:00이어야 마감 시각 비교가 맞습니다. 하나라도 다르면 9시간 어긋난 값이
 * 저장되거나 비교되므로, 경고로 넘기지 않고 기동을 멈춥니다.
 * <ul>
 *   <li>JVM: JejariApplication.main에서 고정</li>
 *   <li>DB 세션: hikari connection-init-sql (application.yml)</li>
 *   <li>Oracle OS: 컨테이너 TZ=Asia/Seoul (compose.yaml). SYSTIMESTAMP가 이 값을 따릅니다</li>
 * </ul>
 */
@Component
public class TimeZoneVerifier implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(TimeZoneVerifier.class);
	private static final String EXPECTED = "+09:00";

	private final JdbcTemplate jdbcTemplate;

	public TimeZoneVerifier(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void run(ApplicationArguments args) {
		String jvm = java.time.ZoneId.systemDefault().getRules()
				.getOffset(java.time.Instant.now()).getId();
		String[] db = jdbcTemplate.queryForObject(
				"SELECT TO_CHAR(CURRENT_TIMESTAMP, 'TZH:TZM'), TO_CHAR(SYSTIMESTAMP, 'TZH:TZM') FROM dual",
				(rs, i) -> new String[] {rs.getString(1), rs.getString(2)});
		String session = db[0];
		String os = db[1];

		if (!EXPECTED.equals(jvm) || !EXPECTED.equals(session) || !EXPECTED.equals(os)) {
			throw new IllegalStateException(String.format(
					"시간대 불일치 (concurrency.md §0.7): JVM=%s, DB 세션=%s, Oracle OS=%s (모두 %s여야 함)",
					jvm, session, os, EXPECTED));
		}
		log.info("시간대 점검 통과: JVM·DB 세션·Oracle OS 모두 {}", EXPECTED);
	}
}
