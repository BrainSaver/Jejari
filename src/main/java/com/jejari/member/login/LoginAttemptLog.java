package com.jejari.member.login;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import com.jejari.member.AuthPolicy;
import com.jejari.member.MemberAuthEnabled;

/**
 * 모든 로그인 시도를 MongoDB {@code login_attempt}에 남긴다 (크리덴셜 스터핑 탐지, Argon2 부하 측정).
 * 이메일·IP는 해시만 남긴다. {@code expire_at}(ts + 보관일)에 TTL 인덱스가 걸려 자동 삭제된다.
 *
 * <p>기록이 실패해도 로그인 자체는 막지 않는다 (MongoDB 장애가 로그인 장애로 번지지 않게). 실패는 오류 로그로 남긴다.
 */
@Component
@MemberAuthEnabled
public class LoginAttemptLog {
	private static final Logger log = LoggerFactory.getLogger(LoginAttemptLog.class);

	/** login_attempt.result. BANNED는 ERD에 추가 요청 */
	public enum Result { SUCCESS, WRONG_PASSWORD, LOCKED, NOT_FOUND, RATE_LIMITED, BANNED }

	private final MongoTemplate mongo;
	private final AuthPolicy policy;
	private final Clock clock;

	public LoginAttemptLog(MongoTemplate mongo, AuthPolicy policy, Clock clock) {
		this.mongo = mongo;
		this.policy = policy;
		this.clock = clock;
	}

	public void record(Long memberId, String emailHash, Result result, String ipHash, String userAgent,
			String sessionId, Long verifyMs) {
		try {
			Instant now = clock.instant();
			Instant expireAt = now.plus(policy.number(AuthPolicy.LOGIN_ATTEMPT_RETENTION_DAYS), ChronoUnit.DAYS);
			Document d = new Document()
					.append("member_id", memberId)
					.append("email_hash", emailHash)
					.append("result", result.name())
					.append("ip_hash", ipHash)
					.append("device_hash", null)
					.append("user_agent", userAgent)
					.append("session_id", sessionId)
					.append("verify_ms", verifyMs == null ? null : verifyMs.intValue())
					.append("ts", Date.from(now))
					.append("expire_at", Date.from(expireAt));
			mongo.insert(d, "login_attempt");
		} catch (RuntimeException e) {
			log.error("login_attempt 기록 실패 result={}", result, e);
		}
	}
}
