package com.jejari.group;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.config.AppPolicy;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.common.pii.Encrypted;
import com.jejari.common.pii.PiiCipher;
import com.jejari.common.pii.PiiField;
import com.jejari.member.MemberAuthEnabled;

/**
 * 단체 명단 변경 (코레일 단체승차권 기준: 1회, 예약 뒤 group.roster_change_days일 이내).
 *
 * <ul>
 * <li>바꿀 수 있는 것: 탑승자의 이름·휴대폰(또는 휴대폰 없음). 인원·좌석·어른/어린이는 그대로 (금액이 바뀌지 않게).</li>
 * <li>대표자(1번)는 바꾸지 않는다. 대표자 전화 인증이 단체 관문이기 때문.</li>
 * <li>승인(예약)된 단체만, 예약이 살아 있을 때(HOLD·결제 중·확정)만, 출발 전에만.</li>
 * <li>바꾼 뒤 경고(휴대폰 없음 비율·다른 단체와 번호 겹침)를 다시 계산한다. 한 번 켜진 경고는 끄지 않는다 (관리자가 본 기록 유지).</li>
 * </ul>
 * 동시에 두 번 눌러도 한 번만 바뀐다: group_booking을 잠그고 roster_changed_at이 비어 있는지 확인한다.
 */
@Service
@MemberAuthEnabled
public class GroupRosterService {

	private static final Logger log = LoggerFactory.getLogger(GroupRosterService.class);

	/** 바꿀 탑승자 한 명 (passengerNo는 명단 순번, 2번부터) */
	public record Change(int passengerNo, String name, String phone, boolean noPhone) {
	}

	private record Clean(int no, String name, String phone, String phoneHash) {
	}

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final PiiCipher cipher;
	private final Pseudonymizer pseudonymizer;
	private final AppPolicy policy;
	private final Clock clock;

