package com.jejari.waitlist;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jejari.common.config.AppPolicy;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.queue.QueueErrorCode;
import com.jejari.queue.domain.RiskPenaltyService;
import com.jejari.queue.port.MemberIdentityPort;
import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;
import com.jejari.seat.PurchaseLimits;
import com.jejari.seat.SeatMapService;
import com.jejari.train.RunInfo;
import com.jejari.train.TrainRunReader;

/**
 * WL-01 취소표 대기 신청 · WL-02 목록 조회·대기 취소 (기능설계서 4.7, concurrency.md §9.3).
 *
 * <p>대기 순서는 <b>신청 시각이 아니라 {@code priority_at} = 신청 시각 + 위험 패널티</b>다.
 * 대기열에서 위험 점수로 뒤로 밀린 회원이 취소표에서 앞자리를 가져가면 후순위가 우회되기 때문이다.
 * 패널티는 <b>신청할 때 한 번</b> 계산해 저장하고, 그 뒤 점수가 바뀌어도 다시 계산하지 않는다 (§9.3-3).
 *
 * <p>위험 점수는 대기열(QUE-01)과 <b>같은 규칙</b>이라 김성원 씨의 {@link RiskPenaltyService}를 그대로 쓴다.
 * 다만 1점당 미루는 시간은 다르다 — 대기열은 몇 분 안에 줄이 빠지지만 취소표는 몇 시간~며칠이라
 * 같은 단위(1점 = 1초)면 80점이 80초라 순서가 사실상 안 바뀐다. {@code waitlist.penalty_ms_per_point}
 * (180000, 1점 = 3분)를 따로 쓴다 (§9.3-2).
 *
 * <p>제안(WL-03)은 이 클래스가 하지 않는다. 빈 좌석 이벤트를 받는 {@code seat-release} 소비자가 한다 (§9.1).
 */
@Service
public class WaitlistService {

	/** 활성 대기 = 아직 결과가 나지 않은 것. 건수 한도와 중복 검사에 쓴다 */
	private static final String ACTIVE = "('WAITING', 'OFFERED')";

	private final JdbcTemplate jdbc;
	private final AppPolicy policy;
	private final TrainRunReader runs;
	private final SeatMapService seatMap;
	private final PurchaseLimits limits;
	private final RiskPenaltyService risk;
	private final ObjectProvider<MemberIdentityPort> members;
	private final Clock clock;

	public WaitlistService(JdbcTemplate jdbc, AppPolicy policy, TrainRunReader runs, SeatMapService seatMap,
			PurchaseLimits limits, RiskPenaltyService queueRiskPenaltyService,
			ObjectProvider<MemberIdentityPort> members, Clock clock) {
		this.jdbc = jdbc;
		this.policy = policy;
		this.runs = runs;
		this.seatMap = seatMap;
		this.limits = limits;
		this.risk = queueRiskPenaltyService;
		this.members = members;
		this.clock = clock;
	}

	/** 신청 입력 */
	public record ApplyCommand(long runId, String fromStationId, String toStationId, String seatGrade, int seatCount) {
	}

	/** 한 건의 대기 */
	public record Entry(long waitlistId, long runId, String fromStationId, String toStationId, String seatGrade,
			int seatCount, LocalDateTime requestedAt, LocalDateTime priorityAt, long penaltyMs, String status,
			LocalDateTime offerDeadlineAt, Long offeredReservationId, Integer rank) {
	}

	// ────────────────────────────── WL-01 신청 ──────────────────────────────

