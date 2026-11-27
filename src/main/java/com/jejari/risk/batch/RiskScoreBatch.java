package com.jejari.risk.batch;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.replay.ReplayAdminService;
import com.jejari.risk.PreviousScore;
import com.jejari.risk.RiskEngine;
import com.jejari.risk.RiskResult;
import com.jejari.risk.SignalCollector;
import com.jejari.risk.SubjectType;
import com.jejari.risk.batch.RiskInputReader.StoredScore;
import com.jejari.risk.cache.RiskJobRunner;
import com.jejari.risk.store.RiskScoreStore;

/**
 * RISK-02 위험 점수 1분 배치. 새 신호가 생긴 대상({@link RiskDirtyQueue})만 다시 계산해 저장한다.
 *
 * <pre>
 * 락(risk:batch:lock) → 감쇠 갱신 대상 표시 → 대상 목록 넘겨받기
 *   → 대상마다: 현재 행 읽기 → 행동 로그·구매·취소·계정 집계 → 엔진 → (바뀌었으면) risk_score·이력 저장 → 캐시
 * </pre>
 *
 * <ul>
 *   <li><b>한 번에 하나</b>: 위험 점수 전용 스레드({@link RiskJobRunner})에서 돌아 재동기화와 겹치지 않고,
 *       여러 대를 띄워도 Redis 락으로 한 대만 돈다. 락은 실행 한도보다 길게 잡고 끝나면 내 것일 때만 푼다.</li>
 *   <li><b>대상 하나가 실패해도</b> 나머지는 계속한다. 실패한 대상은 다음 배치로 미룬다.</li>
 *   <li><b>집계 기간</b>: 행동 로그는 최근 {@code behavior-window}(기본 1일), 구매·취소는 {@code purchase-window}(기본 30일).
 *       관리자가 CLEAR한 대상은 CLEAR 이후만 센다 (엔진이 확인). 기간이 지난 증거는 이전 점수의 반감기로만 남는다.</li>
 *   <li><b>언제 저장하나</b>: {@link #shouldWrite} 참고. 매분 그대로 저장하면 소수 둘째 자리 반올림 때문에 감쇠가 멈춘다.</li>
 * </ul>
 */
@Component
// 개발 모드는 Oracle 점수 원본 없이 risk:*를 Redis에 직접 넣으므로(부하 테스트) 배치를 만들지 않는다
@ConditionalOnProperty(name = "jejari.dev-mode", havingValue = "false", matchIfMissing = true)
public class RiskScoreBatch {
	private static final Logger log = LoggerFactory.getLogger(RiskScoreBatch.class);

	public static final String LOCK_KEY = "risk:batch:lock";
	private static final int CLAIM = 200;
	private static final int DECAY_REFRESH_LIMIT = 1000;
	/** 감쇠만으로 점수가 이만큼 내려가면 저장한다 */
	static final BigDecimal DECAY_STEP = BigDecimal.ONE;

