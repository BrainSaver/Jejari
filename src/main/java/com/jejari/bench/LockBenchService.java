package com.jejari.bench;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.seat.SeatKeys;

/**
 * §17.1 좌석 선점 락 방식 세 가지를 HTTP 한 요청 = 한 선점 시도로 노출한다.
 *
 * <p><b>왜 이게 있나.</b> 기존 측정(`bench/lock_compare.py`)은 Python 스레드로 부하를 줬고,
 * `bench/README.md`가 그 한계를 "GIL 때문에 클라이언트 쪽 병목이 섞인다 — 뼈대가 올라오면
 * k6로 HTTP 레벨에서 다시 재야 한다"고 적어 뒀다. 고쳐야 할 것은 <b>부하 생성기</b>이므로
 * 재는 대상(실험 테이블·락 방식)은 그대로 두고 k6가 때릴 입구만 만든다. 그래야 기존 수치와
 * 사과 대 사과 비교가 된다.
 *
 * <p><b>프로덕션 선점 경로({@code SeatHoldService})는 건드리지 않는다.</b> 거기에 전략 스위치를
 * 넣으면 A·B안에서 Redis {@code seat:hold} 키가 비어 배치도·해제·결제가 전부 어긋난다.
 * 실제 API 전 구간 수치는 별도 시나리오({@code POST /api/v1/reservations})로 따로 잰다.
 *
 * <p>개발 모드에서만 만들어진다. 운영에는 이 클래스가 존재하지 않는다.
 */
@Service
@ConditionalOnProperty(name = "jejari.dev-mode", havingValue = "true")
public class LockBenchService {

	/** 낙관적 락 재시도 한도 (Python 측정과 같은 값) */
	private static final int MAX_RETRY = 20;

	/** 선점 유지 시간. 측정 중에는 만료되지 않을 만큼만 길면 된다 */
	private static final int HOLD_MIN = 10;

	public enum Strategy {
		/** A: 좌석 행을 미리 만들어 두고 SELECT … FOR UPDATE로 줄을 세운다 */
		PESSIMISTIC,
		/** B: 락 없이 읽고 version 조건부 UPDATE. 0행이면 다시 읽는다 */
		OPTIMISTIC,
		/** C 현재 설계: Redis hold.lua 1차 + DB PK 최종 (§2) */
		REDIS
	}

	/** 한 번의 선점 시도 결과 */
	public record Attempt(String result, int dbCalls, int retries, double lockWaitMs) {
		static Attempt ok(int db, int retries, double wait) {
			return new Attempt("ok", db, retries, wait);
		}

		static Attempt lost(int db, int retries, double wait) {
			return new Attempt("lost", db, retries, wait);
		}
	}

	// 프로덕션이 쓰는 것과 같은 파일을 읽는다. SeatScripts의 필드는 package-private이고,
	// 측정용으로 그 접근 범위를 넓히고 싶지 않아 여기서 따로 로드한다
	private static final RedisScript<Long> HOLD = script("hold");
	private static final RedisScript<Long> RELEASE = script("release");

	private static RedisScript<Long> script(String name) {
		DefaultRedisScript<Long> s = new DefaultRedisScript<>();
		s.setLocation(new ClassPathResource("lua/seat/" + name + ".lua"));
		s.setResultType(Long.class);
		return s;
	}

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final StringRedisTemplate redis;

