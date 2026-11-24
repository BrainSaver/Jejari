package com.jejari.group;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.config.AppPolicy;
import com.jejari.group.GroupAllocationTx.Line;
import com.jejari.group.GroupAllocationTx.NotRequested;
import com.jejari.group.GroupAllocationTx.Plan;
import com.jejari.group.GroupAllocationTx.SeatChanged;
import com.jejari.group.GroupSeatPicker.Seat;
import com.jejari.payment.PaymentRiskGuard;
import com.jejari.queue.redis.QueueKeys;
import com.jejari.seat.SeatKeys;
import com.jejari.seat.SeatMapService;
import com.jejari.train.FareReader;
import com.jejari.train.RunInfo;
import com.jejari.train.TrainRunReader;

/**
 * 단체 승인 + 좌석 배정 (concurrency.md §18.3, §18.3a). 자동 승인·관리자 승인·재시도 스캐너(§18.3b)가 같은 절차를 씁니다.
 *
 * <pre>
 * 좌석 고르기(락 없음): 할당석(BLOCKED, 키 = GROUP) → 모자라면 일반 좌석(행 없음, 키 없음)
 *   일반 좌석은 운행이 ON_SALE이고 대기열·입장자가 0일 때만 (§18.3a-0). 아니면 승인 전체를 미룬다(DEFERRED)
 * → group_claim.lua (할당석 + 일반 좌석 한 번에, 전부 아니면 전무)
 * → DB 트랜잭션(GroupAllocationTx) → 커밋
 * 실패하면 group_return.lua로 할당석 키는 GROUP으로, 일반 좌석 키는 지운다 (§18.3-5). 최대 3회 다시 고른다
 * </pre>
 *
 * <p>입장 토큰·개인 매수 카운터(§10)는 쓰지 않습니다. 단체 신청 자체(대표자 전화 인증·위험 점수·번호 중복 경고)가 별도 관문입니다.
 */
@Service
public class GroupAllocator {

	private static final Logger log = LoggerFactory.getLogger(GroupAllocator.class);
	static final int MAX_ATTEMPTS = 3;

	/** 승인 시도 결과 */
	public enum Outcome {
		/** 예약(source = GROUP, HOLD) 생성. 대표자가 결제 기한 안에 결제 */
		APPROVED,
		/** 할당석이 모자라고 지금은 일반 좌석을 가져갈 수 없음(오픈 직후 대기열 있음 등). REQUESTED로 두고 스캐너가 다시 (§18.3b) */
		DEFERRED,
		/** 일반 좌석까지 모자람. REQUESTED로 두고 스캐너가 다시 (취소표가 생기면 될 수 있음) */
		SEAT_SHORT,
		/** 대표자 위험 점수가 결제 차단 기준 이상. 자동 승인하지 않고 관리자 판단을 기다림 */
		REVIEW,
		/** 이미 승인·반려·철회됨 (또는 다른 앱이 지금 처리 중) */
		NOT_REQUESTED
	}

	public record Result(Outcome outcome, Long reservationId, LocalDateTime payDeadlineAt, long totalAmount) {

		static Result of(Outcome o) {
			return new Result(o, null, null, 0);
		}
	}

	/** 승인할 때 읽는 신청 정보 */
	record Booking(long groupId, long repMemberId, String ciHash, long runId, String fromStationId, String toStationId,
			int passengerCount, String status, boolean discount) {
	}

	record Passenger(int passengerNo, String passengerType) {
	}

	private static final RedisScript<Long> CLAIM = script("group_claim");
	private static final RedisScript<Long> RETURN = script("group_return");

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final StringRedisTemplate redis;
	private final GroupAllocationTx allocationTx;
	private final TrainRunReader runs;
	private final FareReader fares;
	private final SeatMapService seatMap;
	private final AppPolicy policy;
	private final ObjectProvider<PaymentRiskGuard> riskGuard;
	private final Clock clock;

