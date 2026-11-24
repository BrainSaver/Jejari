package com.jejari.group;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jejari.event.EventType;
import com.jejari.event.outbox.OutboxRecorder;

/**
 * 단체 승인의 DB 트랜잭션 (concurrency.md §18.3-4, §18.3a-3). Redis({@code group_claim.lua})에서 좌석을 다 잡은 요청만 옵니다.
 * 호출하는 쪽이 트랜잭션을 엽니다.
 *
 * <p>락 순서: group_booking → reservation (§0.4-7). seat_inventory는 할당석 UPDATE 뒤 일반 좌석을 좌석 번호 순으로 INSERT.
 */
@Component
class GroupAllocationTx {

	static final int SEGMENT_NO = 0;
	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	/** 승인이 이미 끝났거나(동시 승인), 신청이 철회·반려됨. 롤백 신호 */
	static final class NotRequested extends RuntimeException {
		NotRequested() {
			super("group_booking.status <> REQUESTED", null, false, false);
		}
	}

	/** 할당석 행 수가 고른 수와 다름 (반납 스캐너가 먼저 지움 등). 롤백 뒤 다시 고른다 */
	static final class SeatChanged extends RuntimeException {
		SeatChanged() {
			super("seat_inventory row count mismatch", null, false, false);
		}
	}

	/** 승객 한 명과 좌석. amount는 할인 반영 금액 */
	record Line(int passengerNo, long seatId, String passengerType, long amount) {
	}

	/**
	 * @param quotaSeats   할당석(BLOCKED → HOLD) 좌석 ID 오름차순
	 * @param generalSeats 일반 좌석(새 INSERT) 좌석 ID 오름차순
	 * @param approvedBy   관리자 승인이면 admin_id, 자동 승인이면 null
	 * @param skipLocked   스캐너(§18.3b)는 다른 앱이 잡고 있는 신청을 기다리지 않고 건너뛴다
	 */
	record Plan(long groupId, long reservationId, long repMemberId, String ciHash, long runId, long trainId,
			String fromStationId, String toStationId, List<Long> quotaSeats, List<Long> generalSeats, List<Line> lines,
			LocalDateTime now, LocalDateTime deadline, Long approvedBy, boolean skipLocked) {

		long totalAmount() {
			return lines.stream().mapToLong(Line::amount).sum();
		}

		int seatCount() {
			return lines.size();
		}
	}

	private final JdbcTemplate jdbc;
	private final OutboxRecorder outbox;

	GroupAllocationTx(JdbcTemplate jdbc, OutboxRecorder outbox) {
		this.jdbc = jdbc;
		this.outbox = outbox;
	}

