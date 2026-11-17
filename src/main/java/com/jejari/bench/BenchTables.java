package com.jejari.bench;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * §17.1 락 비교용 실험 테이블 관리 (`bench/schema.sql`과 같은 모양).
 *
 * <p>실제 스키마를 건드리지 않는다. 비교하려는 것은 "락 방식"이라 세 방식에 똑같이 붙는 비용
 * (FK·트리거·§2.2의 나머지 쓰기)은 넣지 않았다 — 기존 Python 측정과 같은 조건이어야
 * 사과 대 사과 비교가 된다.
 *
 * <p>{@code sqlplus}로 `schema.sql`을 따로 실행하지 않아도 되게 여기서 만든다. 웹 러너가
 * 버튼 하나로 돌 수 있어야 하기 때문이다.
 */
@Component
@ConditionalOnProperty(name = "jejari.dev-mode", havingValue = "true")
public class BenchTables {

	private static final String EXP_SEAT = """
			CREATE TABLE exp_seat (
			  run_id         NUMBER       NOT NULL,
			  seat_id        NUMBER       NOT NULL,
			  status         VARCHAR2(10) NOT NULL,
			  reservation_id NUMBER,
			  version        NUMBER       DEFAULT 0 NOT NULL,
			  updated_at     TIMESTAMP    DEFAULT SYSTIMESTAMP NOT NULL,
			  CONSTRAINT pk_exp_seat PRIMARY KEY (run_id, seat_id),
			  CONSTRAINT ck_exp_seat_status CHECK (status IN ('AVAILABLE', 'HOLD'))
			)""";

	private static final String EXP_SEAT_INV = """
			CREATE TABLE exp_seat_inv (
			  run_id          NUMBER       NOT NULL,
			  seat_id         NUMBER       NOT NULL,
			  segment_no      NUMBER(3)    NOT NULL,
			  reservation_id  NUMBER       NOT NULL,
			  status          VARCHAR2(10) NOT NULL,
			  hold_expires_at TIMESTAMP,
			  created_at      TIMESTAMP    DEFAULT SYSTIMESTAMP NOT NULL,
			  CONSTRAINT pk_exp_seat_inv PRIMARY KEY (run_id, seat_id, segment_no)
			)""";

	private final JdbcTemplate jdbc;

	public BenchTables(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 없으면 만든다. 이미 있으면 그대로 둔다 */
	public void ensure() {
		create("EXP_SEAT", EXP_SEAT);
		create("EXP_SEAT_INV", EXP_SEAT_INV);
	}

	private void create(String name, String ddl) {
		Integer exists = jdbc.queryForObject(
				"SELECT COUNT(*) FROM user_tables WHERE table_name = ?", Integer.class, name);
		if (exists != null && exists > 0) {
			return;
		}
		try {
			jdbc.execute(ddl);
		} catch (DataAccessException e) {
			// 둘이 동시에 만들려 한 경우. ORA-00955(이름이 이미 쓰임)면 남이 만든 것이니 넘어간다.
			// ORA 코드는 최상위 메시지가 아니라 원인 체인에 있다
			if (!String.valueOf(e.getMostSpecificCause().getMessage()).contains("ORA-00955")) {
				throw e;
			}
		}
	}

	/**
	 * 한 회차를 시작할 상태로 되돌린다. A·B안은 좌석 행이 {@code AVAILABLE}로 미리 있어야 하고
	 * C안은 행이 없어야 한다 (INSERT의 PK 충돌이 판정이므로).
	 */
	public void reset(long runId, List<Long> seatIds) {
		ensure();
		jdbc.update("DELETE FROM exp_seat WHERE run_id = ?", runId);
		jdbc.update("DELETE FROM exp_seat_inv WHERE run_id = ?", runId);
		jdbc.batchUpdate("INSERT INTO exp_seat (run_id, seat_id, status) VALUES (?, ?, 'AVAILABLE')",
				seatIds.stream().map(s -> new Object[] { runId, s }).toList());
	}

	/** 같은 좌석이 두 번 팔린 건수 (목표 0). A·B는 exp_seat, C는 exp_seat_inv를 본다 */
	public Duplicates duplicates(long runId) {
		return new Duplicates(
				count("""
						SELECT COUNT(*) FROM (
						  SELECT seat_id FROM exp_seat_inv WHERE run_id = ?
						   GROUP BY seat_id HAVING COUNT(*) > 1)""", runId),
				count("""
						SELECT COUNT(*) FROM (
						  SELECT seat_id FROM exp_seat WHERE run_id = ? AND status = 'HOLD'
						   GROUP BY seat_id HAVING COUNT(DISTINCT reservation_id) > 1)""", runId));
	}

	/** 실제로 선점된 좌석 수 (성공 건수가 맞는지 교차 확인) */
	public int heldSeats(long runId) {
		return count("SELECT COUNT(*) FROM exp_seat WHERE run_id = ? AND status = 'HOLD'", runId)
				+ count("SELECT COUNT(*) FROM exp_seat_inv WHERE run_id = ?", runId);
	}

	private int count(String sql, long runId) {
		Integer n = jdbc.queryForObject(sql, Integer.class, runId);
		return n == null ? 0 : n;
	}

	public record Duplicates(int seatInventory, int seat) {
		public int total() {
			return seatInventory + seat;
		}
	}
}
