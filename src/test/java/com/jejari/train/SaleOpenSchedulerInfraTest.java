package com.jejari.train;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jejari.common.config.AppPolicy;
import com.jejari.train.SaleOpenScheduler.Swept;

/**
 * ADM-02 판매 오픈 스케줄러를 실제 Oracle로 확인한다 (스케줄은 끄고 {@link SaleOpenScheduler#sweepOnce()}를 직접 부른다).
 *
 * <p>시드 운행(9001~9003, 명절 1001~2004)은 건드리지 않는다. 다른 통합 테스트가 9001이 ON_SALE인 것에
 * 기대고 있어서다. 테스트용 운행은 {@code 9101~} 대역에 직접 넣고 끝나면 지운다.
 *
 * <p>열차 1·2는 {@code uq_train_run_train_id_run_date}에 걸리므로 운행일은 시드와 겹치지 않는
 * 먼 미래(+400일 이후)로 둔다.
 */
@Tag("infra")
@SpringBootTest(properties = {
		"spring.data.redis.database=15",
		"jejari.queue.scheduler-enabled=false",
		"jejari.risk.cache-resync-enabled=false",
		"jejari.risk.batch-enabled=false",
		"jejari.seat.expiry-scanner-enabled=false",
		"jejari.train.sale-open-scheduler-enabled=false"})
class SaleOpenSchedulerInfraTest {

	/** 테스트 전용 운행 ID 대역 (시드는 9001~9003만 씀) */
	static final long BASE = 9101;

	private static final String INSERT = """
			INSERT INTO train_run (run_id, train_id, sale_event_id, run_date, sale_open_at, sale_close_at, status)
			VALUES (?, ?, NULL, TRUNC(SYSDATE) + ?, SYSTIMESTAMP + NUMTODSINTERVAL(?, 'SECOND'),
			        SYSTIMESTAMP + NUMTODSINTERVAL(?, 'SECOND'), ?)""";

	/** 마감 없는 운행. 바인드 변수를 IS NULL로 비교하면 Oracle이 타입을 정하지 못해 문을 나눈다 */
	private static final String INSERT_NO_CLOSE = """
			INSERT INTO train_run (run_id, train_id, sale_event_id, run_date, sale_open_at, sale_close_at, status)
			VALUES (?, ?, NULL, TRUNC(SYSDATE) + ?, SYSTIMESTAMP + NUMTODSINTERVAL(?, 'SECOND'), NULL, ?)""";

	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AppPolicy policy;

	SaleOpenScheduler scheduler;

	@BeforeEach
	void setUp() {
		// 스케줄 빈은 끄고(위 속성) 같은 객체를 직접 만들어 sweepOnce()를 부른다. 배경에서 도는 스윕이
		// 테스트 중간에 끼어들지 않게 하려는 것이다 (SeatReleaseInfraTest의 스캐너와 같은 방식)
		scheduler = new SaleOpenScheduler(jdbc, policy);
		// 시드 운행도 전이 대상이다. 적재한 날로부터 하루가 지나면 명절 운행 8건과 9003의 오픈 시각이
		// 지나 있어, 첫 스윕이 그것들까지 연다. 먼저 한 번 가라앉혀야 아래 테스트가 자기 운행의
		// 건수만 센다 (두 번째 스윕부터는 시드 쪽 영향 행이 0이다)
		scheduler.sweepOnce();
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM train_run WHERE run_id >= ?", BASE);
	}

	/**
	 * 테스트 운행 하나를 넣는다.
	 *
	 * @param offset        BASE에서 떨어진 번호 (열차 번호·운행일도 여기서 갈라 유니크 제약을 피한다)
	 * @param openAfterSec  오픈까지 남은 초. 음수면 이미 지난 것
	 * @param closeAfterSec 마감까지 남은 초. null이면 마감 없음
	 */
	private long run(int offset, long openAfterSec, Long closeAfterSec, String status) {
		long runId = BASE + offset;
		long trainId = offset % 2 == 0 ? 1 : 2;
		int dayOffset = 400 + offset;
		if (closeAfterSec == null) {
			jdbc.update(INSERT_NO_CLOSE, runId, trainId, dayOffset, openAfterSec, status);
		} else {
			jdbc.update(INSERT, runId, trainId, dayOffset, openAfterSec, closeAfterSec, status);
		}
		return runId;
	}

	private String status(long runId) {
		return jdbc.queryForObject("SELECT status FROM train_run WHERE run_id = ?", String.class, runId);
	}

