package com.jejari.seat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.auth.AuthErrorCode;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.queue.QueueErrorCode;
import com.jejari.seat.HoldPlan.SeatLine;
import com.jejari.seat.PurchaseLimits.Limits;
import com.jejari.seat.SeatHoldTx.Preempted;
import com.jejari.train.FareReader;
import com.jejari.train.RunInfo;
import com.jejari.train.TrainRunReader;

/**
 * 좌석 선점 RSV-02 (concurrency.md §1 멱등, §2 선점, §3 재선점, §10 매수 카운터, §14.3 보상).
 *
 * <pre>
 * 검증 → 1회 한도 → 예약 ID 발급 → idem SET NX → 한도 1차(limit_incr.lua) → 좌석 경쟁(hold.lua)
 *      → [이긴 요청만] DB 트랜잭션(SeatHoldTx) → 커밋
 * 실패하면: 좌석 키(release.lua) · 한도(limit_decr.lua) · idem 키를 되돌린다 (§2.3)
 * </pre>
 *
 * <p>Redis 경쟁에서 진 요청은 DB에 한 줄도 쓰지 않습니다. 같은 좌석에 100명이 몰려도 DB에 가는 건 1명입니다 (T1).
 */
@Service
public class SeatHoldService {

	private static final Logger log = LoggerFactory.getLogger(SeatHoldService.class);

	/** 선점 요청. seats는 좌석 ID와 승객 유형(ADULT / CHILD / SENIOR) */
	public record HoldCommand(long runId, String fromStationId, String toStationId, List<SeatRequest> seats,
			String channel, String idempotencyKey) {
	}

	public record SeatRequest(long seatId, String passengerType) {
	}

	/** 선점 결과 (또는 같은 멱등 키로 이미 만든 예약의 현재 상태) */
	public record HoldResult(long reservationId, String status, LocalDateTime payDeadlineAt, long totalAmount,
			List<Long> seatIds, boolean replay) {
	}

	private static final java.util.Set<String> PASSENGER_TYPES = java.util.Set.of("ADULT", "CHILD", "SENIOR");
	private static final java.util.Set<String> CHANNELS = java.util.Set.of("WEB", "APP");

	private final TrainRunReader runs;
	private final SeatMapService seatMap;
	private final FareReader fares;
	private final PurchaseLimits limits;
	private final PurchaseCounters counters;
	private final SeatHoldTx holdTx;
	private final SeatPolicy policy;
	private final SeatScripts scripts;
	private final StringRedisTemplate redis;
	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final Clock clock;

	public SeatHoldService(TrainRunReader runs, SeatMapService seatMap, FareReader fares, PurchaseLimits limits, PurchaseCounters counters, SeatHoldTx holdTx,
			SeatPolicy policy, SeatScripts scripts, StringRedisTemplate redis, JdbcTemplate jdbc, TransactionTemplate tx,
			Clock clock) {
		this.runs = runs;
		this.seatMap = seatMap;
		this.fares = fares;
		this.limits = limits;
		this.counters = counters;
		this.holdTx = holdTx;
		this.policy = policy;
		this.scripts = scripts;
		this.redis = redis;
		this.jdbc = jdbc;
		this.tx = tx;
		this.clock = clock;
	}

