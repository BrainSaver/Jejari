package com.jejari.seat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jejari.seat.PurchaseCounters.Change;

/**
 * 좌석 해제의 DB 단계 (concurrency.md §7.1). 호출하는 쪽이 연 트랜잭션 안에서 실행합니다.
 *
 * <p>첫 문장이 reservation 행의 조건부 UPDATE라서 그 예약의 행 락을 먼저 잡습니다 (§0.4-1).
 * 그 뒤 좌석·카운터·Saga·취소표를 바꿉니다. 1행이 아니면 다른 트랜잭션(결제 시작·승인·다른 스캐너)이
 * 먼저 상태를 바꾼 것이므로 아무것도 하지 않습니다 (§0.3). 그래서 취소와 만료가 동시에 와도 카운터는 한 번만 줄어듭니다 (T10).
 *
 * <p>{@link #returnSold}는 확정 예약 취소(§8.1-2·3)의 좌석 부분만 합니다. 예약 상태·cancellation·이력·outbox는 CNL 쪽 몫입니다.
 */
@Component
class SeatReleaseTx {

	/** 해제한 예약. 커밋 뒤 Redis 보상(§7.2)에 씀. group = 단체 예약이라 한도를 되돌리지 않음 (§18.5) */
	record Released(long reservationId, long runId, String ciHash, int seatCount, long purchaseDay, Long saleEventId,
			List<Long> seatIds, ReleaseReason reason, boolean group) {
	}

	/** 확정 취소로 돌려준 좌석 (§8.1-2·3). 커밋 뒤 Redis 보상과 이벤트 내용에 씀. group은 {@link Released}와 같음 */
	record ReturnedSold(long reservationId, long runId, String ciHash, int seatCount, long purchaseDay, Long saleEventId,
			List<Long> seatIds, String fromStationId, String toStationId, boolean group) {
	}

	/** 해제에 필요한 예약 값. 호출하는 트랜잭션이 reservation 행 락을 이미 쥔 뒤에 읽는다 */
	private record Row(long runId, String ciHash, int seatCount, LocalDateTime createdAt, String from, String to,
			String source, Long saleEventId) {

		/** 단체 예약(§18): 승인 때 매수 카운터를 올리지 않았으므로 줄이지도 않는다 */
		boolean group() {
			return "GROUP".equals(source);
		}
	}

	private final JdbcTemplate jdbc;
	private final PurchaseCounters counters;
	private final ReservationOutbox outbox;

	SeatReleaseTx(JdbcTemplate jdbc, PurchaseCounters counters, ReservationOutbox outbox) {
		this.jdbc = jdbc;
		this.counters = counters;
		this.outbox = outbox;
	}

	/**
	 * @param memberId 사용자 취소면 본인 예약인지 함께 확인. 시스템 해제면 null
	 * @return 해제했으면 그 정보, 상태가 이미 바뀌어 아무것도 안 했으면 null
	 */
	Released release(long r, ReleaseReason reason, Long memberId) {
		// 1. 상태 전이. HOLD 만료는 마감이 실제로 지났을 때만 (스캐너가 읽은 뒤 바뀌었을 수 있음)
		String deadline = reason == ReleaseReason.TIMEOUT ? " AND pay_deadline_at < SYSTIMESTAMP" : "";
		String owner = memberId != null ? " AND member_id = ?" : "";
		String canceledAt = "CANCELED".equals(reason.to) ? ", canceled_at = SYSTIMESTAMP" : "";
		List<Object> args = new ArrayList<>(List.of(reason.to, r, reason.from));
		if (memberId != null) {
			args.add(memberId);
		}
		int updated = jdbc.update("UPDATE reservation SET status = ?" + canceledAt
				+ ", updated_at = SYSTIMESTAMP, version = version + 1"
				+ " WHERE reservation_id = ? AND status = ?" + deadline + owner, args.toArray());
		if (updated != 1) {
			return null;
		}

		Row row = loadRow(r);

		// 2. 좌석 재고 HOLD 행 삭제 (이벤트에 넣을 좌석을 먼저 읽어 둠)
		List<Long> seatIds = jdbc.queryForList(
				"SELECT seat_id FROM seat_inventory WHERE reservation_id = ? AND status = 'HOLD' ORDER BY seat_id", Long.class, r);
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id = ? AND status = 'HOLD'", r);

		// 3. 카운터 감소 (§10.2). PER_DAY는 그 예약을 만든 날짜 (자정 넘김, T37)
		long day = decrementCounters(row);

		// 4. Saga 보상 완료(current_step은 마지막 단계 그대로), 이벤트, 이력. outbox는 reservation UPDATE 뒤 (§0.5)
		jdbc.update("""
				UPDATE saga_instance SET status = 'COMPENSATED', updated_at = SYSTIMESTAMP
				 WHERE reservation_id = ? AND status = 'RUNNING'""", r);
		Map<String, Object> payload = seatPayload(r, row.runId(), seatIds, row.from(), row.to(), row.seatCount());
		payload.put("reason", reason.name());
		if (reason == ReleaseReason.USER_BEFORE_PAY) {
			payload.put("cancelFee", 0); // 결제 전이라 돈이 오가지 않음. cancellation 행도 만들지 않음 (§7.1)
			payload.put("refundAmount", 0);
		}
		String eventId = outbox.append(r, reason.eventType, payload);
		jdbc.update("""
				INSERT INTO reservation_history (history_id, reservation_id, from_status, to_status, event_id, actor_type, changed_at)
				VALUES (reservation_history_seq.NEXTVAL, ?, ?, ?, ?, ?, SYSTIMESTAMP)""",
				r, reason.from, reason.to, eventId, reason.actor);

		// 5. 취소표 제안으로 만든 예약이면 그 제안도 만료
		if ("WAITLIST".equals(row.source())) {
			jdbc.update("""
					UPDATE waitlist SET status = 'EXPIRED', updated_at = SYSTIMESTAMP
					 WHERE offered_reservation_id = ? AND status = 'OFFERED'""", r);
		}
		return new Released(r, row.runId(), row.ciHash(), row.seatCount(), day, row.saleEventId(), seatIds, reason,
				row.group());
	}