	private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
			"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
			Long.class);

	private final RiskDirtyQueue dirty;
	private final RiskInputReader reader;
	private final RiskPolicySource policies;
	private final RiskScoreStore store;
	private final SuspiciousCaseOpener cases;
	private final TransactionTemplate tx;
	private final RiskJobRunner runner;
	private final StringRedisTemplate redis;
	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final Duration interval;
	private final Duration behaviorWindow;
	private final Duration purchaseWindow;
	private final Duration maxRun;
	private final Duration decayRefreshAfter;
	private final boolean scheduleEnabled;

	/** ADM-11 녹화 삭제 보류. 녹화가 꺼져 있거나(테스트에서 직접 만든 배치) 없으면 건너뜀 */
	private ObjectProvider<ReplayAdminService> replayAdmin;

	@Autowired
	void setReplayAdmin(ObjectProvider<ReplayAdminService> replayAdmin) {
		this.replayAdmin = replayAdmin;
	}

	public RiskScoreBatch(RiskDirtyQueue dirty, RiskInputReader reader, RiskPolicySource policies, RiskScoreStore store,
			SuspiciousCaseOpener cases, TransactionTemplate tx,
			RiskJobRunner runner, StringRedisTemplate redis, JdbcTemplate jdbc, Clock clock,
			@Value("${jejari.risk.batch-interval:1m}") Duration interval,
			@Value("${jejari.risk.behavior-window:1d}") Duration behaviorWindow,
			@Value("${jejari.risk.purchase-window:30d}") Duration purchaseWindow,
			@Value("${jejari.risk.batch-max-run:40s}") Duration maxRun,
			@Value("${jejari.risk.decay-refresh-after:1d}") Duration decayRefreshAfter,
			@Value("${jejari.risk.batch-enabled:true}") boolean scheduleEnabled) {
		this.dirty = dirty;
		this.reader = reader;
		this.policies = policies;
		this.store = store;
		this.cases = cases;
		this.tx = tx;
		this.runner = runner;
		this.redis = redis;
		this.jdbc = jdbc;
		this.clock = clock;
		this.interval = interval;
		this.behaviorWindow = behaviorWindow;
		this.purchaseWindow = purchaseWindow;
		this.maxRun = maxRun;
		this.decayRefreshAfter = decayRefreshAfter;
		this.scheduleEnabled = scheduleEnabled;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		if (scheduleEnabled) { // 꺼 두면 주기 실행만 안 함 (테스트가 run()을 직접 부름)
			runner.scheduleWithFixedDelay(this::runOnce, interval);
		}
	}

	void runOnce() {
		try {
			Result r = run();
			if (r.scored() > 0 || r.failed() > 0) {
				log.info("위험 점수 배치: 계산 {}건, 저장 {}건, 케이스 {}건, 실패 {}건, 남은 대상 {}건", r.scored(), r.written(), r.opened(), r.failed(),
						r.pending());
			}
		} catch (RuntimeException e) {
			log.error("위험 점수 배치 실패 (다음 주기에 다시 시도)", e);
		}
	}

	/** 한 번 실행. 다른 인스턴스가 돌고 있으면 아무것도 하지 않는다 */
	public Result run() {
		String token = UUID.randomUUID().toString();
		Duration lockTtl = maxRun.plus(Duration.ofMinutes(1));
		if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, token, lockTtl.toMillis(),
				TimeUnit.MILLISECONDS))) {
			return new Result(0, 0, 0, 0, -1);
		}
		try {
			return runLocked();
		} finally {
			redis.execute(RELEASE, List.of(LOCK_KEY), token);
		}
	}

	private Result runLocked() {
		Instant started = clock.instant();
		markDecayRefresh(started);
		RiskEngine engine = new RiskEngine(policies.current());
		int scored = 0;
		int written = 0;
		int opened = 0;
		int failed = 0;
		while (Duration.between(started, clock.instant()).compareTo(maxRun) < 0) {
			List<RiskSubject> batch = dirty.claim(CLAIM);
			if (batch.isEmpty()) {
				break;
			}
			for (RiskSubject s : batch) {
				try {
					Outcome o = score(engine, s, clock.instant());
					if (o != Outcome.UNCHANGED) {
						written++;
					}
					if (o == Outcome.OPENED) {
						opened++;
					}
					scored++;
					dirty.done(s);
				} catch (RuntimeException e) {
					failed++;
					log.error("위험 점수 계산 실패 ({}), 다음 배치에서 다시 시도", s.type(), e);
					dirty.retryLater(s);
				}
			}
		}
		return new Result(scored, written, opened, failed, dirty.pending());
	}

	/**
	 * 오래 다시 계산하지 않은 점수 있는 대상을 목록에 넣는다. 새 신호가 없어도 감쇠가 캐시에 반영되게 (하루에 한 번 정도).
	 * 이게 없으면 조용해진 대상의 점수가 계속 그대로 남는다.
	 */
	private void markDecayRefresh(Instant now) {
		List<RiskSubject> stale = new ArrayList<>();
		jdbc.query("""
				SELECT subject_type, subject_hash FROM risk_score
				 WHERE final_score > 0 AND calculated_at < ?
				 FETCH FIRST %d ROWS ONLY""".formatted(DECAY_REFRESH_LIMIT),
				rs -> {
					RiskSubject s = RiskSubject.ofNullable(SubjectType.valueOf(rs.getString(1)), rs.getString(2));
					if (s != null) {
						stale.add(s);
					}
				}, Timestamp.from(now.minus(decayRefreshAfter)));
		dirty.markAll(stale);
	}

	Outcome score(RiskEngine engine, RiskSubject s, Instant now) {
		StoredScore stored = reader.stored(s);
		PreviousScore prev = stored == null ? null : stored.previous();
		Instant behaviorFrom = now.minus(behaviorWindow);
		Instant purchaseFrom = now.minus(purchaseWindow);
		if (prev != null && prev.clearedAt() != null) {
			behaviorFrom = max(behaviorFrom, prev.clearedAt());
			purchaseFrom = max(purchaseFrom, prev.clearedAt());
		}
		SignalCollector c = new SignalCollector(engine.policy(), s.type(), s.hash(), min(behaviorFrom, purchaseFrom));
		reader.behavior(c, s, behaviorFrom);
		reader.mouse(c, s, behaviorFrom);
		reader.headless(c, now);
		reader.transactions(c, s, purchaseFrom);
		reader.declinedCards(c, s, purchaseFrom, now);
		reader.accounts(c, s, now);
		RiskResult r = engine.score(c.build(), prev, now);
		if (!shouldWrite(stored, r, decayRefreshAfter)) {
			return Outcome.UNCHANGED;
		}
		// 증거 조회(MongoDB)는 트랜잭션 밖에서. 점수 저장과 케이스 생성은 한 트랜잭션 (RISK-04, SuspiciousCaseOpener.open)
		SuspiciousCaseOpener.Plan plan = cases.plan(engine.policy(), s, r, behaviorFrom, purchaseFrom);
		Long caseId = tx.execute(st -> {
			store.save(r);
			return plan == null ? null : cases.open(plan);
		});
		if (caseId != null) {
			// ADM-11: 케이스와 겹치는 화면 녹화가 90일 TTL로 지워지지 않게 삭제 보류 (커밋 뒤, 실패해도 케이스는 그대로)
			ReplayAdminService replays = replayAdmin == null ? null : replayAdmin.getIfAvailable();
			if (replays != null) {
				replays.hold(caseId);
			}
		}
		return caseId == null ? Outcome.WRITTEN : Outcome.OPENED;
	}

	enum Outcome {
		/** 바뀐 게 없어 저장하지 않음 */
		UNCHANGED,
		/** 점수 저장 */
		WRITTEN,
		/** 점수 저장 + 의심 케이스 생성 */
		OPENED
	}

	/**
	 * 저장할지 판단한다. risk_score의 calculated_at은 감쇠의 기준 시각이라, 저장할 때마다 "지금 값"이 새 기준이 된다.
	 *
	 * <ul>
	 *   <li>처음 보는 대상이 0점이면 저장하지 않는다 (정상 회원 전원의 0점 행이 쌓이지 않게).</li>
	 *   <li>규칙 점수가 감쇠한 이전 점수 이상이면(새 증거가 지배) 값·근거가 바뀌었을 때만 저장.</li>
	 *   <li>감쇠가 지배하면 {@link #DECAY_STEP}(1점) 이상 내려갔거나 마지막 저장이 오래됐을 때만 저장.
	 *       매분 저장하면 1분 감쇠량(80점 기준 0.001점)이 소수 둘째 자리 반올림에 지워지고 기준 시각만 계속 밀려
	 *       <b>점수가 영원히 줄지 않는다</b> (활동 중인 오탐 사용자가 풀리지 않음).</li>
	 * </ul>
	 */
	static boolean shouldWrite(StoredScore stored, RiskResult r, Duration refreshAfter) {
		if (stored == null) {
			return r.finalScore().signum() > 0;
		}
		PreviousScore prev = stored.previous();
		boolean ruleDominates = r.ruleScore().compareTo(r.finalScore()) >= 0;
		if (ruleDominates) {
			return r.finalScore().compareTo(prev.finalScore()) != 0
					|| stored.ruleScore() == null || r.ruleScore().compareTo(stored.ruleScore()) != 0
					|| !r.reasonsJson().equals(stored.reasons());
		}
		return prev.finalScore().subtract(r.finalScore()).compareTo(DECAY_STEP) >= 0
				|| !prev.calculatedAt().plus(refreshAfter).isAfter(r.calculatedAt())
				|| r.finalScore().signum() == 0 && prev.finalScore().signum() > 0;
	}

	private static Instant max(Instant a, Instant b) {
		return a.isAfter(b) ? a : b;
	}

	private static Instant min(Instant a, Instant b) {
		return a.isBefore(b) ? a : b;
	}

	/** @param pending 남은 대상 수. 락을 못 잡아 건너뛰었으면 -1 */
	public record Result(int scored, int written, int opened, int failed, long pending) {
	}
}
