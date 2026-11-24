package com.jejari.group;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jejari.common.error.BusinessException;
import com.jejari.common.pii.PiiCipher;
import com.jejari.common.pii.PiiField;
import com.jejari.common.pii.PiiMasker;

/**
 * 단체 신청 조회 (대표자 본인 화면, 관리자 화면). 탑승자 이름·번호는 <b>마스킹해서만</b> 내보냅니다.
 * 결제·만료 결과는 reservation.status가 원천이라 같이 보여 줍니다 (§18.5).
 */
@Service
public class GroupQueryService {

	private static final Logger log = LoggerFactory.getLogger(GroupQueryService.class);

	public record Summary(long groupId, long repMemberId, long runId, String fromStationId, String toStationId,
			int passengerCount, String status, Long reservationId, String reservationStatus, LocalDateTime payDeadlineAt,
			boolean discount, boolean warnNoPhone, boolean warnDupPhone, boolean autoApproved, String cancelReason,
			LocalDateTime createdAt, LocalDateTime approvedAt, LocalDateTime rosterChangedAt) {
	}

	public record PassengerView(int passengerNo, String name, String phone, boolean noPhone, String passengerType,
			boolean rep, Long seatId, Integer carNo, String seatNo, String status) {
	}

	public record Detail(Summary summary, List<PassengerView> passengers) {
	}

	private static final String SUMMARY = """
			SELECT b.group_id, b.rep_member_id, b.run_id, b.from_station_id, b.to_station_id, b.passenger_count, b.status,
			       b.reservation_id, r.status AS rstatus, r.pay_deadline_at, b.discount_yn, b.warn_no_phone_yn,
			       b.warn_dup_phone_yn, b.approved_by, b.cancel_reason, b.created_at, b.approved_at,
			       b.roster_changed_at
			  FROM group_booking b LEFT JOIN reservation r ON r.reservation_id = b.reservation_id""";

	private final JdbcTemplate jdbc;
	private final ObjectProvider<PiiCipher> cipher;

	public GroupQueryService(JdbcTemplate jdbc, ObjectProvider<PiiCipher> cipher) {
		this.jdbc = jdbc;
		this.cipher = cipher;
	}

	/** 대표자 본인의 신청 목록 (최근 순) */
	public List<Summary> mine(long memberId) {
		return jdbc.query(SUMMARY + " WHERE b.rep_member_id = ? ORDER BY b.created_at DESC FETCH FIRST 50 ROWS ONLY",
				(rs, i) -> summary(rs), memberId);
	}

	/** 대표자 본인 것만. 남의 신청은 없는 것과 같게 404 */
	public Detail mineDetail(long memberId, long groupId) {
		Detail d = detail(groupId);
		if (d.summary().repMemberId() != memberId) {
			throw new BusinessException(GroupErrorCode.GROUP_NOT_FOUND);
		}
		return d;
	}

	/**
	 * 관리자 목록. 경고(휴대폰 없음 비율·번호 겹침)가 있는 신청을 위로.
	 *
	 * @param status null이면 전체
	 */
	public List<Summary> list(String status, int limit) {
		int n = Math.max(1, Math.min(limit, 200));
		String where = status == null ? "" : " WHERE b.status = ?";
		String order = " ORDER BY CASE WHEN b.warn_no_phone_yn = 'Y' OR b.warn_dup_phone_yn = 'Y' THEN 0 ELSE 1 END,"
				+ " b.created_at DESC FETCH FIRST " + n + " ROWS ONLY";
		Object[] args = status == null ? new Object[0] : new Object[] { status };
		return jdbc.query(SUMMARY + where + order, (rs, i) -> summary(rs), args);
	}

	public Detail detail(long groupId) {
		Summary s = jdbc.query(SUMMARY + " WHERE b.group_id = ?", (rs, i) -> summary(rs), groupId).stream().findFirst()
				.orElseThrow(() -> new BusinessException(GroupErrorCode.GROUP_NOT_FOUND));
		List<PassengerView> pax = new ArrayList<>();
		jdbc.query("""
				SELECT p.passenger_no, p.name_enc, p.phone_enc, p.no_phone_yn, p.passenger_type, p.rep_yn, p.seat_id, p.status,
				       s.car_no, s.seat_no
				  FROM group_passenger p LEFT JOIN seat s ON s.seat_id = p.seat_id
				 WHERE p.group_id = ? ORDER BY p.passenger_no""", rs -> {
			int no = rs.getInt(1);
			String row = groupId + "-" + no;
			long seat = rs.getLong(7);
			Long seatId = rs.wasNull() ? null : seat;
			pax.add(new PassengerView(no, masked(rs.getString(2), PiiField.GROUP_PASSENGER_NAME, row, true),
					masked(rs.getString(3), PiiField.GROUP_PASSENGER_PHONE, row, false), "Y".equals(rs.getString(4)),
					rs.getString(5), "Y".equals(rs.getString(6)), seatId, seatId == null ? null : rs.getInt(9),
					rs.getString(10), rs.getString(8)));
		}, groupId);
		return new Detail(s, pax);
	}

	private String masked(String cipherText, PiiField field, String row, boolean name) {
		if (cipherText == null) {
			return null;
		}
		PiiCipher c = cipher.getIfAvailable();
		if (c == null) {
			return "***";
		}
		try {
			String plain = c.decrypt(cipherText, field, row);
			return name ? PiiMasker.name(plain) : PiiMasker.phone(plain);
		} catch (RuntimeException e) {
			log.warn("단체 탑승자 복호화 실패 {}", field); // 값은 남기지 않음
			return "***";
		}
	}

	private static Summary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
		long rid = rs.getLong(8);
		Long reservationId = rs.wasNull() ? null : rid;
		rs.getLong(14);
		boolean auto = rs.wasNull();
		return new Summary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getInt(6),
				rs.getString(7), reservationId, rs.getString(9), rs.getObject(10, LocalDateTime.class),
				"Y".equals(rs.getString(11)), "Y".equals(rs.getString(12)), "Y".equals(rs.getString(13)),
				reservationId != null && auto, rs.getString(15), rs.getObject(16, LocalDateTime.class),
				rs.getObject(17, LocalDateTime.class), rs.getObject(18, LocalDateTime.class));
	}
}