	public GroupAllocator(JdbcTemplate jdbc, TransactionTemplate tx, StringRedisTemplate redis,
			GroupAllocationTx allocationTx, TrainRunReader runs, FareReader fares, SeatMapService seatMap,
			AppPolicy policy, ObjectProvider<PaymentRiskGuard> riskGuard, Clock clock) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.redis = redis;
		this.allocationTx = allocationTx;
		this.runs = runs;
		this.fares = fares;
		this.seatMap = seatMap;
		this.policy = policy;
		this.riskGuard = riskGuard;
		this.clock = clock;
	}

	/**
	 * 승인을 시도한다.
	 *
	 * @param adminId    관리자 승인이면 admin_id (위험 점수 확인을 건너뜀), 자동 승인이면 null
	 * @param quotaOnly  true면 일반 좌석은 쓰지 않는다 (§18.3b-2: 앞 신청이 미뤄진 운행의 뒤 신청)
	 * @param skipLocked 스캐너용. 다른 앱이 처리 중인 신청은 기다리지 않고 NOT_REQUESTED
	 */
	public Result approve(long groupId, Long adminId, boolean quotaOnly, boolean skipLocked) {
		Optional<Booking> found = booking(groupId);
		if (found.isEmpty() || !"REQUESTED".equals(found.get().status())) {
			return Result.of(Outcome.NOT_REQUESTED);
		}
		Booking b = found.get();
		if (adminId == null && risky(b.repMemberId())) {
			return Result.of(Outcome.REVIEW);
		}
		LocalDateTime now = LocalDateTime.now(clock);
		RunInfo run = runs.find(b.runId()).orElse(null);
		if (run == null || run.departureAt() == null || !run.departureAt().isAfter(now)) {
			return Result.of(Outcome.SEAT_SHORT);
		}
		List<Passenger> pax = passengers(groupId);
		int n = pax.size();
		if (n != b.passengerCount()) {
			throw new IllegalStateException("명단 수와 신청 인원이 다름 group=" + groupId);
		}
		boolean generalAllowed = !quotaOnly && generalAllowed(run, now);
		LocalDateTime deadline = now.plusDays(policy.getLong(AppPolicy.GROUP_PAY_DEADLINE_DAYS));
		long keyExpiry = epochMs(deadline) + policy.getLong(AppPolicy.HOLD_RELEASE_GRACE_SEC) * 1000L;
		long releaseAt = epochMs(run.departureAt().minusDays(policy.getLong(AppPolicy.GROUP_QUOTA_RELEASE_DAYS)));
		long fare = fare(run, b);
		double discountRate = b.discount() ? policy.getDouble(AppPolicy.GROUP_DISCOUNT_RATE) : 0;

		Set<Long> lost = new HashSet<>(); // 이번 요청에서 진 좌석은 다시 고르지 않는다
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			// 1. 할당석
			List<Seat> quota = GroupSeatPicker.pickBlock(quotaCandidates(run, lost), n);
			int m = n - quota.size();
			List<Seat> general = List.of();
			if (m > 0) {
				if (!generalAllowed) {
					return Result.of(Outcome.DEFERRED);
				}
				// 2. 일반 좌석 (§18.3a-1)
				general = GroupSeatPicker.pickNear(generalCandidates(run, lost), m, quota);
				if (general.size() < m) {
					return Result.of(Outcome.SEAT_SHORT);
				}
			}
			List<Long> quotaIds = quota.stream().map(Seat::seatId).sorted().toList();
			List<Long> generalIds = general.stream().map(Seat::seatId).sorted().toList();
			List<String> keys = new ArrayList<>();
			quotaIds.forEach(id -> keys.add(SeatKeys.hold(run.runId(), id, GroupAllocationTx.SEGMENT_NO)));
			generalIds.forEach(id -> keys.add(SeatKeys.hold(run.runId(), id, GroupAllocationTx.SEGMENT_NO)));

			// 3. Redis 한 번에
			long r = jdbc.queryForObject("SELECT reservation_seq.NEXTVAL FROM dual", Long.class);
			Long won = redis.execute(CLAIM, keys, Long.toString(r), Long.toString(keyExpiry),
					Integer.toString(quotaIds.size()));
			if (won == null || won != 1L) {
				lost.addAll(changedSince(run.runId(), quotaIds, generalIds));
				continue;
			}

			// 4. DB. 승객은 명단 순서대로 좌석(객차·좌석 번호 순)에 앉힌다
			List<Seat> seats = new ArrayList<>(quota);
			seats.addAll(general);
			seats.sort((x, y) -> x.carNo() != y.carNo() ? Integer.compare(x.carNo(), y.carNo())
					: Long.compare(x.seatId(), y.seatId()));
			List<Line> lines = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				Passenger p = pax.get(i);
				long amount = "ADULT".equals(p.passengerType()) ? discounted(fare, discountRate) : fare;
				lines.add(new Line(p.passengerNo(), seats.get(i).seatId(), p.passengerType(), amount));
			}
			Plan plan = new Plan(groupId, r, b.repMemberId(), b.ciHash(), run.runId(), run.trainId(),
					b.fromStationId(), b.toStationId(), quotaIds, generalIds, lines, now, deadline, adminId, skipLocked);
			try {
				tx.executeWithoutResult(st -> allocationTx.write(plan));
			} catch (NotRequested e) {
				giveBack(keys, r, releaseAt, quotaIds.size());
				return Result.of(Outcome.NOT_REQUESTED);
			} catch (SeatChanged | DuplicateKeyException e) {
				giveBack(keys, r, releaseAt, quotaIds.size());
				lost.addAll(changedSince(run.runId(), quotaIds, generalIds));
				continue;
			} catch (RuntimeException e) {
				giveBack(keys, r, releaseAt, quotaIds.size());
				throw e;
			}
			try {
				seatMap.evict(run.runId());
			} catch (RuntimeException e) {
				log.debug("seatmap 캐시 삭제 실패 (유지 시간 뒤 갱신됨)", e);
			}
			log.info("단체 {} 승인 → 예약 {} (할당석 {}, 일반 {}, {})", groupId, r, quotaIds.size(), generalIds.size(),
					adminId == null ? "자동" : "관리자 " + adminId);
			return new Result(Outcome.APPROVED, r, deadline, plan.totalAmount());
		}
		return Result.of(Outcome.SEAT_SHORT);
	}

	// ---------------------------------------------------------------- 조건

	/**
	 * §18.3a-0: 일반 좌석을 가져가도 되는가. 판매 중이고, 줄 선 사람(ZCARD queue)과 입장해 고르는 사람(ZCOUNT queue:active 지금~)이
	 * 둘 다 0일 때만. 오픈 순간 쌓인 단체 신청이 줄 선 사람보다 먼저 일반 좌석을 가져가지 않게.
	 */
	boolean generalAllowed(RunInfo run, LocalDateTime now) {
		if (!run.onSale(now)) {
			return false;
		}
		Long waiting = redis.opsForZSet().zCard(QueueKeys.queue(run.runId()));
		Long active = redis.opsForZSet().count(QueueKeys.active(run.runId()), clock.millis() + 1, Double.POSITIVE_INFINITY);
		return (waiting == null || waiting == 0) && (active == null || active == 0);
	}

	/** 자동 승인 전 매크로 신호: 결제 단계 차단과 같은 기준(위험 점수 ≥ risk.rule.case_threshold) */
	private boolean risky(long memberId) {
		PaymentRiskGuard g = riskGuard.getIfAvailable();
		if (g == null) {
			return false;
		}
		try {
			return g.check(memberId).blocked();
		} catch (RuntimeException e) {
			log.warn("단체 대표자 위험 점수 확인 실패 member={} → 통과", memberId, e);
			return false;
		}
	}

	// ---------------------------------------------------------------- 좌석 후보

	/** 할당석: BLOCKED 행이 있고 Redis 키가 GROUP인 좌석 */
	private List<Seat> quotaCandidates(RunInfo run, Set<Long> lost) {
		List<Seat> rows = jdbc.query("""
				SELECT i.seat_id, s.car_no FROM seat_inventory i JOIN seat s ON s.seat_id = i.seat_id
				 WHERE i.run_id = ? AND i.segment_no = ? AND i.status = 'BLOCKED'
				 ORDER BY s.car_no, i.seat_id""", (rs, i) -> new Seat(rs.getLong(1), rs.getInt(2)),
				run.runId(), GroupAllocationTx.SEGMENT_NO);
		return filterByKey(run.runId(), rows, lost, true);
	}

	/**
	 * 일반 좌석: 일반실 중 seat_inventory 행이 없고 Redis 키도 없는 좌석 (§18.3a-1).
	 * 만료된 HOLD 행이 남은 좌석은 건너뛴다 (단체 트랜잭션에서 남의 예약을 정리하지 않음). REOFFER:R 좌석은 키가 있어 빠진다 (G5c)
	 */
	private List<Seat> generalCandidates(RunInfo run, Set<Long> lost) {
		List<Seat> rows = jdbc.query("""
				SELECT s.seat_id, s.car_no FROM seat s
				 WHERE s.train_id = ? AND s.seat_grade = 'GENERAL'
				   AND NOT EXISTS (SELECT 1 FROM seat_inventory i
				                    WHERE i.run_id = ? AND i.seat_id = s.seat_id AND i.segment_no = ?)
				 ORDER BY s.car_no, s.seat_id""", (rs, i) -> new Seat(rs.getLong(1), rs.getInt(2)),
				run.trainId(), run.runId(), GroupAllocationTx.SEGMENT_NO);
		return filterByKey(run.runId(), rows, lost, false);
	}

	/** quota면 키 값이 GROUP인 좌석만, 아니면 키가 없는 좌석만 */
	private List<Seat> filterByKey(long runId, List<Seat> rows, Set<Long> lost, boolean quota) {
		List<Seat> in = rows.stream().filter(s -> !lost.contains(s.seatId())).toList();
		if (in.isEmpty()) {
			return in;
		}
		List<String> values = new ArrayList<>(in.size());
		for (int from = 0; from < in.size(); from += 500) {
			List<String> keys = in.subList(from, Math.min(in.size(), from + 500)).stream()
					.map(s -> SeatKeys.hold(runId, s.seatId(), GroupAllocationTx.SEGMENT_NO)).toList();
			List<String> got = redis.opsForValue().multiGet(keys);
			if (got == null) {
				got = new ArrayList<>();
				for (int i = 0; i < keys.size(); i++) {
					got.add(null);
				}
			}
			values.addAll(got);
		}
		List<Seat> out = new ArrayList<>();
		for (int i = 0; i < in.size(); i++) {
			String v = values.get(i);
			if (quota ? SeatKeys.GROUP.equals(v) : v == null) {
				out.add(in.get(i));
			}
		}
		return out;
	}

	/** Redis·DB에서 진 좌석: 지금 키 값이 기대와 다른 좌석. 다음 시도에서 뺀다 */
	private List<Long> changedSince(long runId, List<Long> quotaIds, List<Long> generalIds) {
		List<Long> all = new ArrayList<>(quotaIds);
		all.addAll(generalIds);
		if (all.isEmpty()) {
			return all;
		}
		List<String> values = redis.opsForValue().multiGet(
				all.stream().map(id -> SeatKeys.hold(runId, id, GroupAllocationTx.SEGMENT_NO)).toList());
		List<Long> out = new ArrayList<>();
		for (int i = 0; i < all.size(); i++) {
			String v = values == null ? null : values.get(i);
			boolean isQuota = i < quotaIds.size();
			if (isQuota ? !SeatKeys.GROUP.equals(v) : v != null) {
				out.add(all.get(i));
			}
		}
		// DB에서만 진 경우(키는 그대로)에도 같은 좌석을 또 고르지 않게, 아무것도 못 찾으면 전부 뺀다
		return out.isEmpty() ? all : out;
	}

	/** §18.3-5. 실패해도 키는 결제 마감 + 유예 뒤 사라지고, 할당석은 DB가 BLOCKED라 일반 선점이 막힌다 */
	private void giveBack(List<String> keys, long r, long releaseAt, int quotaCount) {
		try {
			redis.execute(RETURN, keys, Long.toString(r), Long.toString(releaseAt), Integer.toString(quotaCount));
		} catch (RuntimeException e) {
			log.warn("단체 좌석 키 되돌리기 실패 reservation={} keys={}", r, keys.size(), e);
		}
	}

	// ---------------------------------------------------------------- 읽기·계산

	private Optional<Booking> booking(long groupId) {
		return jdbc.query("""
				SELECT b.group_id, b.rep_member_id, m.ci_hash, b.run_id, b.from_station_id, b.to_station_id,
				       b.passenger_count, b.status, b.discount_yn
				  FROM group_booking b JOIN member m ON m.member_id = b.rep_member_id
				 WHERE b.group_id = ?""", (rs, i) -> new Booking(rs.getLong(1), rs.getLong(2), rs.getString(3),
				rs.getLong(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8),
				"Y".equals(rs.getString(9))), groupId).stream().findFirst();
	}

	private List<Passenger> passengers(long groupId) {
		return jdbc.query("""
				SELECT passenger_no, passenger_type FROM group_passenger
				 WHERE group_id = ? AND status = 'ACTIVE' ORDER BY passenger_no""",
				(rs, i) -> new Passenger(rs.getInt(1), rs.getString(2)), groupId);
	}

	/** 일반실 운임 (할당석·일반 좌석 모두 일반실). [가정] 어린이 할인은 개인 예매와 같이 아직 없음 */
	private long fare(RunInfo run, Booking b) {
		return fares.find(run.trainKind(), "GENERAL", b.fromStationId(), b.toStationId(), run.runDate())
				.orElseThrow(() -> new IllegalStateException("운임 없음 run=" + run.runId()));
	}

	/** 어른 단체 할인. 원 단위 반올림 */
	static long discounted(long fare, double rate) {
		return fare - Math.round(fare * rate);
	}

	private long epochMs(LocalDateTime t) {
		return t.atZone(clock.getZone()).toInstant().toEpochMilli();
	}

	private static RedisScript<Long> script(String name) {
		DefaultRedisScript<Long> s = new DefaultRedisScript<>();
		s.setLocation(new ClassPathResource("lua/group/" + name + ".lua"));
		s.setResultType(Long.class);
		return s;
	}
}