	public GroupRosterService(JdbcTemplate jdbc, TransactionTemplate tx, PiiCipher cipher, Pseudonymizer pseudonymizer,
			AppPolicy policy, Clock clock) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.cipher = cipher;
		this.pseudonymizer = pseudonymizer;
		this.policy = policy;
		this.clock = clock;
	}

	public void change(long memberId, long groupId, List<Change> changes) {
		if (changes == null || changes.isEmpty()) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		Map<Integer, Clean> byNo = new HashMap<>();
		for (Change c : changes) {
			if (c == null || c.passengerNo() < 2 || byNo.containsKey(c.passengerNo())) {
				throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "대표자(1번)는 바꿀 수 없고, 한 사람은 한 번만 적어 주세요.");
			}
			String name = GroupApplyService.validName(c.name());
			String phone = c.noPhone() ? null : GroupApplyService.normalizePhone(c.phone());
			byNo.put(c.passengerNo(), new Clean(c.passengerNo(), name, phone, phone == null ? null : pseudonymizer.phone(phone)));
		}
		LocalDateTime now = LocalDateTime.now(clock);
		tx.executeWithoutResult(st -> write(memberId, groupId, byNo, now));
		log.info("단체 {} 명단 변경 {}명", groupId, byNo.size());
	}

	private void write(long memberId, long groupId, Map<Integer, Clean> byNo, LocalDateTime now) {
		// 1. 잠금 + 조건 (락 순서: group_booking 먼저, §0.4-7)
		record G(long rep, String status, LocalDateTime approvedAt, LocalDateTime changedAt, String resvStatus,
				LocalDateTime departure) {
		}
		List<G> rows = jdbc.query("""
				SELECT b.rep_member_id, b.status, b.approved_at, b.roster_changed_at,
				       (SELECT r.status FROM reservation r WHERE r.reservation_id = b.reservation_id),
				       (SELECT MIN(s.departure_time) FROM train_stop s WHERE s.run_id = b.run_id)
				  FROM group_booking b WHERE b.group_id = ? FOR UPDATE OF b.status""",
				(rs, i) -> new G(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDateTime.class),
						rs.getObject(4, LocalDateTime.class), rs.getString(5), rs.getObject(6, LocalDateTime.class)), groupId);
		if (rows.isEmpty() || rows.get(0).rep() != memberId) {
			throw new BusinessException(GroupErrorCode.GROUP_NOT_FOUND);
		}
		G g = rows.get(0);
		boolean alive = Set.of("HOLD", "PAYMENT_PENDING", "CONFIRMED").contains(g.resvStatus());
		boolean inWindow = g.approvedAt() != null
				&& !now.isAfter(g.approvedAt().plusDays(policy.getLong(AppPolicy.GROUP_ROSTER_CHANGE_DAYS)));
		boolean beforeDeparture = g.departure() != null && g.departure().isAfter(now);
		if (!"APPROVED".equals(g.status()) || g.changedAt() != null || !alive || !inWindow || !beforeDeparture) {
			throw new BusinessException(GroupErrorCode.GROUP_ROSTER_LOCKED);
		}

		// 2. 바뀐 뒤 명단의 번호가 서로 겹치지 않는지 (해시로 비교)
		record P(int no, String phoneHash) {
		}
		List<P> current = jdbc.query("SELECT passenger_no, phone_hash FROM group_passenger WHERE group_id = ? ORDER BY passenger_no",
				(rs, i) -> new P(rs.getInt(1), rs.getString(2)), groupId);
		Set<Integer> nos = new HashSet<>();
		current.forEach(p -> nos.add(p.no()));
		if (!nos.containsAll(byNo.keySet())) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "명단에 없는 순번이 있습니다.");
		}
		Set<String> seen = new HashSet<>();
		int noPhone = 0;
		for (P p : current) {
			String h = byNo.containsKey(p.no()) ? byNo.get(p.no()).phoneHash() : p.phoneHash();
			if (h == null) {
				noPhone++;
			} else if (!seen.add(h)) {
				throw new BusinessException(GroupErrorCode.GROUP_DUPLICATE_PHONE);
			}
		}

		// 3. 바꾸기 (이름·번호 다시 암호화, 행 ID = "{group_id}-{passenger_no}")
		for (Clean c : byNo.values()) {
			String row = groupId + "-" + c.no();
			Encrypted name = cipher.encrypt(c.name(), PiiField.GROUP_PASSENGER_NAME, row);
			Encrypted phone = c.phone() == null ? null : cipher.encrypt(c.phone(), PiiField.GROUP_PASSENGER_PHONE, row);
			jdbc.update("""
					UPDATE group_passenger SET name_enc = ?, phone_enc = ?, enc_key_id = ?, phone_hash = ?, no_phone_yn = ?,
					       updated_at = SYSTIMESTAMP
					 WHERE group_id = ? AND passenger_no = ?""",
					name.cipherText(), phone == null ? null : phone.cipherText(), name.keyId(), c.phoneHash(),
					c.phoneHash() == null ? "Y" : "N", groupId, c.no());
		}

		// 4. 경고 다시 계산 (켜기만) + 변경 시각 (= 1회 사용)
		boolean warnNoPhone = (double) noPhone / current.size() > policy.getDouble(AppPolicy.GROUP_NO_PHONE_WARN_RATE);
		boolean warnDup = !seen.isEmpty() && usedElsewhere(groupId, seen);
		jdbc.update("""
				UPDATE group_booking SET roster_changed_at = ?,
				       warn_no_phone_yn = CASE WHEN ? = 'Y' THEN 'Y' ELSE warn_no_phone_yn END,
				       warn_dup_phone_yn = CASE WHEN ? = 'Y' THEN 'Y' ELSE warn_dup_phone_yn END,
				       updated_at = SYSTIMESTAMP
				 WHERE group_id = ? AND roster_changed_at IS NULL""",
				now, warnNoPhone ? "Y" : "N", warnDup ? "Y" : "N", groupId);
	}

	private boolean usedElsewhere(long groupId, Set<String> hashes) {
		String in = String.join(",", hashes.stream().map(h -> "?").toList());
		Object[] args = new Object[hashes.size() + 1];
		int i = 0;
		for (String h : hashes) {
			args[i++] = h;
		}
		args[i] = groupId;
		Integer n = jdbc.queryForObject("""
				SELECT COUNT(*) FROM group_passenger p JOIN group_booking b ON b.group_id = p.group_id
				 WHERE p.phone_hash IN (%s) AND p.group_id <> ? AND p.status = 'ACTIVE'
				   AND b.status IN ('REQUESTED', 'APPROVED')""".formatted(in), Integer.class, args);
		return n != null && n > 0;
	}
}
