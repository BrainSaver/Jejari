package com.jejari.train;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jejari.support.NoRunningApp;
import com.jejari.train.RunHorizonScheduler.Extended;

/**
 * 예매 가능 기간이 하루씩 따라오는지 실제 Oracle로 확인한다 ({@link RunHorizonScheduler}).
 *
 * <p>이 테스트는 <b>시드가 깔아 둔 운행</b> 위에서 돈다. 날짜를 지우고 스케줄러가 다시 채우는지 보는
 * 식이라, 끝나면 {@code extendOnce()}가 스스로 복구한다 ({@link #restore()}에서 한 번 더 부른다).
 * 열차 1·2의 운행일은 유일해야 하므로 새 운행을 넣는 대신 있는 것을 지워서 구멍을 만든다.
 */
@Tag("infra")
@ExtendWith(NoRunningApp.class)
@SpringBootTest(properties = {
		"spring.data.redis.database=15",
		"jejari.queue.scheduler-enabled=false",
		"jejari.risk.cache-resync-enabled=false",
		"jejari.risk.batch-enabled=false",
		"jejari.seat.expiry-scanner-enabled=false",
		"jejari.train.sale-open-scheduler-enabled=false",
		"jejari.train.run-horizon-enabled=false"})
class RunHorizonSchedulerInfraTest {

	@Autowired
	JdbcTemplate jdbc;

	/** 스케줄 빈은 끄고 같은 객체를 직접 만든다 (배경 스윕이 끼어들지 않게) */
	private RunHorizonScheduler horizon() {
		return new RunHorizonScheduler(jdbc, List.of(1L, 2L));
	}

	@AfterEach
	void restore() {
		// 지운 날짜를 되돌린다. 메우는 것이 이 스케줄러의 일이라 자기 자신이 복구 도구다
		horizon().extendOnce();
	}

	private LocalDate horizonDate() {
		return jdbc.queryForObject("SELECT ADD_MONTHS(TRUNC(SYSDATE), 2) + 1 FROM dual", LocalDate.class);
	}

