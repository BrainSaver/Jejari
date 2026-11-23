package com.jejari.notification;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jejari.common.error.BusinessException;

/**
 * 알림함 (U-11). 보낸 알림({@code sent_at}이 있는 것)만 보여 줍니다. 아직 발송 전인 행은 1초쯤 뒤에 나타납니다.
 * 회원은 자기 알림만 보고 읽음 표시할 수 있습니다 (남의 알림 ID는 없는 것과 같게 404).
 */
@Service
public class NotificationService {

	public static final int MAX_SIZE = 50;

	/**
	 * @param trip 예약 알림이면 그 표 요약 (열차·날짜·출발 시각·구간·좌석). 화면 문구 "00049 열차 11월 19일 15:45 서울→부산 · 2호차 7A"
	 */
	public record Item(long notificationId, String type, String refType, Long refId, String channel,
			LocalDateTime createdAt, LocalDateTime sentAt, LocalDateTime readAt, Trip trip) {
	}

	/** 표 요약. 내 예약일 때만 채운다 (남의 예약 번호가 들어 있어도 내용은 안 나감) */
	public record Trip(String trainNo, java.time.LocalDate runDate, LocalDateTime departureAt, String fromName,
			String toName, String seats) {
	}

	/** @param nextBefore 다음 페이지를 볼 때 {@code before}로 보낼 값. 마지막 페이지면 null */
	public record Page(List<Item> items, long unreadCount, Long nextBefore) {
	}

	/** 예약 알림에 표 요약을 붙인다. 한 번의 조회로 (알림함 한 쪽 = 최대 50건) */
	List<Item> withTrips(long memberId, List<Item> items) {
		List<Long> ids = items.stream().filter(it -> "RESERVATION".equals(it.refType()) && it.refId() != null)
				.map(Item::refId).distinct().toList();
		if (ids.isEmpty()) {
			return items;
		}
		java.util.Map<Long, Trip> trips = new java.util.HashMap<>();
		jdbc.query("""
				SELECT r.reservation_id, t.train_no, tr.run_date, sd.departure_time, fs.station_name, ts.station_name,
				       (SELECT LISTAGG(s.car_no || '호차 ' || s.seat_no, ', ') WITHIN GROUP (ORDER BY s.car_no, s.seat_no)
				          FROM reservation_seat rs JOIN seat s ON s.seat_id = rs.seat_id
				         WHERE rs.reservation_id = r.reservation_id) AS seats
				  FROM reservation r
				  JOIN train_run tr ON tr.run_id = r.run_id
				  JOIN train t ON t.train_id = tr.train_id
				  JOIN station fs ON fs.station_id = r.from_station_id
				  JOIN station ts ON ts.station_id = r.to_station_id
				  LEFT JOIN train_stop sd ON sd.run_id = r.run_id AND sd.station_id = r.from_station_id
				 WHERE r.member_id = ? AND r.reservation_id IN (""" + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")",
				rs -> {
					trips.put(rs.getLong(1), new Trip(rs.getString(2), rs.getObject(3, java.time.LocalDate.class),
							rs.getObject(4, LocalDateTime.class), rs.getString(5), rs.getString(6), rs.getString(7)));
				}, java.util.stream.Stream.concat(java.util.stream.Stream.of(memberId), ids.stream()).toArray());
		return items.stream().map(it -> trips.containsKey(it.refId()) && "RESERVATION".equals(it.refType())
				? new Item(it.notificationId(), it.type(), it.refType(), it.refId(), it.channel(), it.createdAt(),
						it.sentAt(), it.readAt(), trips.get(it.refId()))
				: it).toList();
	}

	private final JdbcTemplate jdbc;

	public NotificationService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 최신순. {@code before}가 있으면 그 ID보다 오래된 것부터 */
	public Page list(long memberId, Long before, int size) {
		int n = Math.max(1, Math.min(size, MAX_SIZE));
		List<Item> items = jdbc.query("""
				SELECT notification_id, type, ref_type, ref_id, channel, created_at, sent_at, read_at
				  FROM notification
				 WHERE member_id = ? AND sent_at IS NOT NULL AND notification_id < ?
				 ORDER BY notification_id DESC
				 FETCH FIRST ? ROWS ONLY""",
				(rs, i) -> new Item(rs.getLong(1), rs.getString(2), rs.getString(3),
						rs.getObject(4) == null ? null : rs.getLong(4), rs.getString(5),
						rs.getObject(6, LocalDateTime.class), rs.getObject(7, LocalDateTime.class),
						rs.getObject(8, LocalDateTime.class), null),
				memberId, before == null ? Long.MAX_VALUE : before, n + 1);
		Long next = null;
		if (items.size() > n) {
			items = items.subList(0, n);
			next = items.get(n - 1).notificationId();
		}
		items = withTrips(memberId, items);
		Long unread = jdbc.queryForObject("""
				SELECT COUNT(*) FROM notification WHERE member_id = ? AND sent_at IS NOT NULL AND read_at IS NULL""",
				Long.class, memberId);
		return new Page(List.copyOf(items), unread == null ? 0 : unread, next);
	}

	/** 읽음 표시. 이미 읽었으면 그대로 두고 성공 (처음 읽은 시각 유지) */
	public void markRead(long memberId, long notificationId) {
		int n = jdbc.update("""
				UPDATE notification SET read_at = SYSTIMESTAMP, updated_at = SYSTIMESTAMP
				 WHERE notification_id = ? AND member_id = ? AND sent_at IS NOT NULL AND read_at IS NULL""",
				notificationId, memberId);
		if (n == 1) {
			return;
		}
		Long mine = jdbc.queryForObject("""
				SELECT COUNT(*) FROM notification WHERE notification_id = ? AND member_id = ? AND sent_at IS NOT NULL""",
				Long.class, notificationId, memberId);
		if (mine == null || mine == 0) {
			throw new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
		}
	}
}
