package com.jejari.seat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.config.AppPolicy;

/**
 * Redis 장애 뒤 결제 기한 되돌려 주기 (concurrency.md §6.3, T47).
 *
 * <p>Redis가 죽으면 결제 시작이 503이라 좌석을 잡은 사람도 결제를 못 하는데, 기한(DB 시계)은 계속 흐른다.
 * 그래서 <b>장애 동안 HOLD 만료를 멈추고</b>({@link HoldExpiryScanner}가 {@link #redisDown()}을 봄),
 * <b>복구되면 장애 시간만큼(최대 결제 기한 한 번) 기한을 늘린다</b>. 마감 복사본(§0.2)과 좌석 키도 같은 값으로.
 *
 * <p>한계: 장애 시작은 이 앱의 메모리에만 있다 (장애 중 재시작하면 그 장애는 못 늘림). 앱이 여러 대면 대마다 늘리므로
 * 릴레이(§11.2)처럼 한 대만 하게 바꿔야 한다 — 지금은 1대 기준.
 */
@Component
@ConditionalOnProperty(name = "jejari.seat.outage-guard-enabled", havingValue = "true", matchIfMissing = true)
public class RedisOutageGuard {

	private static final Logger log = LoggerFactory.getLogger(RedisOutageGuard.class);
	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private final StringRedisTemplate redis;
	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final SeatScripts scripts;
	private final AppPolicy policy;
	private final Clock clock;

	/** 장애 시작 (null = 정상) */
	private volatile LocalDateTime downSince;
	/** 이번 장애에서 이미 늘린 예약 (복구 처리가 중간에 실패해 다시 할 때 두 번 늘리지 않게) */
	private final Set<Long> extended = new HashSet<>();

	public RedisOutageGuard(StringRedisTemplate redis, JdbcTemplate jdbc, TransactionTemplate tx, SeatScripts scripts,
			AppPolicy policy, Clock clock) {
		this.redis = redis;
		this.jdbc = jdbc;
		this.tx = tx;
		this.scripts = scripts;
		this.policy = policy;
		this.clock = clock;
	}

	/** Redis가 지금 죽어 있다고 보나 (복구 뒤 기한을 다 늘릴 때까지 true) */
	public boolean redisDown() {
		return downSince != null;
	}

	@Scheduled(fixedDelay = 1000, initialDelay = 5000)
	void tick() {
		check();
	}

	/** 장애가 이 시각에 시작된 것으로 둔다 (시험용. 실제로는 PING 실패가 정한다) */
	synchronized void markDown(LocalDateTime since) {
		downSince = since;
	}

	/** PING 한 번. 실패하면 장애 시작을 기억하고, 다시 되면 기한을 늘린 뒤 정상으로 */
	synchronized void check() {
		LocalDateTime now = LocalDateTime.now(clock);
		if (!ping()) {
			if (downSince == null) {
				downSince = now;
				log.warn("Redis 응답 없음 — HOLD 만료를 멈춥니다 (복구되면 장애 시간만큼 결제 기한을 늘림, §6.3)");
			}
			return;
		}
		if (downSince == null) {
			return;
		}
		try {
			int n = extendAfterOutage(downSince, now);
			log.info("Redis 복구 — {}초 장애, 선점 {}건의 결제 기한을 늘렸습니다 (§6.3)",
					Duration.between(downSince, now).toSeconds(), n);
			downSince = null;
			extended.clear();
		} catch (RuntimeException e) {
			log.warn("Redis 복구 뒤 결제 기한 늘리기 실패 — 다음 확인 때 남은 건만 다시 (만료는 계속 멈춤)", e);
		}
	}

	private boolean ping() {
		try {
			return "PONG".equalsIgnoreCase(redis.execute((RedisCallback<String>) RedisConnection::ping));
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * §6.3-3: 장애가 시작될 때 살아 있던 HOLD의 기한을 장애 시간만큼(최대 reservation.pay_deadline_min) 늘린다.
	 *
	 * @return 늘린 예약 수
	 */
	int extendAfterOutage(LocalDateTime start, LocalDateTime end) {
		long maxSec = policy.getLong(AppPolicy.PAY_DEADLINE_MIN) * 60;
		long dtSec = Math.min(Math.max(0, Duration.between(start, end).toSeconds()), maxSec);
		if (dtSec == 0) {
			return 0;
		}
		List<Long> due = jdbc.queryForList("""
				SELECT reservation_id FROM reservation
				 WHERE status = 'HOLD' AND pay_deadline_at > ?""", Long.class, Timestamp.valueOf(start));
		int n = 0;
		for (long r : due) {
			if (extended.contains(r)) {
				continue;
			}
			if (extendOne(r, dtSec)) {
				n++;
			}
			extended.add(r);
		}
		return n;
	}

	private boolean extendOne(long r, long dtSec) {
		record Done(long runId, LocalDateTime deadline, List<Long> seats) {
		}
		Done done = tx.execute(st -> {
			// §0.4-1: 예약 행 먼저. 그 사이 결제 시작·취소로 HOLD가 아니면 0행 → 건너뜀
			int updated = jdbc.update("""
					UPDATE reservation SET pay_deadline_at = pay_deadline_at + NUMTODSINTERVAL(?, 'SECOND'),
					       updated_at = SYSTIMESTAMP, version = version + 1
					 WHERE reservation_id = ? AND status = 'HOLD'""", dtSec, r);
			if (updated != 1) {
				return null;
			}
			LocalDateTime d = jdbc.queryForObject("SELECT pay_deadline_at FROM reservation WHERE reservation_id = ?",
					LocalDateTime.class, r);
			long runId = jdbc.queryForObject("SELECT run_id FROM reservation WHERE reservation_id = ?", Long.class, r);
			// 마감 복사본 (§0.2)
			jdbc.update("UPDATE seat_inventory SET hold_expires_at = ?, updated_at = SYSTIMESTAMP WHERE reservation_id = ? AND status = 'HOLD'",
					d, r);
			jdbc.update("UPDATE saga_instance SET timeout_at = ?, updated_at = SYSTIMESTAMP WHERE reservation_id = ? AND status = 'RUNNING'",
					d, r);
			jdbc.update("""
					UPDATE waitlist SET offer_deadline_at = ?, updated_at = SYSTIMESTAMP
					 WHERE offered_reservation_id = ? AND status = 'OFFERED'""", d, r);
			List<Long> seats = jdbc.queryForList(
					"SELECT seat_id FROM seat_inventory WHERE reservation_id = ? AND status = 'HOLD' ORDER BY seat_id", Long.class, r);
			return new Done(runId, d, seats);
		});
		if (done == null) {
			return false;
		}
		// 커밋 뒤 좌석 키: 값이 R이거나 없을 때만 R로 (rehold.lua). 선점 키라 마감 + 유예 (§0.2 예외)
		long pxat = done.deadline().atZone(ZONE).toInstant().toEpochMilli()
				+ policy.getLong(AppPolicy.HOLD_RELEASE_GRACE_SEC) * 1000;
		List<String> keys = done.seats().stream().map(s -> SeatKeys.hold(done.runId(), s, HoldPlan.SEGMENT_NO)).toList();
		try {
			redis.execute(scripts.rehold, keys, Long.toString(r), Long.toString(pxat));
		} catch (RuntimeException e) {
			// DB는 이미 늘었고 좌석은 DB가 최종 판정(§2.2-1). 키가 없으면 남의 hold.lua가 이겨도 DB에서 거절된다
			log.warn("예약 {} 기한 연장 뒤 좌석 키 다시 걸기 실패 (DB는 늘어남)", r, e);
		}
		return true;
	}
}
