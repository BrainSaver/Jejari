package com.jejari.replay;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.jejari.common.config.AppPolicy;
import com.jejari.risk.SubjectType;
import com.jejari.risk.cache.RiskScoreCache;

/**
 * ADM-11 화면 녹화 받기. 요청 스레드는 검사만 하고 Kafka({@code jejari.replay})로 넘긴다. 저장은 {@code ReplaySink}.
 *
 * <pre>
 * 녹화 모드 (저장량 제어, ERD replay_session.recording_mode):
 *   FULL    = 회원 CI의 위험 점수 ≥ replay.full_record_score(70), 또는 세션 ID 해시로 고른 replay.full_record_ratio(10%)
 *   SAMPLED = 나머지. 마우스 궤적(MOUSE_PATH, 양의 대부분)은 버리고 화면 이동·클릭·입력 길이만
 * </pre>
 * 세션 ID 해시로 고르므로 같은 세션은 요청마다 모드가 바뀌지 않는다. 점수가 중간에 70을 넘으면 그때부터 FULL.
 */
@Service
public class ReplayService {

	/** @param maxPathPoints 이 모드의 궤적 묶음당 점 수 · pathSampleMs 마우스 위치를 재는 간격 */
	public record Config(boolean enabled, String mode, long batchSec, long maxPathPoints, long pathSampleMs) {
	}

	private final AppPolicy policy;
	private final StringRedisTemplate redis;
	private final JdbcTemplate jdbc;
	private final ObjectProvider<ReplayPublisher> publisher;
	private final Clock clock;

	public ReplayService(AppPolicy policy, StringRedisTemplate redis, JdbcTemplate jdbc,
			ObjectProvider<ReplayPublisher> publisher, Clock clock) {
		this.policy = policy;
		this.redis = redis;
		this.jdbc = jdbc;
		this.publisher = publisher;
		this.clock = clock;
	}

	public Config config(long memberId, String sessionId) {
		boolean enabled = publisher.getIfAvailable() != null && sessionId != null;
		String mode = enabled ? mode(memberId, sessionId) : "OFF";
		return new Config(enabled, mode, policy.getLong(AppPolicy.REPLAY_BATCH_SEC),
				ReplayBatch.pathPoints(mode, (int) policy.getLong(AppPolicy.REPLAY_MAX_PATH_POINTS)),
				ReplayBatch.pathSampleMs(mode));
	}

	/** @return 받은(남긴) 이벤트 수 */
	public int accept(long memberId, String sessionId, String deviceHash, String ipHash, List<ReplayBatch.In> events) {
		ReplayPublisher pub = publisher.getIfAvailable();
		if (pub == null || sessionId == null) {
			return 0; // 녹화 꺼짐: 클라이언트를 실패시키지 않는다
		}
		String mode = mode(memberId, sessionId);
		List<ReplayBatch.Event> ok = ReplayBatch.sanitize(events, mode,
				(int) policy.getLong(AppPolicy.REPLAY_MAX_PATH_POINTS), clock.instant());
		if (!ok.isEmpty()) {
			pub.publish(new ReplayMessage(sessionId, memberId, deviceHash, ipHash, mode, clock.millis(), ok));
		}
		return ok.size();
	}

	String mode(long memberId, String sessionId) {
		if (sampledIn(sessionId, policy.getDouble(AppPolicy.REPLAY_FULL_RECORD_RATIO))) {
			return "FULL";
		}
		List<String> ci = jdbc.queryForList("SELECT ci_hash FROM member WHERE member_id = ?", String.class, memberId);
		try {
			if (!ci.isEmpty() && ci.get(0) != null) {
				String v = redis.opsForValue().get(RiskScoreCache.key(SubjectType.CI, ci.get(0)));
				if (v != null && new BigDecimal(v).compareTo(BigDecimal.valueOf(
						policy.getLong(AppPolicy.REPLAY_FULL_RECORD_SCORE))) >= 0) {
					return "FULL";
				}
			}
		} catch (IllegalArgumentException e) {
			// 형식이 이상한 CI·점수 값: 녹화 때문에 회원 화면이 실패하면 안 되므로 평소 모드로
		}
		return "SAMPLED";
	}

	/** 세션 ID로 정하는 표본 (같은 세션은 늘 같은 답). FNV-1a 해시를 0~1로 */
	static boolean sampledIn(String sessionId, double ratio) {
		if (ratio <= 0) {
			return false;
		}
		int h = 0x811c9dc5;
		for (byte b : sessionId.getBytes(StandardCharsets.UTF_8)) {
			h ^= (b & 0xff);
			h *= 0x01000193;
		}
		return (Integer.toUnsignedLong(h) / 4294967296.0) < ratio;
	}
}
