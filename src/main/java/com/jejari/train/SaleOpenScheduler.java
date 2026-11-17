package com.jejari.train;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.jejari.common.config.AppPolicy;

/**
 * ADM-02 판매 오픈 스케줄러: {@code train_run.status}를 시각에 맞춰 옮긴다.
 *
 * <pre>
 * SCHEDULED --(sale_open_at 지남)--> ON_SALE --(sale_close_at 지남)--> CLOSED
 * </pre>
 *
 * <p>오픈 시각은 시드가 규칙으로 계산해 넣는다 (일반은 출발 2개월 전 07:00, 명절은 행사 오픈 시각).
 * 시드는 넣는 순간의 시각으로 status를 한 번 정할 뿐이라, 그 뒤로 시각이 지나도 아무도 바꿔 주지 않았다.
 * 판매 중 여부의 원천이 {@code status}이므로({@link TrainRunReader}) 오픈 시각이 지난 운행이 계속
 * SCHEDULED로 남아 대기열·좌석 선점이 열리지 않는다. 그 자리를 메우는 것이 이 스케줄러다.
 *
 * <p><b>마감을 먼저, 오픈을 나중에</b> 훑는다. 오픈·마감이 모두 지난 운행(지난 날짜 운행)을
 * ON_SALE로 한 번 올렸다가 곧바로 CLOSED로 내리는 헛걸음을 없애려는 것이다.
 *
 * <p>앱이 여러 대면 이 스케줄러도 여러 대에서 돈다. 두 UPDATE 모두 현재 status를 WHERE에 넣은
 * 조건부 UPDATE(concurrency.md §0.3)라 같은 운행을 두 대가 잡아도 한쪽만 바꾼다
 * (멀티인스턴스 코딩 규칙 3번). {@code train_run}에는 version 컬럼이 없어 {@code updated_at}만 올린다.
 *
 * <p>시각 비교는 모두 Oracle {@code SYSTIMESTAMP}로 한다. 앱이 여러 대일 때 각자의 시계로
 * 비교하면 같은 운행의 오픈 시점이 대마다 달라진다. 비교 기준은 DB 한 곳이어야 한다.
 *
 * <p>주기는 app_policy {@code sale.open_interval_sec}(10초). 1초마다 깨어나 정책 주기가 지났을 때만
 * 돈다 ({@link com.jejari.seat.HoldExpiryScanner}와 같은 방식 — 정책을 바꾸면 재기동 없이 반영).
 */
@Component
@ConditionalOnProperty(name = "jejari.train.sale-open-scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class SaleOpenScheduler {

	private static final Logger log = LoggerFactory.getLogger(SaleOpenScheduler.class);

	/** 판매 마감: 마감 시각이 지난 SCHEDULED·ON_SALE 운행 (이미 CLOSED·DEPARTED인 것은 건드리지 않음) */
	private static final String CLOSE = """
			UPDATE train_run
			   SET status = 'CLOSED', updated_at = SYSTIMESTAMP
			 WHERE status IN ('SCHEDULED', 'ON_SALE')
			   AND sale_close_at IS NOT NULL
			   AND sale_close_at <= SYSTIMESTAMP""";

	/** 판매 오픈: 오픈 시각이 지났고 아직 마감되지 않은 SCHEDULED 운행 */
	private static final String OPEN = """
			UPDATE train_run
			   SET status = 'ON_SALE', updated_at = SYSTIMESTAMP
			 WHERE status = 'SCHEDULED'
			   AND sale_open_at <= SYSTIMESTAMP
			   AND (sale_close_at IS NULL OR sale_close_at > SYSTIMESTAMP)""";

	private final JdbcTemplate jdbc;
	private final AppPolicy policy;
	private volatile long lastRunNanos = System.nanoTime() - Long.MAX_VALUE / 2;

	public SaleOpenScheduler(JdbcTemplate jdbc, AppPolicy policy) {
		this.jdbc = jdbc;
		this.policy = policy;
	}

	@Scheduled(fixedDelay = 1000, initialDelay = 5000)
	void tick() {
		long intervalNanos = policy.getLong(AppPolicy.SALE_OPEN_INTERVAL_SEC) * 1_000_000_000L;
		if (System.nanoTime() - lastRunNanos < intervalNanos) {
			return;
		}
		lastRunNanos = System.nanoTime();
		try {
			sweepOnce();
		} catch (RuntimeException e) {
			log.warn("판매 오픈 스캔 실패 (다음 주기에 다시)", e);
		}
	}

	/**
	 * 마감 → 오픈 순서로 한 번 훑는다. 두 UPDATE는 각자의 트랜잭션이다 (한쪽이 실패해도 다른 쪽은 남는다).
	 *
	 * @return 바뀐 운행 수 (마감 + 오픈)
	 */
	public Swept sweepOnce() {
		int closed = jdbc.update(CLOSE);
		int opened = jdbc.update(OPEN);
		if (closed > 0 || opened > 0) {
			log.info("판매 상태 전이: 오픈 {}건, 마감 {}건", opened, closed);
		}
		return new Swept(opened, closed);
	}

	/** 한 번 훑은 결과 */
	public record Swept(int opened, int closed) {
	}
}
