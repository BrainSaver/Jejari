package com.jejari.member.login;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import com.jejari.member.AuthPolicy;
import com.jejari.member.MemberAuthEnabled;

/**
 * 모든 로그인 시도를 MongoDB {@code login_attempt}에 남긴다 (크리덴셜 스터핑 탐지, Argon2 부하 측정).
 * 이메일·IP는 해시만 남긴다. {@code expire_at}(ts + 보관일)에 TTL 인덱스가 걸려 자동 삭제된다.
 *
 * <p><b>로그인 요청은 기록을 기다리지 않는다.</b> 기록은 전용 스레드 1개가 큐에서 꺼내 저장한다.
 * MongoDB가 느리거나 죽어 있어도 로그인 응답 시간은 그대로다 (예전에는 연결 대기 30초만큼 로그인이 멈췄다).
 * <ul>
 *   <li>시각({@code ts})은 시도한 순간에 정한다 (저장이 늦어져도 시각이 밀리지 않게).</li>
 *   <li>큐는 {@value #QUEUE_CAPACITY}건까지. Mongo 장애 중 로그인이 몰려도 메모리가 끝없이 늘지 않게, 넘치면 버리고
 *       버린 수를 경고로 남긴다 (10초에 한 번). 로그인 자체는 IP·이메일 제한이 따로 막는다.</li>
 *   <li>저장 실패는 오류 로그만 남긴다. 앱이 내려갈 때 남은 기록을 최대 5초 기다려 저장한다.</li>
 * </ul>
 */
@Component
@MemberAuthEnabled
public class LoginAttemptLog implements DisposableBean {
	private static final Logger log = LoggerFactory.getLogger(LoginAttemptLog.class);

	static final int QUEUE_CAPACITY = 10_000;
	private static final long WARN_INTERVAL_MS = 10_000;

	/** login_attempt.result */
	public enum Result { SUCCESS, WRONG_PASSWORD, LOCKED, NOT_FOUND, RATE_LIMITED, BANNED }

	private final MongoTemplate mongo;
	private final AuthPolicy policy;
	private final Clock clock;
	private final ThreadPoolExecutor writer;
	private final AtomicLong dropped = new AtomicLong();
	private final AtomicLong lastWarnAt = new AtomicLong();

	public LoginAttemptLog(MongoTemplate mongo, AuthPolicy policy, Clock clock) {
		this.mongo = mongo;
		this.policy = policy;
		this.clock = clock;
		this.writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
				r -> {
					Thread t = new Thread(r, "login-attempt-log");
					t.setDaemon(true);
					return t;
				},
				(r, ex) -> onDropped());
	}

	/** 바로 돌아온다. 저장은 뒤에서 한다. */
	public void record(Long memberId, String emailHash, Result result, String ipHash, String userAgent,
			String sessionId, Long verifyMs) {
		write("MEMBER", memberId, null, emailHash, result, ipHash, userAgent, sessionId, verifyMs);
	}

	/**
	 * 관리자 로그인 시도 (AUTH-05). login_attempt의 actor_type = ADMIN, admin_id로 남긴다 (DB설계서: 관리자 계정 무차별 대입 탐지).
	 * email_hash 자리에는 로그인 ID 해시(Purpose.ADMIN_LOGIN)를 넣는다.
	 */
	public void recordAdmin(Long adminId, String loginIdHash, Result result, String ipHash, String userAgent,
			String sessionId, Long verifyMs) {
		write("ADMIN", null, adminId, loginIdHash, result, ipHash, userAgent, sessionId, verifyMs);
	}

	private void write(String actorType, Long memberId, Long adminId, String emailHash, Result result, String ipHash,
			String userAgent, String sessionId, Long verifyMs) {
		Instant now;
		long retentionDays;
		try {
			now = clock.instant();
			retentionDays = policy.number(AuthPolicy.LOGIN_ATTEMPT_RETENTION_DAYS);
		} catch (RuntimeException e) {
			// 정책을 못 읽어도(Redis 장애) 로그인 흐름을 깨지 않는다. 보관일은 ERD 기본값
			now = Instant.now();
			retentionDays = 30;
		}
		Document d = new Document()
				.append("actor_type", actorType)
				.append("member_id", memberId)
				.append("admin_id", adminId)
				.append("email_hash", emailHash)
				.append("result", result.name())
				.append("ip_hash", ipHash)
				.append("device_hash", null)
				.append("user_agent", userAgent)
				.append("session_id", sessionId)
				.append("verify_ms", verifyMs == null ? null : verifyMs.intValue())
				.append("ts", Date.from(now))
				.append("expire_at", Date.from(now.plus(retentionDays, ChronoUnit.DAYS)));
		try {
			writer.execute(() -> save(d, result));
		} catch (RejectedExecutionException e) {
			onDropped(); // 종료 중
		}
	}

	private void save(Document d, Result result) {
		try {
			mongo.insert(d, "login_attempt");
		} catch (RuntimeException e) {
			log.error("login_attempt 기록 실패 result={}: {}", result, e.toString());
		}
	}

	private void onDropped() {
		long n = dropped.incrementAndGet();
		long now = System.currentTimeMillis();
		long last = lastWarnAt.get();
		if (now - last >= WARN_INTERVAL_MS && lastWarnAt.compareAndSet(last, now)) {
			log.warn("login_attempt 기록 큐가 가득 차 버림 (누적 {}건). MongoDB 연결을 확인하세요", n);
		}
	}

	/** 지금까지 버린 기록 수 (모니터링·테스트용) */
	public long droppedCount() {
		return dropped.get();
	}

	@Override
	public void destroy() throws InterruptedException {
		writer.shutdown();
		if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
			log.warn("앱 종료: 저장하지 못한 login_attempt {}건을 버림", writer.getQueue().size());
			writer.shutdownNow();
		}
	}
}
