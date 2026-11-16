package com.jejari.seat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.error.BusinessException;
import com.jejari.seat.SeatReleaseTx.Released;
import com.jejari.train.RunInfo;
import com.jejari.train.TrainRunReader;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 좌석 해제 (concurrency.md §7). 결제 기한 초과 자동 반환(RSV-03), 결제 전 취소(RSV-05), 결제 실패(PAY-03, Saga)가
 * 모두 이 한 곳을 지납니다.
 *
 * <pre>
 * DB 트랜잭션 (§7.1, SeatReleaseTx): 예약 상태 전이 → HOLD 행 삭제 → 카운터 감소 → Saga·이력·outbox
 * 커밋 후 (§7.2, §14.3 빠른 경로): 좌석 키 → REOFFER:R (대기자 먼저), 한도 감소(limit_decr.lua, 예약당 한 번)
 * </pre>
 *
 * 커밋 후 보상이 앱 종료로 유실돼도, 같은 트랜잭션에 남긴 RESERVATION_EXPIRED·CANCELED 이벤트를
 * seat-release 소비자가 받아 같은 두 스크립트를 다시 실행합니다(보장 경로). 두 스크립트는 두 번 돌려도 결과가 같습니다.
 */
@Service
public class SeatReleaseService {

	private static final Logger log = LoggerFactory.getLogger(SeatReleaseService.class);

	/** 결제 전 취소 결과 */
	public record CancelResult(long reservationId, String status) {
	}

	private final SeatReleaseTx releaseTx;
	private final TrainRunReader runs;
	private final SeatScripts scripts;
	private final SeatPolicy policy;
	private final StringRedisTemplate redis;
	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final MeterRegistry meters;

	public SeatReleaseService(SeatReleaseTx releaseTx, TrainRunReader runs, SeatScripts scripts, SeatPolicy policy,
			StringRedisTemplate redis, JdbcTemplate jdbc, TransactionTemplate tx, MeterRegistry meters) {
		this.releaseTx = releaseTx;
		this.runs = runs;
		this.scripts = scripts;
		this.policy = policy;
		this.redis = redis;
		this.jdbc = jdbc;
		this.tx = tx;
		this.meters = meters;
	}

	/**
	 * RSV-03: 결제 기한이 지난 HOLD 예약 하나를 만료시킨다 (§6.1 스캐너가 건마다 부름).
	 * 앱이 여러 대라 같은 예약을 동시에 잡아도 조건부 UPDATE가 한쪽만 1행이 되므로 안전하다.
	 *
	 * @return 이번에 만료시켰으면 true, 이미 다른 경로가 처리했으면 false
	 */
	public boolean expire(long reservationId) {
		Released done = tx.execute(st -> releaseTx.release(reservationId, ReleaseReason.TIMEOUT, null));
		return afterCommit(done);
	}

	/**
	 * RSV-05 결제 전 취소 (§7.4). HOLD만 취소된다. 수수료가 없고 cancellation 행도 만들지 않는다.
	 * 같은 요청을 다시 보내면(이미 CANCELED) 그대로 성공을 돌려준다.
	 */
	public CancelResult cancelBeforePay(long memberId, long reservationId) {
		Released done = tx.execute(st -> releaseTx.release(reservationId, ReleaseReason.USER_BEFORE_PAY, memberId));
		if (done != null) {
			afterCommit(done);
			return new CancelResult(reservationId, "CANCELED");
		}
		// 0행: 왜 안 됐는지 알려 준다 (남의 예약은 없는 예약과 같게 404)
		List<String> status = jdbc.queryForList(
				"SELECT status FROM reservation WHERE reservation_id = ? AND member_id = ?", String.class, reservationId, memberId);
		if (status.isEmpty()) {
			throw new BusinessException(SeatErrorCode.RESERVATION_NOT_FOUND);
		}
		return switch (status.get(0)) {
			case "CANCELED" -> new CancelResult(reservationId, "CANCELED");
			case "PAYMENT_PENDING" -> throw new BusinessException(SeatErrorCode.PAYMENT_IN_PROGRESS);
			case "EXPIRED" -> throw new BusinessException(SeatErrorCode.RESERVATION_EXPIRED);
			default -> throw new BusinessException(SeatErrorCode.RESERVATION_NOT_HOLD); // CONFIRMED는 확정 취소(CNL-02)
		};
	}