	/**
	 * 그 날짜에 <b>스케줄러가 만든</b> 운행 수. 시퀀스 대역만 센다.
	 *
	 * <p>{@code train_run_seq}가 주는 번호는 100000~399999 범위다 (ERD v27의 MAXVALUE가 강제한다).
	 * 시드의 시연 운행(9001·9002·9003)과 명절 운행은 그 밖에 있고 번호가 고정이라 스케줄러가
	 * 다시 만들어 주지 않는다. 그걸 지우면 되돌릴 수 없다 (9003은 다른 테스트와 문서가
	 * "오픈 예정" 예시로 쓴다). 그래서 이 테스트는 자기가 복구할 수 있는 것만 건드린다.
	 */
	private int generatedOn(LocalDate d) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM train_run
				 WHERE run_date = ? AND run_id >= 100000 AND run_id < 400000""",
				Integer.class, java.sql.Date.valueOf(d));
	}

	private void deleteGeneratedOn(LocalDate d) {
		java.sql.Date day = java.sql.Date.valueOf(d);
		jdbc.update("""
				DELETE FROM train_stop WHERE run_id IN
				  (SELECT run_id FROM train_run
				    WHERE run_date = ? AND run_id >= 100000 AND run_id < 400000)""", day);
		jdbc.update("""
				DELETE FROM train_run
				 WHERE run_date = ? AND run_id >= 100000 AND run_id < 400000""", day);
	}

	@Test
	void 빠진_날짜가_없으면_아무것도_넣지_않는다() {
		// 시드가 이미 지평까지 깔아 뒀다. 한 번 더 불러도 늘지 않아야 한다 (멱등)
		horizon().extendOnce();

		Extended again = horizon().extendOnce();

		assertThat(again.runs()).isZero();
		assertThat(again.stops()).isZero();
	}

	@Test
	void 지평의_날짜가_비면_운행과_정차를_채운다() {
		LocalDate edge = horizonDate();
		int before = generatedOn(edge);
		assertThat(before).isPositive();               // 시드가 지평까지 깔아 둔다
		deleteGeneratedOn(edge);
		assertThat(generatedOn(edge)).isZero();

		Extended filled = horizon().extendOnce();

		assertThat(filled.runs()).isEqualTo(before);
		assertThat(filled.stops()).isEqualTo(before * 5);   // 운행마다 역 5곳
		assertThat(generatedOn(edge)).isEqualTo(before);
	}

	@Test
	void 아직_안_열린_날짜는_SCHEDULED로_들어간다() {
		// 지평(두 달 하루 뒤)은 오픈이 내일 07:00이라 아직 열리지 않았다.
		// 이 행이 있어야 화면이 "오전 7시 오픈"을 보여 주고, 07:00의 ON_SALE 전이를 매일 볼 수 있다
		LocalDate edge = horizonDate();
		deleteGeneratedOn(edge);

		horizon().extendOnce();

		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT status, sale_open_at FROM train_run
				 WHERE run_date = ? AND run_id >= 100000 AND run_id < 400000""",
				java.sql.Date.valueOf(edge));
		assertThat(rows).isNotEmpty();
		assertThat(rows).allSatisfy(r -> {
			assertThat(r.get("STATUS")).isEqualTo("SCHEDULED");
			assertThat(((java.sql.Timestamp) r.get("SALE_OPEN_AT")).toLocalDateTime())
					.isAfter(LocalDateTime.now());
		});
	}

	@Test
	void 정차_시각은_그_열차의_기존_운행과_시_분이_같다() {
		LocalDate edge = LocalDate.now().plusDays(20);
		deleteGeneratedOn(edge);

		horizon().extendOnce();

		// 하행(열차 1) 기점 출발 시각을 시드 운행 9001과 비교한다. 날짜만 다르고 시·분은 같아야 한다
		String made = jdbc.queryForObject("""
				SELECT TO_CHAR(MIN(s.departure_time), 'HH24:MI')
				  FROM train_stop s JOIN train_run r ON r.run_id = s.run_id
				 WHERE r.run_date = ? AND r.train_id = 1""", String.class, java.sql.Date.valueOf(edge));
		String seeded = jdbc.queryForObject(
				"SELECT TO_CHAR(MIN(departure_time), 'HH24:MI') FROM train_stop WHERE run_id = 9001",
				String.class);

		assertThat(made).isEqualTo(seeded);
	}

	@Test
	void 운행_번호는_시퀀스가_주고_할당_범위를_넘지_않는다() {
		LocalDate edge = LocalDate.now().plusDays(20);
		deleteGeneratedOn(edge);

		horizon().extendOnce();

		// 번호에 날짜를 인코딩하지 않는다 (ERD v27). 산식을 쓰면 다른 생성기와 대역이 겹칠 수 있다 —
		// 길동현 님 실제 시간표 적재는 400000 + 연중일 * 1000 + 열차번호를 쓴다.
		// train_run_seq의 MAXVALUE 399999가 그 아래로 할당을 강제한다
		Long id = jdbc.queryForObject(
				"SELECT run_id FROM train_run WHERE run_date = ? AND train_id = 1", Long.class,
				java.sql.Date.valueOf(edge));

		assertThat(id).isBetween(100000L, 399999L);
	}

	@Test
	void 같은_날짜를_두_번_넣지_않는다_번호가_아니라_자연키로_본다() {
		// 중복 판정은 run_id가 아니라 (train_id, run_date)다. 그래서 번호가 달라도 같은 날짜는 하나다
		LocalDate edge = LocalDate.now().plusDays(20);
		deleteGeneratedOn(edge);
		horizon().extendOnce();
		Long first = jdbc.queryForObject(
				"SELECT run_id FROM train_run WHERE run_date = ? AND train_id = 1", Long.class,
				java.sql.Date.valueOf(edge));

		// 시퀀스는 다음 번호로 가 있지만, 자연키가 같으니 또 넣지 않는다
		Extended again = horizon().extendOnce();

		assertThat(again.runs()).isZero();
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM train_run WHERE run_date = ? AND train_id = 1", Integer.class,
				java.sql.Date.valueOf(edge))).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT run_id FROM train_run WHERE run_date = ? AND train_id = 1", Long.class,
				java.sql.Date.valueOf(edge))).isEqualTo(first);
	}

	@Test
	void 중간에_구멍이_나도_메운다() {
		// 하루 이어 주는 일과 묵은 DB를 메우는 일을 같은 문장이 한다
		LocalDate gap = LocalDate.now().plusDays(10);
		int before = generatedOn(gap);
		assertThat(before).isEqualTo(2);               // 중간 날짜는 열차 1·2 모두 스케줄러 몫이다
		deleteGeneratedOn(gap);

		Extended filled = horizon().extendOnce();

		assertThat(filled.runs()).isEqualTo(before);
		assertThat(generatedOn(gap)).isEqualTo(before);
	}
}
