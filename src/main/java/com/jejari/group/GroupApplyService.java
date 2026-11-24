package com.jejari.group;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import com.jejari.group.GroupAllocator.Outcome;
import com.jejari.group.GroupAllocator.Result;
import com.jejari.member.GuestGuard;
import com.jejari.member.MemberAuthEnabled;
import com.jejari.train.RunInfo;
import com.jejari.train.TrainRunReader;

/**
 * 여행사 단체 신청 (코레일 단체승차권 기준, concurrency.md §18).
 *
 * <pre>
 * 검증: 대표자 = 로그인한 정회원(가입 때 본인인증한 번호와 같은지 HMAC 비교), 같은 구간 11~200명, 동의 체크,
 *       판매 오픈(출발 2개월 전 07시) 뒤 ~ 출발 전
 * 저장: group_booking(REQUESTED) + group_passenger(이름·번호 AES-256-GCM, 번호 HMAC) 한 트랜잭션
 * 경고: 휴대폰 없음 비율 > group.no_phone_warn_rate, 다른 단체와 번호 겹침 → 관리자 화면 표시 (막지는 않음)
 * 그다음 바로 자동 승인 시도 (GroupAllocator). 미뤄지면 REQUESTED로 남고 스캐너가 다시 함 (§18.3b)
 * </pre>
 *
 * <p>탑승자 번호는 인증하지 않으므로 개인 20매 카운터에 넣지 않습니다. 대신 번호 겹침이 표팔이 신호입니다.
 */
@Service
@MemberAuthEnabled
public class GroupApplyService {

	private static final Logger log = LoggerFactory.getLogger(GroupApplyService.class);