	public HoldResult hold(long memberId, HoldCommand cmd) {
		LocalDateTime now = LocalDateTime.now(clock);

		// ---- 검증 (Redis·DB 쓰기 전) ----
		if (cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank() || cmd.idempotencyKey().length() > 64
				|| cmd.seats() == null || cmd.seats().isEmpty()) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		RunInfo run = runs.find(cmd.runId()).filter(r -> r.onSale(now))
				.orElseThrow(() -> new BusinessException(QueueErrorCode.RUN_NOT_ON_SALE));
		if (!run.validSegment(cmd.fromStationId(), cmd.toStationId())) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "승차역·하차역이 이 열차의 정차역 순서와 맞지 않습니다.");
		}
		String channel = cmd.channel() == null ? "WEB" : cmd.channel();
		if (!CHANNELS.contains(channel)) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		String ciHash = memberCiHash(memberId);
		List<SeatLine> seats = priceSeats(run, cmd);

		// 0. 1회 한도: 카운터 없이 값만 비교 (§2.1-0)
		Limits lim = limits.read(run, now);
		int n = seats.size();
		if (lim.perReservation() != null && n > lim.perReservation()) {
			throw new BusinessException(SeatErrorCode.LIMIT_PER_RESERVATION);
		}

		// 1. 예약 ID를 먼저 받고 멱등 키를 잡는다 (§1)
		long r = jdbc.queryForObject("SELECT reservation_seq.NEXTVAL FROM dual", Long.class);
		String idemKey = SeatKeys.idem(memberId, cmd.idempotencyKey());
		Boolean first = redis.opsForValue().setIfAbsent(idemKey, Long.toString(r),
				policy.getLong(SeatPolicy.IDEM_TTL_SEC), TimeUnit.SECONDS);
		if (!Boolean.TRUE.equals(first)) {
			return replay(memberId, redis.opsForValue().get(idemKey));
		}

		long purchaseDay = SeatHoldTx.yyyymmdd(now);
		LimitKeys limitKeys = limitKeys(run, ciHash, purchaseDay, lim);
		LocalDateTime deadline = now.plusMinutes(policy.getLong(SeatPolicy.PAY_DEADLINE_MIN));
		List<String> seatKeys = seats.stream().map(s -> SeatKeys.hold(run.runId(), s.seatId(), HoldPlan.SEGMENT_NO)).toList();

		// 2. 매수 한도 1차 (§2.1-2). 넘으면 자가 복구 한 번 (§10.7)
		try {
			limitIncr(limitKeys, n, ciHash);
		} catch (BusinessException e) {
			release(List.of(idemKey), r);
			throw e;
		}

		// 3~4. 좌석 경쟁. 진 요청은 DB 쓰기 0건 (§2.1-4)
		Long won = redis.execute(scripts.hold, seatKeys, Long.toString(r), Long.toString(epochMs(deadline)), "");
		if (won == null || won != 1L) {
			limitDecr(r, limitKeys.keys, n, limitKeys.markExpiry);
			release(List.of(idemKey), r);
			throw new BusinessException(SeatErrorCode.SEAT_ALREADY_TAKEN);
		}

		// 5. 이긴 요청만 DB 트랜잭션 (§2.2)
		HoldPlan plan = new HoldPlan(r, memberId, ciHash, run, cmd.fromStationId(), cmd.toStationId(), seats,
				seats.stream().mapToLong(SeatLine::amount).sum(), cmd.idempotencyKey(), channel, now, deadline,
				purchaseDay, lim);
		List<Preempted> preempted;
		try {
			preempted = tx.execute(st -> holdTx.write(plan));
		} catch (RuntimeException e) {
			// §2.3 롤백 보상: 내 값인 좌석 키만 지우고, 한도를 되돌린다
			release(seatKeys, r);
			limitDecr(r, limitKeys.keys, n, limitKeys.markExpiry);
			if (e instanceof DuplicateKeyException) {
				Optional<Long> existing = existingByKey(memberId, cmd.idempotencyKey());
				if (existing.isPresent()) {
					// §1-2: idem TTL이 지난 뒤 같은 키로 다시 옴 → 기존 예약을 돌려준다 (T31)
					redis.opsForValue().setIfPresent(idemKey, Long.toString(existing.get()),
							policy.getLong(SeatPolicy.IDEM_TTL_SEC), TimeUnit.SECONDS);
					return replay(memberId, Long.toString(existing.get()));
				}
				release(List.of(idemKey), r);
				throw new BusinessException(SeatErrorCode.SEAT_ALREADY_TAKEN, e);
			}
			release(List.of(idemKey), r);
			throw e;
		}

		// 6. 커밋 후: 재선점으로 만료시킨 이전 예약의 Redis 한도를 되돌린다 (빠른 경로, §3-7·§14.3).
		//    앱이 여기서 죽어도 그 예약의 RESERVATION_EXPIRED 이벤트를 seat-release가 소비하며 같은 일을 한다
		for (Preempted p : preempted) {
			try {
				LimitKeys old = limitKeys(run, p.ciHash(), p.purchaseDay(), lim);
				limitDecr(p.reservationId(), old.keys, p.seatCount(), old.markExpiry);
			} catch (RuntimeException e) {
				log.warn("재선점한 이전 예약 {}의 Redis 한도 되돌리기 실패 (seat-release가 다시 함)", p.reservationId(), e);
			}
		}
		try {
			seatMap.evict(run.runId()); // 내 화면에 방금 잡은 좌석이 바로 보이게
		} catch (RuntimeException e) {
			log.debug("seatmap 캐시 삭제 실패 (유지 시간 뒤 갱신됨)", e);
		}
		return new HoldResult(r, "HOLD", deadline, plan.totalAmount(),
				seats.stream().map(SeatLine::seatId).toList(), false);
	}

	// ---------------------------------------------------------------- 검증 도우미

	private String memberCiHash(long memberId) {
		record M(String ciHash, String status) {
		}
		List<M> rows = jdbc.query("SELECT ci_hash, status FROM member WHERE member_id = ?",
				(rs, i) -> new M(rs.getString(1), rs.getString(2)), memberId);
		if (rows.isEmpty()) {
			throw new BusinessException(CommonErrorCode.AUTH_REQUIRED);
		}
		if ("BANNED".equals(rows.get(0).status())) {
			throw new BusinessException(AuthErrorCode.AUTH_BANNED);
		}
		return rows.get(0).ciHash();
	}

	/** 좌석이 그 열차 것인지 확인하고, 등급별 운임으로 좌석마다 금액을 정한다 (선점 시점 운임으로 고정) */
	private List<SeatLine> priceSeats(RunInfo run, HoldCommand cmd) {
		List<SeatRequest> sorted = new ArrayList<>(cmd.seats());
		sorted.sort(Comparator.comparingLong(SeatRequest::seatId)); // INSERT·키 순서 고정 (§0.4-3)
		if (new HashSet<>(sorted.stream().map(SeatRequest::seatId).toList()).size() != sorted.size()) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "같은 좌석이 두 번 들어 있습니다.");
		}
		String in = String.join(",", sorted.stream().map(s -> "?").toList());
		List<Object> args = new ArrayList<>();
		args.add(run.trainId());
		sorted.forEach(s -> args.add(s.seatId()));
		Map<Long, String> grades = new java.util.HashMap<>();
		jdbc.query("SELECT seat_id, seat_grade FROM seat WHERE train_id = ? AND seat_id IN (" + in + ")",
				rs -> {
					grades.put(rs.getLong(1), rs.getString(2));
				}, args.toArray());
		if (grades.size() != sorted.size()) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "이 열차에 없는 좌석이 있습니다.");
		}
		Map<String, Long> fareByGrade = new java.util.HashMap<>();
		List<SeatLine> lines = new ArrayList<>();
		for (SeatRequest s : sorted) {
			String type = s.passengerType() == null ? "ADULT" : s.passengerType();
			if (!PASSENGER_TYPES.contains(type)) {
				throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
			}
			String grade = grades.get(s.seatId());
			long amount = fareByGrade.computeIfAbsent(grade, g -> fare(run, g, cmd.fromStationId(), cmd.toStationId()));
			lines.add(new SeatLine(s.seatId(), type, amount)); // [가정] 승객 유형별 할인은 아직 없음 (어른 운임)
		}
		return lines;
	}

	private long fare(RunInfo run, String grade, String from, String to) {
		return fares.find(run.trainKind(), grade, from, to, run.runDate())
				.orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_REQUEST, "운임이 등록되지 않은 구간입니다."));
	}

	// ---------------------------------------------------------------- 멱등

	/** 같은 멱등 키로 이미 만든 예약의 현재 상태. DB에 아직 행이 없으면 "처리 중"(202) */
	private HoldResult replay(long memberId, String value) {
		if (value == null) {
			throw new BusinessException(SeatErrorCode.REQUEST_IN_PROGRESS);
		}
		long r = Long.parseLong(value);
		record Row(String status, LocalDateTime deadline, long amount) {
		}
		List<Row> rows = jdbc.query("""
				SELECT status, pay_deadline_at, total_amount FROM reservation WHERE reservation_id = ? AND member_id = ?""",
				(rs, i) -> new Row(rs.getString(1), rs.getObject(2, LocalDateTime.class), rs.getLong(3)), r, memberId);
		if (rows.isEmpty()) {
			throw new BusinessException(SeatErrorCode.REQUEST_IN_PROGRESS);
		}
		List<Long> seatIds = jdbc.queryForList(
				"SELECT seat_id FROM reservation_seat WHERE reservation_id = ? ORDER BY seat_id", Long.class, r);
		Row row = rows.get(0);
		return new HoldResult(r, row.status(), row.deadline(), row.amount(), seatIds, true);
	}

	private Optional<Long> existingByKey(long memberId, String key) {
		return jdbc.queryForList("SELECT reservation_id FROM reservation WHERE member_id = ? AND idempotency_key = ?",
				Long.class, memberId, key).stream().findFirst();
	}

	// ---------------------------------------------------------------- 한도 (Redis 1차)

	/** limit_incr.lua에 넘길 키·한도·복원값·만료. 정책이 있는 scope만 Redis로 1차 판정한다 */
	private record LimitKeys(List<String> keys, List<LimitScope> scopes, List<Long> refIds, List<Integer> max,
			List<Long> expiries, long markExpiry) {
	}

	private LimitKeys limitKeys(RunInfo run, String ciHash, long purchaseDay, Limits lim) {
		List<String> keys = new ArrayList<>();
		List<LimitScope> scopes = new ArrayList<>();
		List<Long> refs = new ArrayList<>();
		List<Integer> max = new ArrayList<>();
		List<Long> exp = new ArrayList<>();
		long departure = epochMs(run.departureAt() != null ? run.departureAt() : run.runDate().plusDays(1).atStartOfDay());
		long markExpiry = departure;
		if (lim.limited(LimitScope.PER_RUN)) {
			keys.add(SeatKeys.limitRun(run.runId(), ciHash));
			scopes.add(LimitScope.PER_RUN);
			refs.add(run.runId());
			max.add(lim.max(LimitScope.PER_RUN));
			exp.add(departure);
		}
		if (lim.limited(LimitScope.PER_DAY)) {
			LocalDate day = LocalDate.of((int) (purchaseDay / 10000), (int) (purchaseDay / 100 % 100), (int) (purchaseDay % 100));
			long dayEnd = epochMs(day.plusDays(1).atStartOfDay().plusHours(1)); // 그날 24:00 + 1시간 (ERD v11)
			keys.add(SeatKeys.limitDay(purchaseDay, ciHash));
			scopes.add(LimitScope.PER_DAY);
			refs.add(purchaseDay);
			max.add(lim.max(LimitScope.PER_DAY));
			exp.add(dayEnd);
			markExpiry = Math.max(markExpiry, dayEnd);
		}
		if (run.saleEventId() != null && lim.limited(LimitScope.PER_HOLIDAY)) {
			LocalDate end = run.eventEndDate() != null ? run.eventEndDate() : run.runDate();
			long eventEnd = epochMs(end.plusDays(1).atStartOfDay());
			keys.add(SeatKeys.limitEvent(run.saleEventId(), ciHash));
			scopes.add(LimitScope.PER_HOLIDAY);
			refs.add(run.saleEventId());
			max.add(lim.max(LimitScope.PER_HOLIDAY));
			exp.add(eventEnd);
			markExpiry = Math.max(markExpiry, eventEnd);
		}
		return new LimitKeys(keys, scopes, refs, max, exp, markExpiry);
	}

	@SuppressWarnings("unchecked")
	private void limitIncr(LimitKeys lk, int n, String ciHash) {
		if (lk.keys.isEmpty()) {
			return;
		}
		for (int attempt = 0; attempt < 2; attempt++) {
			List<String> argv = new ArrayList<>();
			argv.add(Integer.toString(n));
			for (int i = 0; i < lk.keys.size(); i++) {
				argv.add(Integer.toString(lk.max.get(i)));
				argv.add(Integer.toString(counters.current(lk.scopes.get(i), lk.refIds.get(i), ciHash))); // 복원값 (§10.4)
				argv.add(Long.toString(lk.expiries.get(i)));
			}
			List<Object> res = redis.execute(scripts.limitIncr, lk.keys, argv.toArray());
			if (res != null && !res.isEmpty() && ((Number) res.get(0)).longValue() == 1L) {
				return;
			}
			int i = ((Number) res.get(1)).intValue() - 1;
			LimitScope scope = lk.scopes.get(i);
			// §10.7 자가 복구: 커밋 후 보상이 유실돼 Redis가 DB보다 크게 남았을 수 있다. DB와 한 번만 대조
			int db = counters.current(scope, lk.refIds.get(i), ciHash);
			if (attempt == 0 && db + n <= lk.max.get(i)) {
				redis.opsForValue().set(lk.keys.get(i), Integer.toString(db));
				redis.expireAt(lk.keys.get(i), java.time.Instant.ofEpochMilli(lk.expiries.get(i)));
				continue;
			}
			throw new BusinessException(scope.error);
		}
	}

	private void limitDecr(long reservationId, List<String> limitKeys, int n, long markExpiry) {
		if (limitKeys.isEmpty()) {
			return;
		}
		List<String> keys = new ArrayList<>();
		keys.add(SeatKeys.limitDec(reservationId));
		keys.addAll(limitKeys);
		redis.execute(scripts.limitDecr, keys, Integer.toString(n), Long.toString(markExpiry));
	}

	/** compare-and-delete: 값이 내 예약 ID인 키만 지운다 */
	private void release(List<String> keys, long reservationId) {
		try {
			redis.execute(scripts.release, keys, Long.toString(reservationId));
		} catch (RuntimeException e) {
			// 보상 유실: 좌석 키는 마감 뒤 TTL로, idem은 TTL로 사라진다 (§14.3)
			log.warn("Redis 보상 실패 reservation={} keys={}", reservationId, keys.size(), e);
		}
	}

	private long epochMs(LocalDateTime t) {
		return t.atZone(clock.getZone()).toInstant().toEpochMilli();
	}
}
