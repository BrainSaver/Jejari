package com.jejari.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 오래된 행 정리 (concurrency.md §11.5, §12). 오프피크(기본 매일 04:10)에 조금씩 나눠 지워 언두·락을 짧게 유지합니다.
 *
 * <ul>
 * <li>outbox_event: PUBLISHED이고 published_at이 outbox.retention_hours보다 오래된 행</li>
 * <li>processed_event: processed_at이 inbox.retention_days보다 오래된 행. Kafka 보관(7일)보다 길어야 재전달 중복을 막습니다</li>
 * </ul>
 */
public class EventCleanup {

	private static final Logger log = LoggerFactory.getLogger(EventCleanup.class);
	private static final int CHUNK = 5000;

	private final JdbcTemplate jdbc;
	private final EventPolicy policy;

	public EventCleanup(JdbcTemplate jdbc, EventPolicy policy) {
		this.jdbc = jdbc;
		this.policy = policy;
	}

	@Scheduled(cron = "${jejari.event.cleanup-cron:0 10 4 * * *}", zone = "Asia/Seoul")
	public void run() {
		long outbox = deleteInChunks("DELETE FROM outbox_event WHERE status = 'PUBLISHED' "
				+ "AND published_at < SYSTIMESTAMP - NUMTODSINTERVAL(?, 'HOUR') AND ROWNUM <= " + CHUNK,
				policy.getLong(EventPolicy.OUTBOX_RETENTION_HOURS));
		long inbox = deleteInChunks("DELETE FROM processed_event "
				+ "WHERE processed_at < SYSTIMESTAMP - NUMTODSINTERVAL(?, 'DAY') AND ROWNUM <= " + CHUNK,
				policy.getLong(EventPolicy.INBOX_RETENTION_DAYS));
		log.info("이벤트 정리: outbox {}건, inbox {}건 삭제", outbox, inbox);
	}

	private long deleteInChunks(String sql, long amount) {
		long total = 0;
		int n;
		do {
			n = jdbc.update(sql, amount); // 자동 커밋: 덩어리마다 커밋
			total += n;
		} while (n == CHUNK);
		return total;
	}
}