	/**
	 * Saga·결제 쪽(C 길동현)이 부르는 해제. <b>이미 열린 트랜잭션 안에서</b> §7.1을 실행하고,
	 * 그 트랜잭션이 커밋되면 Redis 보상을 한다. 예: §5.2 결제 실패는 payment FAILED 기록과 같은 트랜잭션에서
	 * {@code releaseInCurrentTx(R, ReleaseReason.PAY_FAILED)}. 예약 행 락은 호출하는 쪽이 먼저 잡아도 되고 안 잡아도 된다.
	 *
	 * @return 해제했으면 true, 상태가 이미 바뀌어 아무것도 안 했으면 false (두 번 불러도 결과가 같다)
	 */
	public boolean releaseInCurrentTx(long reservationId, ReleaseReason reason) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("releaseInCurrentTx는 트랜잭션 안에서 불러야 합니다");
		}
		Released done = releaseTx.release(reservationId, reason, null);
		if (done == null) {
			return false;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				SeatReleaseService.this.afterCommit(done);
			}
		});
		return true;
	}

	/** §7.2 커밋 후 보상 (빠른 경로). 실패해도 예외를 던지지 않는다: DB는 이미 커밋됐고 보장 경로가 다시 한다 */
	private boolean afterCommit(Released done) {
		if (done == null) {
			return false;
		}
		meters.counter("jejari.seat.released", "reason", done.reason().name()).increment();
		try {
			List<String> seatKeys = done.seatIds().stream()
					.map(id -> SeatKeys.hold(done.runId(), id, HoldPlan.SEGMENT_NO)).toList();
			if (!seatKeys.isEmpty()) {
				// 내 값(R 또는 SOLD:R)인 키만 REOFFER:R로. HOLD 만료라 TTL로 이미 사라졌으면 아무 일 없음
				redis.execute(scripts.reoffer, seatKeys, Long.toString(done.reservationId()),
						Long.toString(policy.getLong(SeatPolicy.REOFFER_HOLD_SEC) * 1000));
			}
			RunInfo run = runs.find(done.runId()).orElse(null);
			List<String> keys = new ArrayList<>();
			keys.add(SeatKeys.limitDec(done.reservationId()));
			keys.add(SeatKeys.limitRun(done.runId(), done.ciHash()));
			keys.add(SeatKeys.limitDay(done.purchaseDay(), done.ciHash()));
			if (done.saleEventId() != null) {
				keys.add(SeatKeys.limitEvent(done.saleEventId(), done.ciHash()));
			}
			redis.execute(scripts.limitDecr, keys, Integer.toString(done.seatCount()), Long.toString(markExpiry(run)));
		} catch (RuntimeException e) {
			log.warn("예약 {} 해제 후 Redis 보상 실패 (seat-release가 다시 함)", done.reservationId(), e);
		}
		return true;
	}

	/** limit:dec 표시 키 만료: 운행 출발·행사 종료 중 늦은 쪽 (§14.1). 운행을 못 읽으면 넉넉히 하루 뒤 */
	private long markExpiry(RunInfo run) {
		java.time.ZoneId zone = java.time.ZoneId.of("Asia/Seoul");
		LocalDateTime t = LocalDateTime.now(zone).plusDays(1);
		if (run != null) {
			LocalDateTime dep = run.departureAt() != null ? run.departureAt() : run.runDate().plusDays(1).atStartOfDay();
			t = dep.isAfter(t) ? dep : t;
			if (run.eventEndDate() != null && run.eventEndDate().plusDays(1).atStartOfDay().isAfter(t)) {
				t = run.eventEndDate().plusDays(1).atStartOfDay();
			}
		}
		return t.atZone(zone).toInstant().toEpochMilli();
	}
}
