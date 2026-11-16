package com.jejari.seat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.jejari.train.RunInfo;
import com.jejari.train.TrainRunReader;

/**
 * 결제(PAY-01·02, C 길동현)가 부르는 좌석 단계. 결제 쪽 트랜잭션 <b>안에서</b> 좌석 재고를 바꾸고,
 * 그 트랜잭션이 커밋되면 Redis 좌석 키를 맞춥니다.
 *
 * <p>나눠 맡는 기준: reservation·payment·saga·이력·outbox는 결제 쪽이, 좌석 재고(seat_inventory)와 Redis 좌석 키는
 * 이 클래스가 바꿉니다. 결제 쪽이 reservation 행 락을 먼저 잡은 뒤에 부릅니다 (§0.4-1).
 *
 * <pre>
 * §4 결제 시작   reservation HOLD → PAYMENT_PENDING (결제 쪽, 새 마감 :d) → extendHoldInCurrentTx(R, :d) → … → COMMIT → extend.lua
 * §5.1 승인      reservation FOR UPDATE (결제 쪽) → confirmSeatsInCurrentTx(R) → payment APPROVED → reservation CONFIRMED → … → COMMIT → sold.lua
 * </pre>
 *
 * 두 메서드 모두 <b>false면 좌석을 잃은 것</b>이므로 호출한 쪽이 트랜잭션을 롤백해야 합니다
 * (§4.1-3 결제 시작 거절, §5.1 "확정 실패" 경로). false일 때는 커밋 후 Redis 작업도 걸지 않습니다.
 */
@Service
public class SeatPaymentService {

	private static final Logger log = LoggerFactory.getLogger(SeatPaymentService.class);
	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private final JdbcTemplate jdbc;
	private final StringRedisTemplate redis;
	private final SeatScripts scripts;
	private final TrainRunReader runs;
	private final SeatMapService seatMap;

	public SeatPaymentService(JdbcTemplate jdbc, StringRedisTemplate redis, SeatScripts scripts, TrainRunReader runs,
			SeatMapService seatMap) {
		this.jdbc = jdbc;
		this.redis = redis;
		this.scripts = scripts;
		this.runs = runs;
		this.seatMap = seatMap;
	}

	/**
	 * §4.1-3 결제 시작 때 좌석 마감을 예약의 새 마감 :d로 복사하고(§0.2), 커밋 뒤 Redis 좌석 키도 :d로 연장한다(§4.2-1).
	 * 영향 행 수가 K(§2.4)와 다르면 좌석을 이미 잃은 것이다.
	 *
	 * @param deadline reservation.pay_deadline_at의 새 값 (결제 쪽이 GREATEST로 늘린 뒤 RETURNING으로 읽은 값)
	 * @return K행 모두 이 예약 HOLD였으면 true. false면 호출한 쪽이 롤백하고 결제 시작을 거절한다
	 */
	public boolean extendHoldInCurrentTx(long reservationId, LocalDateTime deadline) {
		SeatReleaseService.requireTx("extendHoldInCurrentTx");
		Reservation rv = reservation(reservationId);
		if (rv == null) {
			return false;
		}
		List<Long> seatIds = heldSeats(reservationId);
		int updated = jdbc.update("""
				UPDATE seat_inventory SET hold_expires_at = ?, updated_at = SYSTIMESTAMP
				 WHERE reservation_id = ? AND status = 'HOLD'""", deadline, reservationId);
		if (updated != rv.k()) {
			log.info("예약 {} 결제 시작: 좌석 행 {}개 중 {}개만 HOLD (좌석을 잃음)", reservationId, rv.k(), updated);
			return false;
		}
		long pxat = deadline.atZone(ZONE).toInstant().toEpochMilli();
		afterCommit(() -> {
			// 값이 R인 키만 민다. TTL로 이미 사라진 키는 되살리지 않는다 (남에게 넘어갔을 수 있음, extend.lua)
			Long n = redis.execute(scripts.extend, keys(rv.runId(), seatIds), Long.toString(reservationId), Long.toString(pxat));
			if (n == null || n != seatIds.size()) {
				log.warn("예약 {} 결제 시작: Redis 좌석 키 {}개 중 {}개만 연장 (DB는 연장됨, DB가 최종 판정)", reservationId,
						seatIds.size(), n);
			}
		});
		return true;
	}

