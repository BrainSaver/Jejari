package com.jejari.group;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.jejari.common.config.AppPolicy;
import com.jejari.group.GroupAllocator.Outcome;

/**
 * 미룬 단체 승인 다시 하기 (concurrency.md §18.3b). 1분마다.
 *
 * <ol>
 * <li>REQUESTED 신청을 운행별로 신청 순서({@code created_at, group_id})대로 다시 승인 시도 (§18.3 처음부터).</li>
 * <li>앞 신청이 미뤄지면(DEFERRED) 그 운행의 뒤 신청은 할당석만으로 되는 것만 승인한다. 할당석은 개인과 겨루지 않기 때문.</li>
 * <li>마감이 지나도록 승인 못 한 신청은 REJECTED (일반 좌석 부족). 마감 = 할당석 반납 시각(출발 − group.quota_release_days).
 *     반납 시각 뒤에 들어온 신청은 출발 1일 전.</li>
 * </ol>
 *
 * <p>앱이 여러 대여도 된다: 승인 트랜잭션이 {@code group_booking}을 {@code FOR UPDATE SKIP LOCKED}로 잡으므로,
 * 다른 앱이 처리 중인 신청은 기다리지 않고 건너뛰고, 한 신청은 한 번만 승인된다. 위험 점수 때문에 관리자 판단을 기다리는
 * 신청(REVIEW)은 순서를 막지 않는다.
 */
@Component
@ConditionalOnProperty(name = "jejari.group.retry-scanner-enabled", havingValue = "true", matchIfMissing = true)
public class GroupRetryScanner {

	private static final Logger log = LoggerFactory.getLogger(GroupRetryScanner.class);
	static final String REJECT_REASON = "일반 좌석 부족 (할당석 반납 시각까지 배정하지 못함)";
	private static final int BATCH = 200;

	private final JdbcTemplate jdbc;
	private final GroupAllocator allocator;
	private final AppPolicy policy;

	GroupRetryScanner(JdbcTemplate jdbc, GroupAllocator allocator, AppPolicy policy) {
		this.jdbc = jdbc;
		this.allocator = allocator;
		this.policy = policy;
	}

	@Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
	void tick() {
		try {
			rejectOverdue();
			retry();
		} catch (RuntimeException e) {
			log.warn("단체 승인 재시도 실패 (다음 주기에 다시)", e);
		}
	}

	/** §18.3b-3. 반려한 신청 수 */
	int rejectOverdue() {
		String dep = "(SELECT MIN(s.departure_time) FROM train_stop s WHERE s.run_id = b.run_id)";
		long days = policy.getLong(AppPolicy.GROUP_QUOTA_RELEASE_DAYS);
		int n = jdbc.update("""
				UPDATE group_booking b SET status = 'REJECTED', cancel_reason = ?, updated_at = SYSTIMESTAMP
				 WHERE b.status = 'REQUESTED'
				   AND ((b.created_at < %1$s - NUMTODSINTERVAL(?, 'DAY') AND %1$s - NUMTODSINTERVAL(?, 'DAY') <= SYSTIMESTAMP)
				        OR %1$s - NUMTODSINTERVAL(1, 'DAY') <= SYSTIMESTAMP)""".formatted(dep),
				REJECT_REASON, days, days);
		if (n > 0) {
			log.info("단체 신청 {}건 반려 (마감까지 좌석 배정 못 함)", n);
		}
		return n;
	}

	/** §18.3b-1·2. 승인한 신청 수 */
	int retry() {
		record Row(long groupId, long runId) {
		}
		List<Row> rows = jdbc.query("""
				SELECT group_id, run_id FROM group_booking WHERE status = 'REQUESTED'
				 ORDER BY run_id, created_at, group_id FETCH FIRST %d ROWS ONLY""".formatted(BATCH),
				(rs, i) -> new Row(rs.getLong(1), rs.getLong(2)));
		int approved = 0;
		long run = -1;
		boolean deferred = false;
		for (Row r : rows) {
			if (r.runId() != run) {
				run = r.runId();
				deferred = false;
			}
			Outcome o;
			try {
				o = allocator.approve(r.groupId(), null, deferred, true).outcome();
			} catch (RuntimeException e) {
				log.warn("단체 {} 승인 재시도 실패", r.groupId(), e);
				continue;
			}
			if (o == Outcome.APPROVED) {
				approved++;
			} else if (o == Outcome.DEFERRED) {
				deferred = true; // 뒤 신청은 할당석만으로 (신청 순서 유지)
			}
		}
		return approved;
	}
}
