package com.jejari.seat;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.jejari.common.config.AppPolicy;

/**
 * RSV-03 결제 기한 초과 자동 반환: HOLD 만료 스캐너 (concurrency.md §6.1).
 *
 * <p>PG 결과 확인(§6.2, C 길동현)과 <b>쿼리를 나눠</b> 둔다. 결과를 모르는 결제가 아무리 쌓여도
 * HOLD 만료가 밀리지 않게 하려는 것이다 (T27). 조회식은 인덱스 {@code ix_resv_hold_due}와 같은 CASE 식이라
 * HOLD인 행만 인덱스에 들어 있다.
 *
 * <p>앱이 여러 대면 스캐너도 여러 대에서 돈다. 건마다 조건부 UPDATE(§7.1)라 같은 예약을 두 대가 잡아도
 * 한쪽만 처리한다 (멀티인스턴스 코딩 규칙 3번).
 *
 * <p>주기·건수는 app_policy {@code scanner.hold_interval_sec}(5초)·{@code scanner.batch_size}(500건).
 * 1초마다 깨어나 정책 주기가 지났을 때만 돈다 (정책을 바꾸면 재기동 없이 반영).
 */
@Component
@ConditionalOnProperty(name = "jejari.seat.expiry-scanner-enabled", havingValue = "true", matchIfMissing = true)
public class HoldExpiryScanner {

	private static final Logger log = LoggerFactory.getLogger(HoldExpiryScanner.class);

	private static final String DUE = """
			SELECT reservation_id FROM reservation
			 WHERE (CASE WHEN status = 'HOLD' THEN pay_deadline_at END) < SYSTIMESTAMP
			 FETCH FIRST ? ROWS ONLY""";

	private final JdbcTemplate jdbc;
	private final SeatReleaseService release;
	private final AppPolicy policy;
	private volatile long lastRunNanos = System.nanoTime() - Long.MAX_VALUE / 2;

	public HoldExpiryScanner(JdbcTemplate jdbc, SeatReleaseService release, AppPolicy policy) {
		this.jdbc = jdbc;
		this.release = release;
		this.policy = policy;
	}

	private RedisOutageGuard outage;   // 없으면(단위 시험·끈 경우) 늘 돈다

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	void setOutageGuard(RedisOutageGuard outage) {
		this.outage = outage;
	}

	@Scheduled(fixedDelay = 1000, initialDelay = 5000)
	void tick() {
		if (outage != null && outage.redisDown()) {
			return; // §6.3: Redis가 죽어 결제를 못 하는 동안에는 만료시키지 않는다 (복구 뒤 장애 시간만큼 기한을 늘림)
		}
		long intervalNanos = policy.getLong(AppPolicy.SCANNER_HOLD_INTERVAL_SEC) * 1_000_000_000L;
		if (System.nanoTime() - lastRunNanos < intervalNanos) {
			return;
		}
		lastRunNanos = System.nanoTime();
		try {
			scanOnce();
		} catch (RuntimeException e) {
			log.warn("HOLD 만료 스캔 실패 (다음 주기에 다시)", e);
		}
	}

	/** 기한이 지난 HOLD를 최대 batch_size건 읽어 건마다 별도 트랜잭션으로 만료시킨다. @return 만료시킨 건수 */
	public int scanOnce() {
		List<Long> due = jdbc.queryForList(DUE, Long.class, policy.getLong(AppPolicy.SCANNER_BATCH_SIZE));
		int expired = 0;
		for (long r : due) {
			try {
				if (release.expire(r)) {
					expired++;
				}
			} catch (RuntimeException e) {
				// 한 건 실패가 나머지를 막지 않게. 이 예약은 다음 주기에 다시 후보로 나온다
				log.warn("예약 {} 만료 처리 실패", r, e);
			}
		}
		if (expired > 0) {
			log.info("HOLD 만료 {}건 / 후보 {}건", expired, due.size());
		}
		return expired;
	}
}