	private static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{M}][\\p{L}\\p{M} ·.'\\-]{0,48}[\\p{L}\\p{M}.]$|^[\\p{L}]$");
	private static final Pattern PHONE_CHARS = Pattern.compile("^[0-9 ()+\\-]{9,20}$");
	private static final Pattern KR_MOBILE = Pattern.compile("^(010[0-9]{8}|01[16789][0-9]{7,8})$");
	private static final Set<String> TYPES = Set.of("ADULT", "CHILD");

	public record PassengerInput(String name, String phone, boolean noPhone, String passengerType, boolean rep) {
	}

	public record ApplyCommand(Long runId, String fromStationId, String toStationId, boolean consent,
			List<PassengerInput> passengers) {
	}

	public record ApplyResult(long groupId, String status, Outcome outcome, Long reservationId,
			LocalDateTime payDeadlineAt, long totalAmount, boolean discount, boolean warnNoPhone, boolean warnDupPhone) {
	}

	/** 검증을 통과한 탑승자 (번호는 숫자만) */
	private record Clean(int no, String name, String phone, String phoneHash, String type, boolean rep) {
	}

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final StringRedisTemplate redis;
	private final TrainRunReader runs;
	private final GroupAllocator allocator;
	private final GuestGuard guests;
	private final PiiCipher cipher;
	private final Pseudonymizer pseudonymizer;
	private final AppPolicy policy;
	private final Clock clock;

	public GroupApplyService(JdbcTemplate jdbc, TransactionTemplate tx, StringRedisTemplate redis, TrainRunReader runs,
			GroupAllocator allocator, GuestGuard guests, PiiCipher cipher, Pseudonymizer pseudonymizer,
			AppPolicy policy, Clock clock) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.redis = redis;
		this.runs = runs;
		this.allocator = allocator;
		this.guests = guests;
		this.cipher = cipher;
		this.pseudonymizer = pseudonymizer;
		this.policy = policy;
		this.clock = clock;
	}

	public ApplyResult apply(long memberId, ApplyCommand cmd) {
		LocalDateTime now = LocalDateTime.now(clock);
		if (cmd == null || cmd.runId() == null || cmd.passengers() == null) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		if (!cmd.consent()) {
			throw new BusinessException(GroupErrorCode.GROUP_CONSENT_REQUIRED);
		}
		int n = cmd.passengers().size();
		if (n < policy.getLong(AppPolicy.GROUP_MIN_SIZE) || n > policy.getLong(AppPolicy.GROUP_MAX_SIZE)) {
			throw new BusinessException(GroupErrorCode.GROUP_SIZE_OUT_OF_RANGE);
		}
		guests.forbidGuest(memberId);
		RunInfo run = openRun(cmd.runId(), now);
		if (!run.validSegment(cmd.fromStationId(), cmd.toStationId())) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "승차역·하차역이 이 열차의 정차역 순서와 맞지 않습니다.");
		}
		List<Clean> pax = clean(cmd.passengers());
		checkRep(memberId, pax);

		// 같은 대표자가 같은 열차에 두 번 누르는 것 막기 (빠른 경로 10초 + DB 확인)
		String lock = "group:apply:" + memberId + ":" + run.runId();
		if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, "1", Duration.ofSeconds(10)))) {
			throw new BusinessException(GroupErrorCode.GROUP_ALREADY_REQUESTED);
		}
		Integer open = jdbc.queryForObject("""
				SELECT COUNT(*) FROM group_booking WHERE rep_member_id = ? AND run_id = ? AND status = 'REQUESTED'""",
				Integer.class, memberId, run.runId());
		if (open != null && open > 0) {
			throw new BusinessException(GroupErrorCode.GROUP_ALREADY_REQUESTED);
		}

		long noPhone = pax.stream().filter(p -> p.phoneHash() == null).count();
		boolean warnNoPhone = (double) noPhone / n > policy.getDouble(AppPolicy.GROUP_NO_PHONE_WARN_RATE);
		boolean warnDup = phoneUsedElsewhere(pax);
		boolean discount = !run.departureAt().isAfter(now.plusDays(policy.getLong(AppPolicy.GROUP_DISCOUNT_WITHIN_DAYS)));

		long groupId = jdbc.queryForObject("SELECT reservation_seq.NEXTVAL FROM dual", Long.class); // [가정] 전용 시퀀스가 생기면 교체
		tx.executeWithoutResult(st -> insert(groupId, memberId, run.runId(), cmd, pax, now, discount, warnNoPhone, warnDup));
		log.info("단체 신청 {} run={} 인원={} 휴대폰없음={} 번호겹침={}", groupId, run.runId(), n, noPhone, warnDup);

		// 신호가 없으면 바로 자동 승인 (위험 점수가 높으면 REVIEW로 관리자 판단)
		Result r = allocator.approve(groupId, null, false, false);
		String status = r.outcome() == Outcome.APPROVED ? "APPROVED" : "REQUESTED";
		return new ApplyResult(groupId, status, r.outcome(), r.reservationId(), r.payDeadlineAt(), r.totalAmount(),
				discount, warnNoPhone, warnDup);
	}

	/** 신청 철회 (승인 전만). 승인 뒤에는 예약 취소(§8)로 */
	public void withdraw(long memberId, long groupId) {
		int n = jdbc.update("""
				UPDATE group_booking SET status = 'WITHDRAWN', updated_at = SYSTIMESTAMP
				 WHERE group_id = ? AND rep_member_id = ? AND status = 'REQUESTED'""", groupId, memberId);
		if (n != 1) {
			Integer mine = jdbc.queryForObject("SELECT COUNT(*) FROM group_booking WHERE group_id = ? AND rep_member_id = ?",
					Integer.class, groupId, memberId);
			throw new BusinessException(mine != null && mine > 0 ? GroupErrorCode.GROUP_NOT_REQUESTED : GroupErrorCode.GROUP_NOT_FOUND);
		}
	}

	// ---------------------------------------------------------------- 검증

	/** 판매 오픈 뒤 ~ 판매 마감·출발 전. 오픈 시각은 개인 예매와 같음(출발 2개월 전 07시) */
	private RunInfo openRun(long runId, LocalDateTime now) {
		RunInfo run = runs.find(runId).orElseThrow(() -> new BusinessException(GroupErrorCode.GROUP_NOT_OPEN));
		List<LocalDateTime> openAt = jdbc.queryForList("SELECT sale_open_at FROM train_run WHERE run_id = ?",
				LocalDateTime.class, runId);
		boolean opened = !openAt.isEmpty() && openAt.get(0) != null && !openAt.get(0).isAfter(now);
		boolean alive = ("SCHEDULED".equals(run.status()) || RunInfo.ON_SALE.equals(run.status()))
				&& (run.saleCloseAt() == null || run.saleCloseAt().isAfter(now))
				&& run.departureAt() != null && run.departureAt().isAfter(now);
		if (!opened || !alive) {
			throw new BusinessException(GroupErrorCode.GROUP_NOT_OPEN);
		}
		return run;
	}

	private List<Clean> clean(List<PassengerInput> in) {
		List<Clean> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		int reps = 0;
		int no = 2; // 대표자가 1번
		for (PassengerInput p : in) {
			if (p == null) {
				throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
			}
			String name = p.name() == null ? null : p.name().strip();
			if (name == null || !NAME.matcher(name).matches()) {
				throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "탑승자 이름 형식이 올바르지 않습니다.");
			}
			String type = p.passengerType() == null ? "ADULT" : p.passengerType();
			if (!TYPES.contains(type)) {
				throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
			}
			String phone = null;
			String hash = null;
			if (!p.noPhone()) {
				phone = normalizePhone(p.phone());
				if (!seen.add(phone)) {
					throw new BusinessException(GroupErrorCode.GROUP_DUPLICATE_PHONE);
				}
				hash = pseudonymizer.phone(phone);
			}
			if (p.rep()) {
				reps++;
				if (phone == null) {
					throw new BusinessException(GroupErrorCode.GROUP_REP_INVALID);
				}
			}
			out.add(new Clean(p.rep() ? 1 : no++, name, phone, hash, type, p.rep()));
		}
		if (reps != 1) {
			throw new BusinessException(GroupErrorCode.GROUP_REP_INVALID);
		}
		out.sort((a, b) -> Integer.compare(a.no(), b.no()));
		return out;
	}

	/** 대표자 전화 인증: 가입 때 본인인증한 번호(member.phone_hash)와 같아야 한다 */
	private void checkRep(long memberId, List<Clean> pax) {
		List<String> hash = jdbc.queryForList("SELECT phone_hash FROM member WHERE member_id = ? AND status = 'ACTIVE'",
				String.class, memberId);
		Clean rep = pax.get(0);
		if (hash.isEmpty() || hash.get(0) == null || !hash.get(0).equals(rep.phoneHash())) {
			throw new BusinessException(GroupErrorCode.GROUP_REP_PHONE_MISMATCH);
		}
	}

	/** 처리 중·승인된 다른 단체 명단에 같은 번호가 있는가 (같은 명단 돌려쓰기 = 표팔이 신호) */
	private boolean phoneUsedElsewhere(List<Clean> pax) {
		List<String> hashes = pax.stream().map(Clean::phoneHash).filter(h -> h != null).toList();
		if (hashes.isEmpty()) {
			return false;
		}
		String in = String.join(",", hashes.stream().map(h -> "?").toList());
		Integer n = jdbc.queryForObject("""
				SELECT COUNT(*) FROM group_passenger p JOIN group_booking b ON b.group_id = p.group_id
				 WHERE p.phone_hash IN (%s) AND p.status = 'ACTIVE' AND b.status IN ('REQUESTED', 'APPROVED')""".formatted(in),
				Integer.class, hashes.toArray());
		return n != null && n > 0;
	}

	static String normalizePhone(String phone) {
		if (phone == null || !PHONE_CHARS.matcher(phone.strip()).matches()) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "휴대폰 번호 형식이 올바르지 않습니다.");
		}
		String digits = phone.replaceAll("[^0-9]", "");
		if (digits.startsWith("82") && digits.length() >= 11 && digits.length() <= 12) {
			digits = "0" + digits.substring(2);
		}
		if (!KR_MOBILE.matcher(digits).matches()) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "휴대폰 번호 형식이 올바르지 않습니다.");
		}
		return digits;
	}

	// ---------------------------------------------------------------- 저장

	private void insert(long groupId, long memberId, long runId, ApplyCommand cmd, List<Clean> pax, LocalDateTime now,
			boolean discount, boolean warnNoPhone, boolean warnDup) {
		jdbc.update("""
				INSERT INTO group_booking (group_id, rep_member_id, run_id, from_station_id, to_station_id, passenger_count,
				       status, discount_yn, consent_at, warn_no_phone_yn, warn_dup_phone_yn)
				VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?, ?)""",
				groupId, memberId, runId, cmd.fromStationId(), cmd.toStationId(), pax.size(), yn(discount), now,
				yn(warnNoPhone), yn(warnDup));
		jdbc.batchUpdate("""
				INSERT INTO group_passenger (group_id, passenger_no, name_enc, phone_enc, enc_key_id, phone_hash,
				       no_phone_yn, passenger_type, rep_yn, status)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')""", pax, 200, (ps, p) -> {
			String row = groupId + ":" + p.no();
			Encrypted name = cipher.encrypt(p.name(), PiiField.GROUP_PASSENGER_NAME, row);
			Encrypted phone = p.phone() == null ? null : cipher.encrypt(p.phone(), PiiField.GROUP_PASSENGER_PHONE, row);
			ps.setLong(1, groupId);
			ps.setInt(2, p.no());
			ps.setString(3, name.cipherText());
			ps.setString(4, phone == null ? null : phone.cipherText());
			ps.setString(5, name.keyId());
			ps.setString(6, p.phoneHash());
			ps.setString(7, yn(p.phoneHash() == null));
			ps.setString(8, p.type());
			ps.setString(9, yn(p.rep()));
		});
	}

	private static String yn(boolean b) {
		return b ? "Y" : "N";
	}
}
