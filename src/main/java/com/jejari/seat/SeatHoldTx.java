package com.jejari.seat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jejari.common.error.BusinessException;
import com.jejari.seat.HoldPlan.SeatLine;
import com.jejari.seat.PurchaseCounters.Change;

/**
 * 좌석 선점의 DB 트랜잭션 (concurrency.md §2.2, 재선점 §3, 카운터 §10). Redis 좌석 경쟁에서 <b>이긴 요청만</b> 여기 옵니다.
 * Redis가 1차, 여기가 최종 판정입니다 (§0.1). 호출하는 쪽이 트랜잭션을 엽니다.
 *
 * <p>네이티브 UPDATE 규칙 (§0.3): updated_at = SYSTIMESTAMP, reservation은 version + 1,
 * 현재 상태를 WHERE에 넣고 영향 행 수를 확인합니다.
 */
@Component
class SeatHoldTx {

	/**
	 * 재선점으로 만료시킨 이전 예약. 커밋 뒤 그 예약의 Redis 한도를 되돌리는 데 씀 (§3-7).
	 *
	 * @param group 단체 예약(source = GROUP)이면 true. 카운터를 올린 적이 없어 되돌리지 않는다 (§18.5)
	 */
	record Preempted(long reservationId, String ciHash, int seatCount, long purchaseDay, Long saleEventId, boolean group) {
	}

	private final JdbcTemplate jdbc;
	private final PurchaseCounters counters;
	private final ReservationOutbox outbox;

	SeatHoldTx(JdbcTemplate jdbc, PurchaseCounters counters, ReservationOutbox outbox) {
		this.jdbc = jdbc;
		this.counters = counters;
		this.outbox = outbox;
	}

	List<Preempted> write(HoldPlan p) {
		List<Change> counterChanges = new ArrayList<>();

		// 1. 이전 점유자 확인 (락 없음). 행이 없으면 바로 3으로
		TreeSet<Long> expiredOwners = new TreeSet<>();
		String in = String.join(",", p.seats().stream().map(s -> "?").toList());
		List<Object> args = new ArrayList<>();
		args.add(p.run().runId());
		args.add(HoldPlan.SEGMENT_NO);
		p.seats().forEach(s -> args.add(s.seatId()));
		jdbc.query("""
				SELECT reservation_id, status, CASE WHEN hold_expires_at >= SYSTIMESTAMP THEN 1 ELSE 0 END AS live
				  FROM seat_inventory WHERE run_id = ? AND segment_no = ? AND seat_id IN (%s)""".formatted(in), rs -> {
			// BLOCKED(단체 할당석, §18.2): 예약이 없는 행이라 만료된 HOLD로 보면 reservation_id 0을 재선점하려다 PK 충돌이 난다
			if ("SOLD".equals(rs.getString("status")) || "BLOCKED".equals(rs.getString("status")) || rs.getInt("live") == 1) {
				// Redis 키가 사라졌는데 DB 점유가 유효한 경우. 정상이면 일어나지 않지만 DB가 최종
				throw new BusinessException(SeatErrorCode.SEAT_ALREADY_TAKEN);
			}
			expiredOwners.add(rs.getLong("reservation_id"));
		}, args.toArray());

		// 2. 재선점 (§3): 만료된 HOLD의 이전 예약을 ID 오름차순으로 한 건씩 잠그고 정리
		List<Preempted> preempted = new ArrayList<>();
		for (long prev : expiredOwners) {
			Preempted done = expirePrevious(prev, counterChanges);
			if (done != null) {
				preempted.add(done);
			}
		}

		// 3. 예약
		long r = p.reservationId();
		jdbc.update("""
				INSERT INTO reservation (reservation_id, member_id, ci_hash, run_id, from_station_id, to_station_id,
				       seat_count, total_amount, status, channel, source, held_at, pay_deadline_at, idempotency_key, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'HOLD', ?, ?, ?, ?, ?, 0)""",
				r, p.memberId(), p.ciHash(), p.run().runId(), p.fromStationId(), p.toStationId(),
				p.seatCount(), p.totalAmount(), p.channel(), p.source(), p.now(), p.deadline(), p.idempotencyKey());

		// 4. 좌석 재고: (run_id, seat_id, segment_no) 오름차순 INSERT (§0.4-3). PK 충돌이면 롤백 → 거절
		for (SeatLine s : p.seats()) {
			jdbc.update("""
					INSERT INTO seat_inventory (run_id, train_id, seat_id, segment_no, reservation_id, status, hold_expires_at)
					VALUES (?, ?, ?, ?, ?, 'HOLD', ?)""",
					p.run().runId(), p.run().trainId(), s.seatId(), HoldPlan.SEGMENT_NO, r, p.deadline());
		}

		// 5. 예약 좌석. 승차권 번호는 지금 발급하지만 확정 전에는 효력이 없음
		int no = 1;
		for (SeatLine s : p.seats()) {
			jdbc.update("""
					INSERT INTO reservation_seat (reservation_id, seat_id, ticket_no, passenger_type, amount)
					VALUES (?, ?, ?, ?, ?)""", r, s.seatId(), "T" + r + "-" + no++, s.passengerType(), s.amount());
		}

		// 6. 카운터: 새 예약의 증가 + 재선점한 이전 예약의 감소를 정렬해서 한 번에 (§10.3)
		addIncrements(p, counterChanges);
		counters.apply(counterChanges);

		// 7~9. 이벤트, 이력, Saga. outbox는 reservation INSERT 뒤 (§0.5)
		String eventId = outbox.append(r, "SEAT_HELD", seatHeldPayload(p));
		history(r, null, "HOLD", eventId, p.actorType());
		jdbc.update("""
				INSERT INTO saga_instance (saga_id, reservation_id, current_step, status, timeout_at)
				VALUES (?, ?, 'SEAT_HELD', 'RUNNING', ?)""", UUID.randomUUID().toString(), r, p.deadline());
		return preempted;
	}