	void write(Plan p) {
		long r = p.reservationId();

		// 1. 신청 잠금. REQUESTED가 아니면 롤백 (멱등, 동시 승인 2건 중 1건만, G3)
		List<String> status = jdbc.queryForList(
				"SELECT status FROM group_booking WHERE group_id = ? FOR UPDATE" + (p.skipLocked() ? " SKIP LOCKED" : ""),
				String.class, p.groupId());
		if (status.isEmpty() || !"REQUESTED".equals(status.get(0))) {
			throw new NotRequested();
		}

		// 2. 예약. 대표자 명의, 결제 기한은 reservation.pay_deadline_at 하나가 원천 (§18 원칙)
		jdbc.update("""
				INSERT INTO reservation (reservation_id, member_id, ci_hash, run_id, from_station_id, to_station_id,
				       seat_count, total_amount, status, channel, source, held_at, pay_deadline_at, idempotency_key, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'HOLD', 'WEB', 'GROUP', ?, ?, ?, 0)""",
				r, p.repMemberId(), p.ciHash(), p.runId(), p.fromStationId(), p.toStationId(),
				p.seatCount(), p.totalAmount(), p.now(), p.deadline(), "grp:" + p.groupId());

		// 3. 할당석 BLOCKED → HOLD. 행 수가 다르면 그 사이 반납(§18.4)됐거나 다른 단체가 가져감
		if (!p.quotaSeats().isEmpty()) {
			String in = String.join(",", p.quotaSeats().stream().map(s -> "?").toList());
			List<Object> args = new ArrayList<>(List.of(r, p.deadline(), p.runId(), SEGMENT_NO));
			args.addAll(p.quotaSeats());
			int n = jdbc.update("""
					UPDATE seat_inventory SET status = 'HOLD', reservation_id = ?, hold_expires_at = ?, updated_at = SYSTIMESTAMP
					 WHERE run_id = ? AND segment_no = ? AND status = 'BLOCKED' AND seat_id IN (%s)""".formatted(in),
					args.toArray());
			if (n != p.quotaSeats().size()) {
				throw new SeatChanged();
			}
		}
		// 3a. 일반 좌석 INSERT (§18.3a-3). 행이 없는 좌석만 골랐으므로 PK 충돌이면 그 사이 누가 먼저 잡음 → 롤백
		for (long seatId : p.generalSeats()) {
			jdbc.update("""
					INSERT INTO seat_inventory (run_id, train_id, seat_id, segment_no, reservation_id, status, hold_expires_at)
					VALUES (?, ?, ?, ?, ?, 'HOLD', ?)""", p.runId(), p.trainId(), seatId, SEGMENT_NO, r, p.deadline());
		}

		// 4. 예약 좌석 (승객 명단 순서대로)
		int no = 1;
		for (Line l : p.lines()) {
			jdbc.update("""
					INSERT INTO reservation_seat (reservation_id, seat_id, ticket_no, passenger_type, amount)
					VALUES (?, ?, ?, ?, ?)""", r, l.seatId(), "T" + r + "-" + no++, l.passengerType(), l.amount());
		}

		// 5. 탑승자 ↔ 좌석
		jdbc.batchUpdate("UPDATE group_passenger SET seat_id = ?, updated_at = SYSTIMESTAMP WHERE group_id = ? AND passenger_no = ?",
				p.lines(), 200, (ps, l) -> {
					ps.setLong(1, l.seatId());
					ps.setLong(2, p.groupId());
					ps.setInt(3, l.passengerNo());
				});

		// 6. 신청 승인
		int ok = jdbc.update("""
				UPDATE group_booking SET status = 'APPROVED', reservation_id = ?, approved_at = ?, approved_by = ?,
				       updated_at = SYSTIMESTAMP
				 WHERE group_id = ? AND status = 'REQUESTED'""", r, p.now(), p.approvedBy(), p.groupId());
		if (ok != 1) {
			throw new NotRequested();
		}

		// 7. 이벤트(reservation INSERT 뒤, §0.5)·이력·Saga
		String eventId = outbox.recordReservation(r, EventType.SEAT_HELD, payload(p));
		jdbc.update("""
				INSERT INTO reservation_history (history_id, reservation_id, from_status, to_status, event_id, actor_type, changed_at)
				VALUES (reservation_history_seq.NEXTVAL, ?, NULL, 'HOLD', ?, ?, SYSTIMESTAMP)""",
				r, eventId, p.approvedBy() == null ? "SYSTEM" : "ADMIN");
		jdbc.update("""
				INSERT INTO saga_instance (saga_id, reservation_id, current_step, status, timeout_at)
				VALUES (?, ?, 'SEAT_HELD', 'RUNNING', ?)""", UUID.randomUUID().toString(), r, p.deadline());
	}

	/** SeatHoldTx의 SEAT_HELD와 같은 모양 + groupId */
	private static Map<String, Object> payload(Plan p) {
		List<Long> seats = new ArrayList<>(p.quotaSeats());
		seats.addAll(p.generalSeats());
		seats.sort(null);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("reservationId", p.reservationId());
		m.put("memberId", p.repMemberId());
		m.put("ciHash", p.ciHash());
		m.put("runId", p.runId());
		m.put("seats", seats.stream().map(id -> Map.of("seatId", id, "segNo", SEGMENT_NO)).toList());
		m.put("from", p.fromStationId());
		m.put("to", p.toStationId());
		m.put("seatCount", p.seatCount());
		m.put("amount", p.totalAmount());
		m.put("payDeadlineAt", p.deadline().atZone(ZONE).toOffsetDateTime().toString());
		m.put("source", "GROUP");
		m.put("groupId", p.groupId());
		return m;
	}
}