	public LockBenchService(JdbcTemplate jdbc, TransactionTemplate tx, StringRedisTemplate redis) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.redis = redis;
	}

	public Attempt attempt(Strategy strategy, long runId, long seatId, long reservationId) {
		return switch (strategy) {
			case PESSIMISTIC -> pessimistic(runId, seatId, reservationId);
			case OPTIMISTIC -> optimistic(runId, seatId, reservationId);
			case REDIS -> redis(runId, seatId, reservationId);
		};
	}

	// ─────────────────────────────── A 비관적 락 ───────────────────────────────

	/**
	 * {@code FOR UPDATE}로 잡은 락은 커밋까지 쥐고 있어야 의미가 있으므로 한 트랜잭션 안에서 돈다.
	 * 락을 얻기까지 걸린 시간을 따로 재서 "줄 서서 기다린 시간"을 본다 — A안의 특징이 그것이다.
	 */
	private Attempt pessimistic(long runId, long seatId, long reservationId) {
		return tx.execute(st -> {
			long w0 = System.nanoTime();
			List<String> rows = jdbc.query(
					"SELECT status FROM exp_seat WHERE run_id = ? AND seat_id = ? FOR UPDATE",
					(ResultSet rs, int i) -> rs.getString(1), runId, seatId);
			double wait = (System.nanoTime() - w0) / 1_000_000.0;
			int db = 1;
			if (rows.size() == 1 && "AVAILABLE".equals(rows.get(0))) {
				jdbc.update("""
						UPDATE exp_seat SET status = 'HOLD', reservation_id = ?, updated_at = SYSTIMESTAMP
						 WHERE run_id = ? AND seat_id = ?""", reservationId, runId, seatId);
				return Attempt.ok(db + 1, 0, wait);
			}
			return Attempt.lost(db, 0, wait);
		});
	}

	// ─────────────────────────────── B 낙관적 락 ───────────────────────────────

	/**
	 * 트랜잭션을 길게 열지 않는 것이 B안의 요점이라 시도마다 짧은 트랜잭션을 쓴다.
	 * 조건부 UPDATE가 0행이면 그 사이 남이 바꾼 것이므로 다시 읽는다.
	 */
	private Attempt optimistic(long runId, long seatId, long reservationId) {
		int db = 0;
		for (int retry = 0; retry < MAX_RETRY; retry++) {
			List<long[]> rows = jdbc.query(
					"SELECT version FROM exp_seat WHERE run_id = ? AND seat_id = ? AND status = 'AVAILABLE'",
					(ResultSet rs, int i) -> new long[] { rs.getLong(1) }, runId, seatId);
			db++;
			if (rows.isEmpty()) {
				return Attempt.lost(db, retry, 0);
			}
			int changed = jdbc.update("""
					UPDATE exp_seat SET status = 'HOLD', reservation_id = ?, version = version + 1,
					       updated_at = SYSTIMESTAMP
					 WHERE run_id = ? AND seat_id = ? AND version = ? AND status = 'AVAILABLE'""",
					reservationId, runId, seatId, rows.get(0)[0]);
			db++;
			if (changed == 1) {
				return Attempt.ok(db, retry, 0);
			}
		}
		// 한도까지 양보만 한 경우. 정상 거절로 센다 (좌석은 결국 남이 가졌다)
		return Attempt.lost(db, MAX_RETRY, 0);
	}

	// ──────────────────── C 현재 설계 (Redis 1차 + DB PK 최종) ────────────────────

	/**
	 * 진 요청은 <b>DB에 아예 가지 않는다</b>(§2.1-4) — 이 수치가 C안을 고른 이유다.
	 * 이긴 요청만 INSERT하고, PK 충돌이 나면 DB가 최종 거절한 것이라 Redis 키를 되돌린다(§2.3).
	 */
	private Attempt redis(long runId, long seatId, long reservationId) {
		String key = SeatKeys.hold(runId, seatId, 0);
		long deadline = System.currentTimeMillis() + HOLD_MIN * 60_000L;
		Long won = redis.execute(HOLD, List.of(key), Long.toString(reservationId),
				Long.toString(deadline), "");
		if (won == null || won != 1L) {
			return Attempt.lost(0, 0, 0);
		}
		try {
			jdbc.update(con -> {
				PreparedStatement ps = con.prepareStatement("""
						INSERT INTO exp_seat_inv (run_id, seat_id, segment_no, reservation_id, status, hold_expires_at)
						VALUES (?, ?, 0, ?, 'HOLD', SYSTIMESTAMP + INTERVAL '10' MINUTE)""");
				ps.setLong(1, runId);
				ps.setLong(2, seatId);
				ps.setLong(3, reservationId);
				return ps;
			});
			return Attempt.ok(1, 0, 0);
		} catch (DataIntegrityViolationException e) {
			redis.execute(RELEASE, List.of(key), Long.toString(reservationId));
			return Attempt.lost(1, 0, 0);
		}
	}
}