	/** §3-1~5. 이 예약을 만료시켰으면 그 정보를, 이미 다른 트랜잭션이 만료시켰으면 null */
	private Preempted expirePrevious(long prev, List<Change> counterChanges) {
		record Prev(String status, int seatCount, String ciHash, long runId, LocalDateTime createdAt,
				String from, String to, Long saleEventId, boolean group) {
		}
		Prev old = jdbc.queryForObject("""
				SELECT r.status, r.seat_count, r.ci_hash, r.run_id, r.created_at, r.from_station_id, r.to_station_id, r.source,
				       (SELECT tr.sale_event_id FROM train_run tr WHERE tr.run_id = r.run_id) AS sale_event_id
				  FROM reservation r WHERE r.reservation_id = ? FOR UPDATE OF r.status""", (rs, i) -> {
			long se = rs.getLong("sale_event_id");
			return new Prev(rs.getString("status"), rs.getInt("seat_count"), rs.getString("ci_hash"), rs.getLong("run_id"),
					rs.getObject("created_at", LocalDateTime.class), rs.getString("from_station_id"),
					rs.getString("to_station_id"), rs.wasNull() ? null : se, "GROUP".equals(rs.getString("source")));
		}, prev);

		switch (old.status()) {
			case "EXPIRED" -> {
				return null; // 다른 트랜잭션이 이미 처리함 (예: 이전 예약의 5A·5B를 두 사람이 동시에 재선점). 감소 없음
			}
			case "HOLD" -> {
				// 아래로
			}
			default -> throw new BusinessException(SeatErrorCode.SEAT_ALREADY_TAKEN); // 결제 중·확정은 재선점 불가 (T5)
		}

		int updated = jdbc.update("""
				UPDATE reservation SET status = 'EXPIRED', updated_at = SYSTIMESTAMP, version = version + 1
				 WHERE reservation_id = ? AND status = 'HOLD' AND pay_deadline_at < SYSTIMESTAMP""", prev);
		if (updated != 1) {
			throw new BusinessException(SeatErrorCode.SEAT_ALREADY_TAKEN); // 마감이 아직 안 지남
		}

		// 지금 가져올 좌석만이 아니라 그 예약의 HOLD 행 전부 (고아 행 방지)
		List<Long> seatIds = jdbc.queryForList(
				"SELECT seat_id FROM seat_inventory WHERE reservation_id = ? AND status = 'HOLD' ORDER BY seat_id", Long.class, prev);
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id = ? AND status = 'HOLD'", prev);

		long day = yyyymmdd(old.createdAt()); // 감소는 그 예약을 만든 날짜 (자정 넘김 버그 방지, T37)
		if (!old.group()) { // 단체 예약은 카운터를 올린 적이 없다 (§3-4, §18.5). 줄이면 대표자 개인 한도가 잘못 줄어듦
			counterChanges.add(Change.decr(LimitScope.PER_RUN, old.runId(), old.ciHash(), old.seatCount()));
			counterChanges.add(Change.decr(LimitScope.PER_DAY, day, old.ciHash(), old.seatCount()));
			if (old.saleEventId() != null) {
				counterChanges.add(Change.decr(LimitScope.PER_HOLIDAY, old.saleEventId(), old.ciHash(), old.seatCount()));
			}
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("reservationId", prev);
		payload.put("runId", old.runId());
		payload.put("seats", seatIds.stream().map(id -> Map.of("seatId", id, "segNo", HoldPlan.SEGMENT_NO)).toList());
		payload.put("from", old.from());
		payload.put("to", old.to());
		payload.put("seatCount", old.seatCount());
		payload.put("reason", "PREEMPTED");
		String eventId = outbox.append(prev, "RESERVATION_EXPIRED", payload);
		history(prev, "HOLD", "EXPIRED", eventId, "SYSTEM");
		jdbc.update("""
				UPDATE saga_instance SET status = 'COMPENSATED', updated_at = SYSTIMESTAMP
				 WHERE reservation_id = ? AND status = 'RUNNING'""", prev);
		return new Preempted(prev, old.ciHash(), old.seatCount(), day, old.saleEventId(), old.group());
	}

	private static void addIncrements(HoldPlan p, List<Change> changes) {
		int n = p.seatCount();
		var limits = p.limits();
		changes.add(Change.incr(LimitScope.PER_RUN, p.run().runId(), p.ciHash(), n, limits.max(LimitScope.PER_RUN)));
		changes.add(Change.incr(LimitScope.PER_DAY, p.purchaseDay(), p.ciHash(), n, limits.max(LimitScope.PER_DAY)));
		if (p.run().saleEventId() != null) {
			changes.add(Change.incr(LimitScope.PER_HOLIDAY, p.run().saleEventId(), p.ciHash(), n,
					limits.max(LimitScope.PER_HOLIDAY)));
		}
	}

	private void history(long reservationId, String from, String to, String eventId, String actor) {
		jdbc.update("""
				INSERT INTO reservation_history (history_id, reservation_id, from_status, to_status, event_id, actor_type, changed_at)
				VALUES (reservation_history_seq.NEXTVAL, ?, ?, ?, ?, ?, SYSTIMESTAMP)""",
				reservationId, from, to, eventId, actor);
	}

	private static Map<String, Object> seatHeldPayload(HoldPlan p) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("reservationId", p.reservationId());
		m.put("memberId", p.memberId());
		m.put("ciHash", p.ciHash());
		m.put("runId", p.run().runId());
		m.put("seats", p.seats().stream().map(s -> Map.of("seatId", s.seatId(), "segNo", HoldPlan.SEGMENT_NO)).toList());
		m.put("from", p.fromStationId());
		m.put("to", p.toStationId());
		m.put("seatCount", p.seatCount());
		m.put("amount", p.totalAmount());
		m.put("payDeadlineAt", p.deadline().atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime().toString());
		m.put("source", p.source());
		return m;
	}

	static long yyyymmdd(LocalDateTime t) {
		return t.getYear() * 10000L + t.getMonthValue() * 100L + t.getDayOfMonth();
	}
}