	@Transactional
	public Entry apply(long memberId, ApplyCommand cmd) {
		RunInfo run = validate(cmd);
		LocalDateTime now = LocalDateTime.now(clock);

		// 0. 신청 자격 (§9.3-0). RESTRICTED는 최대 패널티로 미뤄도 결국 가져가므로 신청 자체를 막는다
		MemberIdentity me = identity(memberId);
		if ("RESTRICTED".equals(me.status())) {
			throw new BusinessException(WaitlistErrorCode.WAITLIST_RESTRICTED);
		}

		// 1회 매수 한도. 여기서 보고 제안 때는 다시 보지 않는다 (§9.1-5)
		Integer perReservation = limits.read(run, now).perReservation();
		if (perReservation != null && cmd.seatCount() > perReservation) {
			throw new BusinessException(com.jejari.seat.SeatErrorCode.LIMIT_PER_RESERVATION);
		}

		// 그 등급이 매진이어야 대기다. 남았으면 바로 사면 된다
		if (remaining(run, cmd.seatGrade()) > 0) {
			throw new BusinessException(WaitlistErrorCode.WAITLIST_NOT_SOLD_OUT);
		}

		// 같은 운행 중복·전체 건수. UNIQUE 제약이 없어 트랜잭션 안에서 센다
		// (동시에 두 번 눌러도 한 건만 남게 하려면 아래 INSERT 뒤 다시 세는 방법도 있지만,
		//  신청은 경쟁이 심한 경로가 아니라 여기서는 읽고 쓰는 것으로 충분하다)
		if (count(memberId, cmd.runId()) >= policy.getLong(AppPolicy.WAITLIST_MAX_PER_RUN)) {
			throw new BusinessException(WaitlistErrorCode.WAITLIST_DUPLICATED);
		}
		if (count(memberId, null) >= policy.getLong(AppPolicy.WAITLIST_MAX_ACTIVE)) {
			throw new BusinessException(WaitlistErrorCode.WAITLIST_LIMIT);
		}

		// §9.3 위험 패널티. 점수는 대기열과 같은 규칙, 1점당 미루는 시간만 취소표 값을 쓴다
		double score = risk.score(me);
		long penaltyMs = Math.round(score * policy.getLong(AppPolicy.WAITLIST_PENALTY_MS_PER_POINT));
		LocalDateTime priorityAt = now.plusNanos(penaltyMs * 1_000_000L);

		long id = jdbc.queryForObject("SELECT waitlist_seq.NEXTVAL FROM dual", Long.class);
		jdbc.update("""
				INSERT INTO waitlist (waitlist_id, member_id, run_id, from_station_id, to_station_id, seat_grade,
				       seat_count, requested_at, priority_at, penalty_ms, status)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'WAITING')""",
				id, memberId, cmd.runId(), cmd.fromStationId(), cmd.toStationId(), cmd.seatGrade(),
				cmd.seatCount(), now, priorityAt, penaltyMs);

		return new Entry(id, cmd.runId(), cmd.fromStationId(), cmd.toStationId(), cmd.seatGrade(), cmd.seatCount(),
				now, priorityAt, penaltyMs, "WAITING", null, null, rank(cmd.runId(), priorityAt, id));
	}

	// ───────────────────────── WL-02 조회·대기 취소 ─────────────────────────

	/** 내 대기 목록. 최근 것이 위로 */
	public List<Entry> myList(long memberId) {
		return jdbc.query("""
				SELECT waitlist_id, run_id, from_station_id, to_station_id, seat_grade, seat_count,
				       requested_at, priority_at, penalty_ms, status, offer_deadline_at, offered_reservation_id
				  FROM waitlist WHERE member_id = ? ORDER BY waitlist_id DESC""",
				(rs, i) -> {
					long resv = rs.getLong("offered_reservation_id");
					String status = rs.getString("status");
					long runId = rs.getLong("run_id");
					LocalDateTime priorityAt = rs.getObject("priority_at", LocalDateTime.class);
					long id = rs.getLong("waitlist_id");
					return new Entry(id, runId, rs.getString("from_station_id"), rs.getString("to_station_id"),
							rs.getString("seat_grade"), rs.getInt("seat_count"),
							rs.getObject("requested_at", LocalDateTime.class), priorityAt, rs.getLong("penalty_ms"),
							status, rs.getObject("offer_deadline_at", LocalDateTime.class),
							rs.wasNull() ? null : resv,
							"WAITING".equals(status) ? rank(runId, priorityAt, id) : null);
				}, memberId);
	}

