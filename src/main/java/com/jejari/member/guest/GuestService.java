package com.jejari.member.guest;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.auth.ActorType;
import com.jejari.auth.AuthErrorCode;
import com.jejari.auth.session.AuthTokenService;
import com.jejari.auth.session.AuthTokenService.IssuedTokens;
import com.jejari.auth.session.ClientInfo;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.common.pii.Encrypted;
import com.jejari.common.pii.PiiCipher;
import com.jejari.common.pii.PiiField;
import com.jejari.member.AuthLimiter;
import com.jejari.member.AuthPolicy;
import com.jejari.member.MemberAuthEnabled;
import com.jejari.member.verify.VerificationTokens;
import com.jejari.member.verify.VerifiedIdentity;

/**
 * 비회원(게스트) 예매. 설계: docs/김성원/팀-확인-요청-비회원-예매.md, ERD v33 (docs/강동균/김성원-답장-비회원-예매-20261123.md).
 *
 * <pre>
 * 입장: 본인인증(verify) 토큰 → 같은 CI의 GUEST 행을 다시 쓰거나 새로 만든다 → 세션 행 + 액세스 토큰 (비밀번호 없음)
 * 조회: 예약 번호(PK) + 휴대폰 HMAC이 그 예약 회원의 phone_hash와 같은지 (IP당 시간당 제한)
 * 삭제: 마지막 운행일 + guest.retain_days_after_run이 지나면 이름·연락처를 지운다
 * </pre>
 *
 * <ul>
 *   <li><b>게스트는 회원이다</b>: member 행이 있고 status는 ACTIVE로 시작해 관리자 제한·정지가 똑같이 걸린다
 *       (게스트 표시는 status가 아니라 member_type). 매수 제한·위험 점수도 같은 CI로 동작한다.</li>
 *   <li><b>세션 행을 만든다</b>: 세션이 없으면 행동 로그·녹화가 빠지고, 관리자 정지로 쫓아낼 수도 없다.</li>
 *   <li><b>같은 CI는 한 행</b>: 위험 점수가 같은 CI에 달린 계정 수를 세므로 예매마다 새로 만들면 정직한 사용자가 다계정으로 보인다.
 *       이미 회원인 CI면 GUEST_IS_MEMBER(로그인 안내). 동시 입장 두 건은 CI별 Redis 잠금으로 한 줄로 세운다.</li>
 *   <li><b>정지된 CI</b>(탈퇴한 정지 회원 포함)는 게스트로도 들어올 수 없다 (재가입으로 정지를 피하는 길 차단).</li>
 * </ul>
 */
@Service
@MemberAuthEnabled
public class GuestService {
	private static final Logger log = LoggerFactory.getLogger(GuestService.class);

	static final String GUEST_NAME_AFTER_PURGE = "비회원";
	private static final Duration ENTER_LOCK = Duration.ofSeconds(15);

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final StringRedisTemplate redis;
	private final VerificationTokens tokens;
	private final AuthTokenService sessions;
	private final PiiCipher cipher;
	private final Pseudonymizer pseudonymizer;
	private final AuthLimiter limiter;
	private final AuthPolicy policy;
	private final Clock clock;

