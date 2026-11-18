package com.jejari.waitlist;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.config.AppPolicy;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.queue.QueueErrorCode;
import com.jejari.queue.domain.RiskPenaltyService;
import com.jejari.queue.port.MemberIdentityPort;
import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;
import com.jejari.seat.PurchaseLimits;
import com.jejari.seat.SeatErrorCode;
import com.jejari.seat.SeatMapService;
import com.jejari.train.RunInfo;
import com.jejari.train.TrainRunReader;

/**
 * WL-01 취소표 대기 신청, WL-02 내 대기 목록·대기 취소. 제안(WL-03)은 seat-release 소비자가 한다 (concurrency.md §9).
 *
 * <pre>
 * 신청: 입력 검사 → 판매 중 운행·구간 → 1회 매수 한도 → 이용 제한(RESTRICTED) 거절 → 그 등급 매진 확인
 *       → 위험 점수로 패널티 계산 (트랜잭션 밖, Redis)
 *       → [트랜잭션] 회원 행 잠금 → 같은 운행·전체 활성 대기 수 확인 → INSERT (priority_at = 신청 시각 + 패널티)
 * </pre>
 *
 * <ul>
 *   <li><b>순서 = priority_at</b> (§9.3): 위험 점수 × waitlist.penalty_ms_per_point(1점 = 3분)만큼 뒤로. 신청 때 한 번만 계산한다.
 *       대기열에서 뒤로 밀린 사람이 취소표로 우회하지 못하게 하려는 것이다. 점수 때문에 신청을 막지는 않는다.</li>
 *   <li><b>RESTRICTED는 거절</b>: 관리자가 제한한 회원은 최대 패널티(5시간)로 미뤄도 결국 표를 가져가므로 신청 자체를 막는다.</li>
 *   <li><b>회원 행 잠금</b>: 따닥 두 번 눌러 두 요청이 동시에 "0건"을 보고 둘 다 넣는 것을 막는다 (같은 회원 신청을 줄 세움).</li>
 *   <li><b>점수·패널티는 응답에 넣지 않는다</b>: 순서가 왜 밀렸는지 알려 주면 봇이 점수 기준을 역산할 수 있다.</li>
 *   <li>활성 대기 = WAITING·OFFERED. 끝난 건(ACCEPTED·EXPIRED·CANCELED·SKIPPED)은 한도에 세지 않는다.</li>
 * </ul>
 */
@Service
public class WaitlistService {

	static final Set<String> GRADES = Set.of("GENERAL", "FIRST");
	static final int MAX_LIST = 50;

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final TrainRunReader runs;
	private final PurchaseLimits limits;
	private final SeatMapService seatMap;
	private final MemberIdentityPort members;
	private final RiskPenaltyService risk;
	private final AppPolicy policy;
	private final Clock clock;