	/**
	 * §5.1-4 결제 승인 때 좌석을 HOLD → SOLD로 확정하고, 커밋 뒤 Redis 좌석 키를 SOLD:R(만료 = 운행 출발)로
	 * 덮어쓴다(§5.1-10). payment·reservation·saga·이력·outbox(5~8단계)는 결제 쪽이 같은 트랜잭션에서 한다.
	 *
	 * @return K행 모두 확정했으면 true. false면 좌석을 잃었거나 예약이 이미 풀린 것이므로 호출한 쪽이 롤백하고
	 *         "확정 실패" 경로(별도 트랜잭션에서 payment APPROVED + 늦은 승인 환불 §7.3)로 간다
	 */
	public boolean confirmSeatsInCurrentTx(long reservationId) {
		SeatReleaseService.requireTx("confirmSeatsInCurrentTx");
		Reservation rv = reservation(reservationId);
		if (rv == null) {
			return false;
		}
		List<Long> seatIds = heldSeats(reservationId);
		int updated = jdbc.update("""
				UPDATE seat_inventory SET status = 'SOLD', hold_expires_at = NULL, updated_at = SYSTIMESTAMP
				 WHERE reservation_id = ? AND status = 'HOLD'""", reservationId);
		if (updated != rv.k()) {
			log.info("예약 {} 결제 확정: 좌석 행 {}개 중 {}개만 HOLD (확정 실패 경로)", reservationId, rv.k(), updated);
			return false;
		}
		afterCommit(() -> {
			// DB가 확정했으므로 Redis 값이 무엇이든(사라졌어도) SOLD:R로 덮어쓴다 (sold.lua)
			RunInfo run = runs.find(rv.runId()).orElse(null);
			LocalDateTime dep = run == null ? LocalDateTime.now(ZONE).plusDays(1)
					: run.departureAt() != null ? run.departureAt() : run.runDate().plusDays(1).atStartOfDay();
			redis.execute(scripts.sold, keys(rv.runId(), seatIds), Long.toString(reservationId),
					Long.toString(dep.atZone(ZONE).toInstant().toEpochMilli()));
			seatMap.evict(rv.runId());
		});
		return true;
	}

	/** 결제 쪽이 이미 reservation 행 락을 쥐고 있으므로 락 없이 읽는다. K = seat_count × 구간 수 1 (§2.4) */
	private record Reservation(long runId, int k) {
	}

	private Reservation reservation(long reservationId) {
		try {
			return jdbc.queryForObject("SELECT run_id, seat_count FROM reservation WHERE reservation_id = ?",
					(rs, i) -> new Reservation(rs.getLong(1), rs.getInt(2)), reservationId);
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}

	private List<Long> heldSeats(long reservationId) {
		return jdbc.queryForList("SELECT seat_id FROM seat_inventory WHERE reservation_id = ? AND status = 'HOLD' ORDER BY seat_id",
				Long.class, reservationId);
	}

	private static List<String> keys(long runId, List<Long> seatIds) {
		return seatIds.stream().map(id -> SeatKeys.hold(runId, id, HoldPlan.SEGMENT_NO)).toList();
	}

	/** 커밋된 뒤에만 Redis를 바꾼다. 실패해도 예외를 던지지 않는다 (DB가 이미 최종 판정을 했음, §0.1) */
	private static void afterCommit(Runnable redisWork) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					redisWork.run();
				} catch (RuntimeException e) {
					log.warn("커밋 후 Redis 좌석 키 갱신 실패 (DB는 커밋됨)", e);
				}
			}
		});
	}
}
