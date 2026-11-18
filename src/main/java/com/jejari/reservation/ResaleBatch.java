package com.jejari.reservation;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CNL-04 재판매 여부 집계 배치 (concurrency.md §8.3). 운행이 출발(DEPARTED)한 뒤, 그 운행의 취소마다 풀린 좌석을
 * 다른 예약이 SOLD로 가졌으면 {@code cancellation.resold_yn = 'Y'}로 바꾼다. 기획 배경의 "재판매되지 못한 표" 지표.
 *
 * <pre>
 * 대상: 출발한 운행(train_run.status = DEPARTED, run_date ≥ 오늘 − lookback)의 취소 중 resold_yn = 'N'
 * 'Y' 조건: 취소된 예약의 좌석(reservation_seat) 중 하나라도 같은 운행에서 다른 예약의 seat_inventory SOLD 행이 있음
 * </pre>
 *
 * <ul>
 *   <li><b>출발 뒤에만</b>: 출발 전에는 재판매가 계속 바뀌므로 'N'으로 둔다 (§8.3). 출발하면 더 팔리지 않아 값이 고정된다.</li>
 *   <li><b>N → Y만</b>: 출발 뒤 SOLD 행은 사라지지 않으므로 Y가 N으로 돌아갈 일이 없다. 조건부 UPDATE 한 문장이라 몇 번 돌려도 같다.</li>
 *   <li><b>다시 팔렸다가 또 취소된 좌석</b>은 SOLD 행이 지워졌으므로 재판매로 세지 않는다 (출발 때 실제로 누가 앉았는가).</li>
 *   <li><b>구간 재고가 꺼져 있어</b>(HoldPlan.SEGMENT_NO = 0) 좌석만 비교한다. 구간 재고를 켜면 구간 겹침 조건을 더해야 한다.</li>
 *   <li>lookback(기본 7일): 배치가 며칠 멈췄다 다시 돌아도 따라잡는다. 그보다 오래된 운행은 다시 보지 않는다 (매시간 전체를 훑지 않게).</li>
 * </ul>
 */
@Component
public class ResaleBatch {
	private static final Logger log = LoggerFactory.getLogger(ResaleBatch.class);
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final boolean enabled;
	private final int lookbackDays;

	public ResaleBatch(JdbcTemplate jdbc, Clock clock, @Value("${jejari.resale.batch-enabled:true}") boolean enabled,
			@Value("${jejari.resale.lookback-days:7}") int lookbackDays) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.enabled = enabled;
		this.lookbackDays = lookbackDays;
	}

	@Scheduled(fixedDelayString = "${jejari.resale.interval:PT1H}", initialDelayString = "PT2M")
	void tick() {
		if (enabled) {
			run();
		}
	}

	/** @return 이번에 'Y'로 바꾼 취소 수 */
	public int run() {
		LocalDate since = LocalDate.now(clock.withZone(SEOUL)).minusDays(lookbackDays);
		int changed = jdbc.update("""
				UPDATE cancellation c SET resold_yn = 'Y', updated_at = SYSTIMESTAMP
				 WHERE c.resold_yn = 'N'
				   AND EXISTS (
				       SELECT 1 FROM reservation r
				         JOIN train_run t ON t.run_id = r.run_id
				         JOIN reservation_seat rs ON rs.reservation_id = r.reservation_id
				         JOIN seat_inventory i ON i.run_id = r.run_id AND i.seat_id = rs.seat_id
				        WHERE r.reservation_id = c.reservation_id
				          AND t.status = 'DEPARTED' AND t.run_date >= ?
				          AND i.status = 'SOLD' AND i.reservation_id <> r.reservation_id)""", Date.valueOf(since));
		if (changed > 0) {
			log.info("재판매 집계: 출발한 운행의 취소 {}건을 재판매(Y)로 기록", changed);
		}
		return changed;
	}
}
