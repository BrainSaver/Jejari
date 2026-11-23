package com.jejari.behavior.sink;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.bson.Document;

import com.jejari.behavior.BehaviorEvent;
import com.jejari.behavior.BehaviorMeta;
import com.jejari.behavior.BehaviorType;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka 메시지 → behavior_log 문서. 토픽에는 인증이 없어 누구나 넣을 수 있다고 보고, 형식을 다시 검사한다
 * (보낼 때 거른 것과 같은 규칙 + 필드별 형식). 이상한 메시지는 null → 건너뛴다 (컨슈머가 멈추지 않게).
 */
public final class BehaviorDocuments {

	private static final Pattern UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
	private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
	private static final Set<String> TYPES = EnumSet.allOf(BehaviorType.class).stream().map(Enum::name)
			.collect(Collectors.toUnmodifiableSet());
	private static final int MAX_MESSAGE_CHARS = 16_384;

	private BehaviorDocuments() {
	}

	/** 검사를 통과한 이벤트 */
	public record Parsed(BehaviorEvent event, Instant ts) {
	}

	public static Parsed parse(String message, JsonMapper json) {
		if (message == null || message.length() > MAX_MESSAGE_CHARS) {
			return null;
		}
		BehaviorEvent e;
		try {
			e = json.readValue(message, BehaviorEvent.class);
		} catch (JacksonException ex) {
			return null;
		}
		if (e == null || e.eventId() == null || !UUID.matcher(e.eventId()).matches()
				|| e.eventType() == null || !TYPES.contains(e.eventType())
				|| !hashOrNull(e.deviceHash()) || !hashOrNull(e.ipHash())
				|| (e.sessionId() != null && !UUID.matcher(e.sessionId()).matches())
				|| (e.memberId() != null && e.memberId() <= 0) || (e.runId() != null && e.runId() <= 0)
				|| !("WEB".equals(e.channel()) || "APP".equals(e.channel()))
				|| e.responseStatus() < 100 || e.responseStatus() > 599
				|| (e.userAgent() != null && e.userAgent().length() > 300)) {
			return null;
		}
		Instant ts;
		try {
			ts = OffsetDateTime.parse(e.ts()).toInstant();
		} catch (DateTimeParseException | NullPointerException ex) {
			return null;
		}
		return new Parsed(e, ts);
	}

	/**
	 * ERD 컬럼명(snake_case)으로 바꾼 문서. meta는 한 번 더 거른다.
	 *
	 * @param ciHash     member_id로 찾은 CI 해시 (비로그인·없는 회원이면 null)
	 * @param intervalMs 같은 세션의 직전 이벤트와 간격 (첫 이벤트면 null)
	 */
	public static Document toDocument(Parsed p, String ciHash, Integer intervalMs, long retentionDays) {
		BehaviorEvent e = p.event();
		Map<String, Object> meta = BehaviorMeta.sanitize(e.meta()).meta();
		Document d = new Document()
				.append("event_id", e.eventId())
				.append("member_id", e.memberId())
				.append("ci_hash", ciHash)
				.append("device_hash", e.deviceHash())
				.append("ip_hash", e.ipHash())
				.append("session_id", e.sessionId())
				.append("event_type", e.eventType())
				.append("run_id", e.runId())
				.append("ts", Date.from(p.ts()))
				.append("interval_ms", intervalMs)
				.append("user_agent", e.userAgent())
				.append("channel", e.channel())
				.append("response_status", e.responseStatus())
				.append("meta", new Document(meta))
				.append("expire_at", Date.from(p.ts().plus(retentionDays, ChronoUnit.DAYS)));
		// 처리 시간: 위조할 수 없는 서버 값. 음수·하루 넘는 값은 이상한 값이라 버린다
		if (e.elapsedMs() != null && e.elapsedMs() >= 0 && e.elapsedMs() < 86_400_000L) {
			d.append("elapsed_ms", e.elapsedMs());
		}
		return d;
	}

	private static boolean hashOrNull(String h) {
		return h == null || HASH.matcher(h).matches();
	}
}