	/**
	 * 대기 취소. 기다리는 중({@code WAITING})일 때만 된다. 이미 제안받았으면({@code OFFERED})
	 * 그건 예약을 취소하는 것(RSV-05)이지 대기를 취소하는 것이 아니다.
	 *
	 * <p>같은 요청을 다시 보내면 그대로 성공을 돌려준다 (이미 CANCELED).
	 */
	@Transactional
	public Entry cancel(long memberId, long waitlistId) {
		int changed = jdbc.update("""
				UPDATE waitlist SET status = 'CANCELED', updated_at = SYSTIMESTAMP
				 WHERE waitlist_id = ? AND member_id = ? AND status = 'WAITING'""", waitlistId, memberId);
		Optional<Entry> after = find(memberId, waitlistId);
		if (after.isEmpty()) {
			// 남의 대기는 없는 대기와 같게 404 (존재를 알려 주지 않는다)
			throw new BusinessException(WaitlistErrorCode.WAITLIST_NOT_FOUND);
		}
		Entry e = after.get();
		if (changed == 0 && !"CANCELED".equals(e.status())) {
			throw new BusinessException(WaitlistErrorCode.WAITLIST_NOT_WAITING);
		}
		return e;
	}

	// ────────────────────────────── 도우미 ──────────────────────────────

	private RunInfo validate(ApplyCommand cmd) {
		if (cmd.seatCount() < 1 || cmd.seatGrade() == null
				|| !List.of("GENERAL", "FIRST").contains(cmd.seatGrade())) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "등급은 GENERAL·FIRST, 매수는 1 이상이어야 합니다.");
		}
		RunInfo run = runs.find(cmd.runId()).filter(r -> r.onSale(LocalDateTime.now(clock)))
				.orElseThrow(() -> new BusinessException(QueueErrorCode.RUN_NOT_ON_SALE));
		if (!run.validSegment(cmd.fromStationId(), cmd.toStationId())) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "승차역·하차역이 이 열차의 정차역 순서와 맞지 않습니다.");
		}
		return run;
	}

	private MemberIdentity identity(long memberId) {
		return members.orderedStream().findFirst()
				.flatMap(port -> port.find(memberId))
				.orElseThrow(() -> new BusinessException(WaitlistErrorCode.WAITLIST_UNAVAILABLE));
	}

	private int remaining(RunInfo run, String grade) {
		Map<String, Integer> byGrade = seatMap.remainingByGrade(run.runId(), run.trainId());
		Integer n = byGrade.get(grade);
		return n == null ? 0 : n;
	}

	/** 활성 대기 건수. runId가 null이면 전체 */
	private long count(long memberId, Long runId) {
		String sql = "SELECT COUNT(*) FROM waitlist WHERE member_id = ? AND status IN " + ACTIVE
				+ (runId == null ? "" : " AND run_id = ?");
		Long n = runId == null
				? jdbc.queryForObject(sql, Long.class, memberId)
				: jdbc.queryForObject(sql, Long.class, memberId, runId);
		return n == null ? 0 : n;
	}

	/** 내 앞에 몇 명 있나 (1부터). 제안은 priority_at, waitlist_id 순서다 (§9.1-4) */
	private int rank(long runId, LocalDateTime priorityAt, long waitlistId) {
		Long n = jdbc.queryForObject("""
				SELECT COUNT(*) + 1 FROM waitlist
				 WHERE run_id = ? AND status = 'WAITING'
				   AND (priority_at < ? OR (priority_at = ? AND waitlist_id < ?))""",
				Long.class, runId, priorityAt, priorityAt, waitlistId);
		return n == null ? 1 : n.intValue();
	}

	private Optional<Entry> find(long memberId, long waitlistId) {
		return myList(memberId).stream().filter(e -> e.waitlistId() == waitlistId).findFirst();
	}
}
