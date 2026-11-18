package com.jejari.reservation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.jejari.payment.LateApprovalRefund;

/**
 * CNL-03 환불 재시도 배치 (concurrency.md §7.3·§8.2, 기능설계서 4.6). {@code refund_status}가 REQUESTED·FAILED인
 * 취소를 주기적으로 다시 환불 요청한다.
 *
 * <pre>
 * 대상: refund_status IN (REQUESTED, FAILED)
 *       AND updated_at  &lt; 지금 − 유예(grace)      ← 막 커밋돼 커밋 뒤 환불이 진행 중인 건은 건드리지 않음
 *       AND canceled_at &gt; 지금 − 포기 시간(give-up) ← 그보다 오래된 건은 사람이 처리 (로그 ERROR)
 * 건마다: USER·ADMIN → ReservationCancelService.retryRefund, PAY_FAILED → LateApprovalRefund.refund (길동현)
 * </pre>
 *
 * <ul>
 *   <li><b>두 번 환불되지 않음</b>: 처음 환불·재시도 모두 환불 키 {@code cancel:<cancel_id>}로 보내 PG가 한 번만 환불한다.
 *       여러 대가 동시에 돌아도 같다. DONE은 {@code refund_status <> 'DONE'} 조건부 UPDATE라 알림 이벤트도 한 번만.</li>
 *   <li><b>한 건이 실패해도 계속</b>: 실패한 건은 FAILED로 남아 다음 주기에 다시 시도한다.</li>
 *   <li><b>트랜잭션 밖에서 PG 호출</b> (§0.6): 이 배치는 트랜잭션 없이 돌고, 결과 기록만 짧은 트랜잭션.</li>
 *   <li>cancellation은 쓰기 폭주 테이블이 아니고 이 조건 행은 적어서 인덱스 없이 조회한다 (오프피크 풀스캔 검토, CLAUDE.md).</li>
 * </ul>
 */
@Component
public class RefundRetryBatch {
	private static final Logger log = LoggerFactory.getLogger(RefundRetryBatch.class);

	private final JdbcTemplate jdbc;
	private final ReservationCancelService cancels;
	private final LateApprovalRefund lateApproval;
	private final Clock clock;
	private final boolean enabled;
	private final Duration grace;
	private final Duration giveUp;
	private final int batchSize;

	public RefundRetryBatch(JdbcTemplate jdbc, ReservationCancelService cancels, LateApprovalRefund lateApproval,
			Clock clock, @Value("${jejari.refund.retry-enabled:true}") boolean enabled,
			@Value("${jejari.refund.retry-grace:PT1M}") Duration grace,
			@Value("${jejari.refund.retry-give-up:PT72H}") Duration giveUp,
			@Value("${jejari.refund.retry-batch-size:100}") int batchSize) {
		this.jdbc = jdbc;
		this.cancels = cancels;
		this.lateApproval = lateApproval;
		this.clock = clock;
		this.enabled = enabled;
		this.grace = grace;
		this.giveUp = giveUp;
		this.batchSize = batchSize;
	}

	/** @param tried 다시 요청한 건수 · done 이번에 DONE이 된 건수 · failed 아직 실패 · givenUp 포기 시간이 지나 사람이 볼 건수 */
	public record Result(int tried, int done, int failed, long givenUp) {
	}

	@Scheduled(fixedDelayString = "${jejari.refund.retry-interval:PT5M}", initialDelayString = "PT1M")
	void tick() {
		if (enabled) {
			run();
		}
	}

	public Result run() {
		Instant now = clock.instant();
		List<Map<String, Object>> targets = jdbc.queryForList("""
				SELECT cancel_id, cancel_type FROM cancellation
				 WHERE refund_status IN ('REQUESTED', 'FAILED') AND updated_at < ? AND canceled_at > ?
				 ORDER BY cancel_id FETCH FIRST ? ROWS ONLY""",
				Timestamp.from(now.minus(grace)), Timestamp.from(now.minus(giveUp)), batchSize);
		int done = 0;
		int failed = 0;
		for (Map<String, Object> t : targets) {
			long cancelId = ((Number) t.get("CANCEL_ID")).longValue();
			String type = (String) t.get("CANCEL_TYPE");
			String result;
			try {
				result = "PAY_FAILED".equals(type) ? lateApproval.refund(cancelId) : cancels.retryRefund(cancelId);
			} catch (RuntimeException e) {
				log.warn("환불 재시도 중 오류 (취소 {}), 다음 주기에 다시: {}", cancelId, e.toString());
				result = "FAILED";
			}
			if ("DONE".equals(result)) {
				done++;
			} else {
				failed++;
			}
		}
		Long givenUp = jdbc.queryForObject("""
				SELECT COUNT(*) FROM cancellation WHERE refund_status IN ('REQUESTED', 'FAILED') AND canceled_at <= ?""",
				Long.class, Timestamp.from(now.minus(giveUp)));
		long stuck = givenUp == null ? 0 : givenUp;
		if (stuck > 0) {
			log.error("환불 재시도 포기 시간({})이 지난 미환불 취소 {}건 → 사람이 처리해야 함", giveUp, stuck);
		}
		if (!targets.isEmpty()) {
			log.info("환불 재시도: {}건 시도, 완료 {}, 실패 {}", targets.size(), done, failed);
		}
		return new Result(targets.size(), done, failed, stuck);
	}
}