	@Test
	void 오픈_시각이_지난_SCHEDULED_운행을_ON_SALE로_바꾼다() {
		long due = run(0, -60, 3600L, "SCHEDULED");

		Swept swept = scheduler.sweepOnce();

		assertThat(status(due)).isEqualTo("ON_SALE");
		assertThat(swept.opened()).isEqualTo(1);
	}

	@Test
	void 오픈_시각이_아직_안_된_운행은_그대로_둔다() {
		long notYet = run(1, 3600, 7200L, "SCHEDULED");

		Swept swept = scheduler.sweepOnce();

		assertThat(status(notYet)).isEqualTo("SCHEDULED");
		assertThat(swept.opened()).isZero();
	}

	@Test
	void 마감_시각이_지난_ON_SALE_운행을_CLOSED로_바꾼다() {
		long over = run(2, -7200, -60L, "ON_SALE");

		Swept swept = scheduler.sweepOnce();

		assertThat(status(over)).isEqualTo("CLOSED");
		assertThat(swept.closed()).isEqualTo(1);
	}

	@Test
	void 오픈과_마감이_모두_지난_운행은_ON_SALE을_거치지_않고_CLOSED가_된다() {
		long stale = run(3, -7200, -60L, "SCHEDULED");

		Swept swept = scheduler.sweepOnce();

		// 마감을 먼저 훑기 때문에 한 번에 CLOSED다. 오픈 쪽은 이 운행을 보지 못한다
		assertThat(status(stale)).isEqualTo("CLOSED");
		assertThat(swept.closed()).isEqualTo(1);
		assertThat(swept.opened()).isZero();
	}

	@Test
	void 마감이_없는_운행은_오픈만_되고_닫히지_않는다() {
		long noClose = run(4, -60, null, "SCHEDULED");

		assertThat(scheduler.sweepOnce().opened()).isEqualTo(1);
		assertThat(status(noClose)).isEqualTo("ON_SALE");

		// 다시 훑어도 ON_SALE 그대로 (마감 시각이 없으면 CLOSE 대상이 아니다)
		Swept again = scheduler.sweepOnce();
		assertThat(status(noClose)).isEqualTo("ON_SALE");
		assertThat(again.closed()).isZero();
	}

	@Test
	void 이미_CLOSED_DEPARTED인_운행은_건드리지_않는다() {
		long closed = run(5, -7200, -60L, "CLOSED");
		long departed = run(6, -7200, -60L, "DEPARTED");

		Swept swept = scheduler.sweepOnce();

		assertThat(status(closed)).isEqualTo("CLOSED");
		assertThat(status(departed)).isEqualTo("DEPARTED");
		assertThat(swept.closed()).isZero();
		assertThat(swept.opened()).isZero();
	}

	@Test
	void 두_번_훑어도_두_번째는_바뀌는_것이_없다() {
		run(7, -60, 3600L, "SCHEDULED");
		run(8, -7200, -60L, "ON_SALE");

		Swept first = scheduler.sweepOnce();
		assertThat(first.opened()).isEqualTo(1);
		assertThat(first.closed()).isEqualTo(1);

		// 앱이 여러 대여도 조건부 UPDATE라 두 번째는 영향 행이 0이다 (concurrency.md §0.3)
		Swept second = scheduler.sweepOnce();
		assertThat(second.opened()).isZero();
		assertThat(second.closed()).isZero();
	}

	@Test
	void 전이한_운행은_updated_at이_올라간다() {
		long due = run(9, -60, 3600L, "SCHEDULED");
		jdbc.update("UPDATE train_run SET updated_at = SYSTIMESTAMP - INTERVAL '1' DAY WHERE run_id = ?", due);

		scheduler.sweepOnce();

		Long fresh = jdbc.queryForObject(
				"SELECT COUNT(*) FROM train_run WHERE run_id = ? AND updated_at > SYSTIMESTAMP - INTERVAL '1' MINUTE",
				Long.class, due);
		assertThat(fresh).isEqualTo(1);
	}

	@Test
	void 오픈된_운행은_대기열이_보는_판매_중_목록에_들어온다() {
		long due = run(10, -60, 3600L, "SCHEDULED");
		TrainRunReader runs = new TrainRunReader(jdbc);
		assertThat(runs.onSaleRunIds()).doesNotContain(due);

		scheduler.sweepOnce();

		List<Long> onSale = runs.onSaleRunIds();
		assertThat(onSale).contains(due);
		// 시드 운행 9001은 그대로 판매 중이어야 한다 (다른 통합 테스트가 여기에 기댄다)
		assertThat(onSale).contains(9001L);
	}
}