	public WaitlistService(JdbcTemplate jdbc, TransactionTemplate tx, TrainRunReader runs, PurchaseLimits limits,
			SeatMapService seatMap, MemberIdentityPort members, RiskPenaltyService risk, AppPolicy policy,
			Clock clock) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.runs = runs;
		this.limits = limits;
		this.seatMap = seatMap;
		this.members = members;
		this.risk = risk;
		this.policy = policy;
		this.clock = clock;
	}

	public record ApplyCommand(Long runId, String fromStationId, String toStationId, String seatGrade,
			Integer seatCount) {
	}

	public record Applied(long waitlistId, String status, Instant requestedAt) {
	}

	// ------------------------------------------------------------ WL-01 신청

	public Applied apply(long memberId, ApplyCommand cmd) {
		if (cmd == null || cmd.runId() == null || cmd.fromStationId() == null || cmd.toStationId() == null
				|| cmd.seatGrade() == null || !GRADES.contains(cmd.seatGrade()) || cmd.seatCount() == null
				|| cmd.seatCount() < 1 || cmd.seatCount() > 99) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		LocalDateTime now = LocalDateTime.now(clock);
		RunInfo run = runs.find(cmd.runId()).filter(r -> r.onSale(now))
				.orElseThrow(() -> new BusinessException(QueueErrorCode.RUN_NOT_ON_SALE));
		if (!run.validSegment(cmd.fromStationId(), cmd.toStationId())) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "승차역·하차역이 이 열차의 정차역 순서와 맞지 않습니다.");
		}
		Integer perReservation = limits.read(run, now).perReservation();
		if (perReservation != null && cmd.seatCount() > perReservation) {
			throw new BusinessException(SeatErrorCode.LIMIT_PER_RESERVATION);
		}

		MemberIdentity me = members.find(memberId)
				.orElseThrow(() -> new BusinessException(WaitlistErrorCode.WAITLIST_RESTRICTED));
		if (!"ACTIVE".equals(me.status())) {
			throw new BusinessException(WaitlistErrorCode.WAITLIST_RESTRICTED); // RESTRICTED (BANNED는 로그인에서 막힘)
		}

		Map<String, Integer> remaining = seatMap.remainingByGrade(run.runId(), run.trainId());
		if (!remaining.containsKey(cmd.seatGrade())) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "이 열차에 없는 좌석 등급입니다.");
		}
		if (remaining.get(cmd.seatGrade()) >= cmd.seatCount()) {
			throw new BusinessException(WaitlistErrorCode.WAITLIST_NOT_SOLD_OUT);
		}

		// 위험 점수는 대기열과 같은 규칙(CI·결제 수단·기기 중 최댓값, 캐시 없으면 Oracle, 모르면 100)
		long penaltyMs = Math.round(risk.score(me) * policy.getLong(AppPolicy.WAITLIST_PENALTY_MS_PER_POINT));
		Instant requestedAt = clock.instant();
		Instant priorityAt = requestedAt.plusMillis(penaltyMs);
		long maxPerRun = policy.getLong(AppPolicy.WAITLIST_MAX_PER_RUN);
		long maxActive = policy.getLong(AppPolicy.WAITLIST_MAX_ACTIVE);

		long id = tx.execute(st -> {
			jdbc.queryForObject("SELECT member_id FROM member WHERE member_id = ? FOR UPDATE", Long.class, memberId);
			Map<String, Object> counts = jdbc.queryForMap("""
					SELECT COUNT(*) AS active, NVL(SUM(CASE WHEN run_id = ? THEN 1 ELSE 0 END), 0) AS same_run
					  FROM waitlist WHERE member_id = ? AND status IN ('WAITING', 'OFFERED')""", run.runId(), memberId);
			if (((Number) counts.get("SAME_RUN")).longValue() >= maxPerRun) {
				throw new BusinessException(WaitlistErrorCode.WAITLIST_DUPLICATED);
			}
			if (((Number) counts.get("ACTIVE")).longValue() >= maxActive) {
				throw new BusinessException(WaitlistErrorCode.WAITLIST_LIMIT);
			}
			long newId = jdbc.queryForObject("SELECT waitlist_seq.NEXTVAL FROM dual", Long.class);
			jdbc.update("""
					INSERT INTO waitlist (waitlist_id, member_id, run_id, from_station_id, to_station_id, seat_grade,
					  seat_count, requested_at, priority_at, penalty_ms, status)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'WAITING')""",
					newId, memberId, run.runId(), cmd.fromStationId(), cmd.toStationId(), cmd.seatGrade(),
					cmd.seatCount(), Timestamp.from(requestedAt), Timestamp.from(priorityAt), penaltyMs);
			return newId;
		});
		return new Applied(id, "WAITING", requestedAt);
	}

	// ------------------------------------------------------------ WL-02 목록

	/**
	 * @param ahead 기다리는 중(WAITING)일 때 같은 운행에서 내 앞에 있는 대기 수 (구간·등급이 달라 먼저 받지 못할 수도 있음)
	 */
	public record Item(long waitlistId, String status, long runId, LocalDate runDate, String trainNo,
			String fromStationId, String fromName, String toStationId, String toName, String seatGrade, int seatCount,
			Instant requestedAt, Long ahead, Instant offerDeadlineAt, Long offeredReservationId) {
	}

	public List<Item> list(long memberId) {
		return jdbc.query("""
				SELECT w.waitlist_id, w.status, w.run_id, tr.run_date, t.train_no,
				       w.from_station_id, fs.station_name, w.to_station_id, tst.station_name,
				       w.seat_grade, w.seat_count, w.requested_at,
				       CASE WHEN w.status = 'WAITING' THEN (
				         SELECT COUNT(*) FROM waitlist o
				          WHERE o.run_id = w.run_id AND o.status = 'WAITING'
				            AND (o.priority_at < w.priority_at
				                 OR (o.priority_at = w.priority_at AND o.waitlist_id < w.waitlist_id))) END,
				       w.offer_deadline_at, w.offered_reservation_id
				  FROM waitlist w
				  JOIN train_run tr ON tr.run_id = w.run_id
				  JOIN train t ON t.train_id = tr.train_id
				  JOIN station fs ON fs.station_id = w.from_station_id
				  JOIN station tst ON tst.station_id = w.to_station_id
				 WHERE w.member_id = ?
				 ORDER BY w.waitlist_id DESC
				 FETCH FIRST %d ROWS ONLY""".formatted(MAX_LIST),
				(rs, i) -> {
					long ahead = rs.getLong(13);
					boolean aheadNull = rs.wasNull();
					long offered = rs.getLong(15);
					boolean offeredNull = rs.wasNull();
					boolean isOffered = "OFFERED".equals(rs.getString(2));
					Timestamp deadline = rs.getTimestamp(14);
					return new Item(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getDate(4).toLocalDate(),
							rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
							rs.getString(10), rs.getInt(11), rs.getTimestamp(12).toInstant(),
							aheadNull ? null : ahead,
							isOffered && deadline != null ? deadline.toInstant() : null,
							isOffered && !offeredNull ? offered : null);
				}, memberId);
	}

	// ------------------------------------------------------------ WL-02 대기 취소

	/** WAITING만 CANCELED로. 남의 대기·없는 대기는 같은 404 */
	public void cancel(long memberId, long waitlistId) {
		int n = jdbc.update("""
				UPDATE waitlist SET status = 'CANCELED', updated_at = SYSTIMESTAMP
				 WHERE waitlist_id = ? AND member_id = ? AND status = 'WAITING'""", waitlistId, memberId);
		if (n == 1) {
			return;
		}
		List<String> status = jdbc.queryForList("SELECT status FROM waitlist WHERE waitlist_id = ? AND member_id = ?",
				String.class, waitlistId, memberId);
		if (status.isEmpty()) {
			throw new BusinessException(WaitlistErrorCode.WAITLIST_NOT_FOUND);
		}
		if ("CANCELED".equals(status.get(0))) {
			return; // 이미 취소됨: 다시 눌러도 같은 결과 (멱등)
		}
		throw new BusinessException(WaitlistErrorCode.WAITLIST_NOT_WAITING);
	}
}