	public GuestService(JdbcTemplate jdbc, TransactionTemplate tx, StringRedisTemplate redis, VerificationTokens tokens,
			AuthTokenService sessions, PiiCipher cipher, Pseudonymizer pseudonymizer, AuthLimiter limiter, AuthPolicy policy,
			Clock clock) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.redis = redis;
		this.tokens = tokens;
		this.sessions = sessions;
		this.cipher = cipher;
		this.pseudonymizer = pseudonymizer;
		this.limiter = limiter;
		this.policy = policy;
		this.clock = clock;
	}

	public boolean enabled() {
		return policy.flag(AuthPolicy.GUEST_ENABLED);
	}

	// ------------------------------------------------------------ 입장

	public IssuedTokens enter(String verificationToken, String ipHash, String userAgent) {
		if (!enabled()) {
			throw new BusinessException(GuestErrorCode.GUEST_DISABLED);
		}
		// 토큰은 쓰기 전에 들여다본다: 이미 회원인 CI면 토큰을 태우지 않고 거절해, 로그인 뒤 처음부터 다시 인증하지 않게 한다
		VerifiedIdentity peeked = tokens.peek(verificationToken);
		String lockKey = "guest:enter:" + peeked.ciHash();
		if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, "1", ENTER_LOCK))) {
			throw new BusinessException(GuestErrorCode.GUEST_BUSY);
		}
		try {
			checkAllowed(peeked.ciHash());
			VerifiedIdentity id = tokens.consume(verificationToken);
			long memberId = tx.execute(st -> upsertGuest(id));
			IssuedTokens issued = sessions.issue(ActorType.MEMBER, memberId, new ClientInfo(null, ipHash, userAgent), false);
			log.info("비회원 입장 member={} (세션 {})", memberId, issued.sessionId());
			return issued;
		} finally {
			redis.delete(lockKey);
		}
	}

	/** 이미 회원인 CI → 로그인 안내, 정지된 CI → 거절 */
	private void checkAllowed(String ciHash) {
		List<String[]> rows = jdbc.query("""
				SELECT member_type, status, withdrawn_at FROM member WHERE ci_hash = ?""",
				(rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, ciHash);
		for (String[] r : rows) {
			if ("BANNED".equals(r[1])) {
				throw new BusinessException(AuthErrorCode.AUTH_BANNED);
			}
		}
		for (String[] r : rows) {
			boolean guest = "GUEST".equals(r[0]);
			boolean withdrawn = r[2] != null;
			if (!guest && !withdrawn) {
				throw new BusinessException(GuestErrorCode.GUEST_IS_MEMBER);
			}
		}
	}

	private long upsertGuest(VerifiedIdentity id) {
		Timestamp expires = Timestamp.from(clock.instant().plus(Duration.ofDays(policy.number(AuthPolicy.GUEST_RETAIN_DAYS_AFTER_RUN))));
		List<Long> existing = jdbc.queryForList(
				"SELECT member_id FROM member WHERE ci_hash = ? AND member_type = 'GUEST' ORDER BY member_id FOR UPDATE",
				Long.class, id.ciHash());
		long memberId;
		if (existing.isEmpty()) {
			memberId = nextId("member_seq");
			Encrypted phone = cipher.encrypt(id.phone(), PiiField.MEMBER_PHONE, memberId);
			jdbc.update("""
					INSERT INTO member (member_id, email, ci_hash, name, phone_enc, phone_hash, enc_key_id, status,
					                    member_type, guest_expires_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 'GUEST', ?)""",
					memberId, "guest-" + memberId + "@guest.invalid", id.ciHash(), id.name(), phone.cipherText(),
					pseudonymizer.phone(id.phone()), phone.keyId(), expires);
		} else {
			// 같은 CI의 게스트 행을 다시 쓴다. 지워졌던 행이면 이름·연락처를 새로 채우고 되살린다 (이력·연결 계정 수를 지킨다)
			memberId = existing.get(0);
			Encrypted phone = cipher.encrypt(id.phone(), PiiField.MEMBER_PHONE, memberId);
			jdbc.update("""
					UPDATE member SET name = ?, phone_enc = ?, phone_hash = ?, enc_key_id = ?, withdrawn_at = NULL,
					       guest_expires_at = CASE WHEN guest_expires_at IS NULL OR guest_expires_at < ? THEN ? ELSE guest_expires_at END,
					       updated_at = SYSTIMESTAMP
					 WHERE member_id = ?""",
					id.name(), phone.cipherText(), pseudonymizer.phone(id.phone()), phone.keyId(), expires, expires, memberId);
		}
		jdbc.update("""
				INSERT INTO identity_verification (verification_id, member_id, ci_hash, provider, result, verified_at)
				VALUES (?, ?, ?, ?, 'SUCCESS', ?)""",
				nextId("identity_verification_seq"), memberId, id.ciHash(), id.provider().name(),
				Timestamp.from(id.verifiedAt()));
		return memberId;
	}

	// ------------------------------------------------------------ 예약 조회 (로그인 없이)

	/** 비회원 예약 조회 결과. 이름·휴대폰은 담지 않는다 */
	public record GuestReservation(long reservationId, String status, long runId, String fromStationId, String fromStation,
			String toStationId, String toStation, int seatCount, long totalAmount, Instant payDeadlineAt,
			Instant confirmedAt, Instant departureAt) {
	}

	/**
	 * 예약 번호 + 휴대폰으로 예약 한 건. 예약 번호가 순번이라 하나씩 넣어 보는 공격을 IP당 시간당 한도로 막는다.
	 * 번호가 없거나, 휴대폰이 다르거나, 게스트 예약이 아니면 모두 같은 404 (존재 여부를 알려 주지 않는다).
	 */
	public GuestReservation lookup(Long reservationId, String phone, String ipHash) {
		if (!enabled()) {
			throw new BusinessException(GuestErrorCode.GUEST_DISABLED);
		}
		long n = limiter.hit(AuthLimiter.guestLookupRateKey(ipHash), Duration.ofHours(1));
		if (n > policy.number(AuthPolicy.GUEST_LOOKUP_RATE_PER_HOUR)) {
			throw new BusinessException(CommonErrorCode.RATE_LIMITED);
		}
		String phoneHash;
		try {
			phoneHash = phone == null || reservationId == null ? null : pseudonymizer.phone(phone);
		} catch (IllegalArgumentException e) {
			phoneHash = null; // 휴대폰 형식이 아니어도 "맞지 않음"과 같은 응답
		}
		if (phoneHash == null) {
			throw new BusinessException(GuestErrorCode.GUEST_LOOKUP_NOT_FOUND);
		}
		List<GuestReservation> rows = jdbc.query("""
				SELECT r.reservation_id, r.status, r.run_id, r.from_station_id, fs.station_name, r.to_station_id, ts.station_name,
				       r.seat_count, r.total_amount, r.pay_deadline_at, r.confirmed_at,
				       (SELECT MIN(d.departure_time) FROM train_stop d WHERE d.run_id = r.run_id AND d.station_id = r.from_station_id)
				  FROM reservation r
				  JOIN member m ON m.member_id = r.member_id
				  LEFT JOIN station fs ON fs.station_id = r.from_station_id
				  LEFT JOIN station ts ON ts.station_id = r.to_station_id
				 WHERE r.reservation_id = ? AND m.member_type = 'GUEST' AND m.withdrawn_at IS NULL AND m.phone_hash = ?""",
				(rs, i) -> new GuestReservation(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
						rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getLong(9),
						instant(rs.getTimestamp(10)), instant(rs.getTimestamp(11)), instant(rs.getTimestamp(12))),
				reservationId, phoneHash);
		if (rows.isEmpty()) {
			throw new BusinessException(GuestErrorCode.GUEST_LOOKUP_NOT_FOUND);
		}
		return rows.get(0);
	}

	private static Instant instant(Timestamp t) {
		return t == null ? null : t.toInstant();
	}

	// ------------------------------------------------------------ 삭제

	/**
	 * 기한이 지난 게스트의 이름·연락처를 지운다 (탈퇴와 같은 방식: 이름은 표시용 값으로, 연락처는 NULL, 세션은 폐기).
	 * 기한 = 마지막 예약의 출발일 + guest.retain_days_after_run. 명절 표는 몇 주 전에 사므로 시간이 아니라 운행일 기준이다.
	 * 아직 진행 중인 예약(결제 전·결제 중, 출발 전 확정)이나 기한이 안 지난 예약이 있으면 지우지 않는다.
	 * CI 해시는 남긴다 (같은 CI로 다시 들어오면 이 행을 되살리고, 위험 점수·매수 제한을 세탁하지 못하게).
	 *
	 * @return 지운 게스트 수
	 */
	@Scheduled(cron = "${jejari.guest.purge-cron:0 30 4 * * *}", zone = "Asia/Seoul")
	public int purgeExpired() {
		long days = policy.number(AuthPolicy.GUEST_RETAIN_DAYS_AFTER_RUN);
		Timestamp now = Timestamp.from(clock.instant());
		List<Long> due = jdbc.queryForList("""
				SELECT m.member_id FROM member m
				 WHERE m.member_type = 'GUEST' AND m.withdrawn_at IS NULL AND m.guest_expires_at < ?
				   AND NOT EXISTS (
				       SELECT 1 FROM reservation r
				         LEFT JOIN train_stop d ON d.run_id = r.run_id AND d.station_id = r.from_station_id
				        WHERE r.member_id = m.member_id
				          AND (r.status IN ('HOLD', 'PAYMENT_PENDING')
				               OR (r.status IN ('CONFIRMED', 'CANCELED') AND (d.departure_time IS NULL
				                   OR d.departure_time + NUMTODSINTERVAL(?, 'DAY') > ?))))""",
				Long.class, now, days, now);
		for (long memberId : due) {
			tx.executeWithoutResult(st -> {
				jdbc.update("""
						UPDATE waitlist SET status = 'CANCELED', updated_at = SYSTIMESTAMP
						 WHERE member_id = ? AND status = 'WAITING'""", memberId);
				jdbc.update("""
						UPDATE member SET name = ?, phone_enc = NULL, phone_hash = NULL, enc_key_id = NULL, withdrawn_at = ?,
						       updated_at = SYSTIMESTAMP
						 WHERE member_id = ? AND member_type = 'GUEST' AND withdrawn_at IS NULL""",
						GUEST_NAME_AFTER_PURGE, now, memberId);
				jdbc.update("""
						UPDATE auth_session SET revoked_at = SYSTIMESTAMP, revoke_reason = 'LOGOUT', updated_at = SYSTIMESTAMP
						 WHERE member_id = ? AND revoked_at IS NULL""", memberId);
			});
		}
		if (!due.isEmpty()) {
			log.info("기한이 지난 비회원 {}명의 이름·연락처를 지웠습니다", due.size());
		}
		return due.size();
	}

	private long nextId(String sequence) {
		Long id = jdbc.queryForObject("SELECT " + sequence + ".NEXTVAL FROM dual", Long.class);
		if (id == null) {
			throw new IllegalStateException("시퀀스 값 없음: " + sequence);
		}
		return id;
	}
}