	/**
	 * 확정 예약 취소의 좌석 부분 (§8.1-2·3): SOLD 행 삭제 → 카운터 감소.
	 * 호출하는 트랜잭션이 먼저 reservation을 CONFIRMED → CANCELED로 바꾼 뒤(1행) 불러야 한다.
	 *
	 * @return 돌려준 좌석. 지울 SOLD 행이 없으면(이미 돌려줌) null — 두 번 불러도 카운터는 한 번만 준다
	 */
	ReturnedSold returnSold(long r) {
		String status = jdbc.queryForObject("SELECT status FROM reservation WHERE reservation_id = ?", String.class, r);
		if (!"CANCELED".equals(status)) {
			throw new IllegalStateException("예약 " + r + "을(를) CANCELED로 바꾼 뒤에 좌석을 돌려주세요 (지금 " + status + ")");
		}
		List<Long> seatIds = jdbc.queryForList(
				"SELECT seat_id FROM seat_inventory WHERE reservation_id = ? AND status = 'SOLD' ORDER BY seat_id", Long.class, r);
		int deleted = jdbc.update("DELETE FROM seat_inventory WHERE reservation_id = ? AND status = 'SOLD'", r);
		if (deleted == 0) {
			return null;
		}
		Row row = loadRow(r);
		long day = decrementCounters(row);
		return new ReturnedSold(r, row.runId(), row.ciHash(), row.seatCount(), day, row.saleEventId(), seatIds,
				row.from(), row.to(), row.group());
	}

	/** 빈 좌석 이벤트(RESERVATION_EXPIRED·CANCELED)의 공통 내용. seat-release 소비자(§9)가 이 모양을 읽는다 */
	static Map<String, Object> seatPayload(long r, long runId, List<Long> seatIds, String from, String to, int seatCount) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("reservationId", r);
		payload.put("runId", runId);
		payload.put("seats", seatIds.stream().map(id -> Map.of("seatId", id, "segNo", HoldPlan.SEGMENT_NO)).toList());
		payload.put("from", from);
		payload.put("to", to);
		payload.put("seatCount", seatCount);
		return payload;
	}

	private Row loadRow(long r) {
		return jdbc.queryForObject("""
				SELECT r.run_id, r.ci_hash, r.seat_count, r.created_at, r.from_station_id, r.to_station_id, r.source,
				       (SELECT tr.sale_event_id FROM train_run tr WHERE tr.run_id = r.run_id) AS sale_event_id
				  FROM reservation r WHERE r.reservation_id = ?""", (rs, i) -> {
			long se = rs.getLong("sale_event_id");
			Long saleEventId = rs.wasNull() ? null : se;
			return new Row(rs.getLong("run_id"), rs.getString("ci_hash"), rs.getInt("seat_count"),
					rs.getObject("created_at", LocalDateTime.class), rs.getString("from_station_id"),
					rs.getString("to_station_id"), rs.getString("source"), saleEventId);
		}, r);
	}

	/** §10.2 감소. 예약을 만든 날(PER_DAY scope_ref_id)을 돌려준다. 단체 예약은 감소하지 않는다 (§18.5) */
	private long decrementCounters(Row row) {
		long day = SeatHoldTx.yyyymmdd(row.createdAt());
		if (row.group()) {
			return day;
		}
		List<Change> changes = new ArrayList<>();
		changes.add(Change.decr(LimitScope.PER_RUN, row.runId(), row.ciHash(), row.seatCount()));
		changes.add(Change.decr(LimitScope.PER_DAY, day, row.ciHash(), row.seatCount()));
		if (row.saleEventId() != null) {
			changes.add(Change.decr(LimitScope.PER_HOLIDAY, row.saleEventId(), row.ciHash(), row.seatCount()));
		}
		counters.apply(changes);
		return day;
	}
}
