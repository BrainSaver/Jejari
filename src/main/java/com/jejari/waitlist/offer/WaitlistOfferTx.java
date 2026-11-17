package com.jejari.waitlist.offer;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.jejari.seat.SeatHoldService;
import com.jejari.seat.SeatHoldService.LimitBlocked;
import com.jejari.seat.SeatHoldService.OfferResult;

/**
 * §9.1 취소표 제안 트랜잭션 T. 빈 좌석 이벤트 하나를 <b>트랜잭션 하나</b>로 처리한다 —
 * {@code processed_event} 도장, SKIPPED 갱신, 제안 예약 쓰기, waitlist OFFERED, 알림이 모두 T 안이다.
 * T가 롤백되면 이벤트가 다시 전달되고 처음부터 다시 판단한다.
 *
 * <p>대기자는 {@code FOR UPDATE SKIP LOCKED}로 <b>한 건씩</b> 잠근다. 앱이 여러 대여도 한 대기자에게
 * 한 번만 제안된다 — 다른 인스턴스가 잡은 행은 건너뛴다 (§9.1-4).
 */
@Component
public class WaitlistOfferTx {

	/** 대기자 조회 (인덱스 ix_waitlist_next). 순서는 신청 시각이 아니라 priority_at이다 (§9.3) */
	private static final String NEXT_WAITERS = """
			SELECT waitlist_id, member_id, from_station_id, to_station_id, seat_grade, seat_count
			  FROM waitlist
			 WHERE run_id = ? AND status = 'WAITING'
			 ORDER BY priority_at, waitlist_id
			 FOR UPDATE SKIP LOCKED""";

	private final JdbcTemplate jdbc;
	private final SeatHoldService seatHold;

	public WaitlistOfferTx(JdbcTemplate jdbc, SeatHoldService seatHold) {
		this.jdbc = jdbc;
		this.seatHold = seatHold;
	}

	record Waiter(long waitlistId, long memberId, String from, String to, String grade, int seatCount) {
	}

	/** 제안 한 건 (커밋 뒤 Redis 보상에 쓸 값도 담는다) */
	public record Offered(long waitlistId, long memberId, long reservationId, List<Long> seatIds) {
	}

	/**
	 * 빈 좌석을 대기 순서대로 제안한다.
	 *
	 * @param seatIds 이벤트가 알려 준 빈 좌석. 제안할 때마다 줄어든다
	 * @return 이번에 제안한 건들 (없으면 빈 목록)
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<Offered> offer(long runId, String from, String to, List<Long> seatIds, long prevReservationId) {
		List<Long> free = new ArrayList<>(seatIds);
		List<Offered> offered = new ArrayList<>();

		for (Waiter w : waiters(runId)) {
			if (free.isEmpty()) {
				break;
			}
			// 구간·등급·매수가 맞지 않으면 그냥 다음 대기자로 (상태는 그대로 WAITING, §9.1-5)
			if (!w.from().equals(from) || !w.to().equals(to) || w.seatCount() > free.size()
					|| !gradeMatches(w.grade(), free, w.seatCount())) {
				continue;
			}
			List<Long> take = pick(w.grade(), free, w.seatCount());

			OfferResult r = seatHold.offerInCurrentTx(w.waitlistId(), w.memberId(), runId, from, to, take,
					prevReservationId);
			if (r.blocked() == LimitBlocked.LIMIT) {
				// 매수 한도에 걸린 대기자는 건너뛴다. 좌석은 그대로 두고 다음 사람에게 (§9.1-5)
				jdbc.update("""
						UPDATE waitlist SET status = 'SKIPPED', updated_at = SYSTIMESTAMP
						 WHERE waitlist_id = ? AND status = 'WAITING'""", w.waitlistId());
				notify(w.memberId(), "WAITLIST_SKIPPED", "WAITLIST", w.waitlistId());
				continue;
			}
			if (r.blocked() == LimitBlocked.SEATS_TAKEN) {
				// REOFFER가 만료돼 일반 사용자가 먼저 가져갔다. 더 제안할 좌석이 없다
				break;
			}

			int changed = jdbc.update("""
					UPDATE waitlist SET status = 'OFFERED', offered_at = SYSTIMESTAMP, offer_deadline_at = ?,
					       offered_reservation_id = ?, updated_at = SYSTIMESTAMP
					 WHERE waitlist_id = ? AND status = 'WAITING'""",
					r.payDeadlineAt(), r.reservationId(), w.waitlistId());
			if (changed != 1) {
				// FOR UPDATE로 잡고 있으므로 여기 오면 안 된다. 왔다면 상태가 꼬인 것이라 롤백시킨다
				throw new IllegalStateException("대기 " + w.waitlistId() + " 를 OFFERED로 바꾸지 못했습니다");
			}
			// 제안받았다는 알림. 가리키는 것은 결제해야 할 예약이다
			notify(w.memberId(), "WAITLIST_OFFER", "RESERVATION", r.reservationId());

			free.removeAll(take);
			offered.add(new Offered(w.waitlistId(), w.memberId(), r.reservationId(), take));
		}
		return offered;
	}

	private List<Waiter> waiters(long runId) {
		return jdbc.query(NEXT_WAITERS, (ResultSet rs, int i) -> new Waiter(rs.getLong("waitlist_id"),
				rs.getLong("member_id"), rs.getString("from_station_id"), rs.getString("to_station_id"),
				rs.getString("seat_grade"), rs.getInt("seat_count")), runId);
	}

	/** 그 등급 좌석이 매수만큼 있나 */
	private boolean gradeMatches(String grade, List<Long> free, int seatCount) {
		return pick(grade, free, seatCount).size() == seatCount;
	}

	/** 빈 좌석 중 그 등급인 것을 매수만큼 고른다 (좌석 ID 오름차순, §0.4-3) */
	private List<Long> pick(String grade, List<Long> free, int seatCount) {
		if (free.isEmpty()) {
			return List.of();
		}
		String in = String.join(",", free.stream().map(String::valueOf).toList());
		return jdbc.queryForList("SELECT seat_id FROM seat WHERE seat_id IN (" + in + ") AND seat_grade = ?"
				+ " ORDER BY seat_id FETCH FIRST " + seatCount + " ROWS ONLY", Long.class, grade);
	}

	/**
	 * WL-04 제안 알림. 보내는 것(채널)은 C 길동현의 notification 소비자가 하고, 여기서는 같은 트랜잭션에
	 * 행만 남긴다 (§9.1-6). 제안과 알림이 같이 커밋되거나 같이 롤백돼야 한다.
	 */
	private void notify(long memberId, String type, String refType, long refId) {
		jdbc.update("""
				INSERT INTO notification (notification_id, member_id, type, ref_type, ref_id, channel)
				VALUES (notification_seq.NEXTVAL, ?, ?, ?, ?, 'PUSH')""",
				memberId, type, refType, refId);
	}
}
