package com.jejari.queue.dev;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.queue.redis.QueueKeys;

/**
 * 개발·부하 테스트 전용 관리 API. 부하 테스트 프로그램(loadtest/)이 Redis에 직접 접속하지 않고
 * 시나리오 전에 상태를 비우고, 위험 점수·정책을 정할 수 있게 한다.
 * jejari.dev-mode=true일 때만 만들어지며 운영에서는 존재하지 않는다 (인증 없음, DevSecurityConfig가 열어 줌).
 */
@RestController
@RequestMapping("/api/v1/dev")
@ConditionalOnProperty(name = "jejari.dev-mode", havingValue = "true")
public class DevAdminController {

	private static final Pattern POLICY_KEY = Pattern.compile("^(queue|risk|waitlist)\\.[a-z_]{1,40}$");
	private static final Pattern POLICY_VALUE = Pattern.compile("^[A-Za-z0-9_.-]{1,32}$");
	private static final Pattern HASH = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
	private static final List<String> SUBJECT_TYPES = List.of("CI", "PAYMENT", "DEVICE");
	private static final int MAX_MEMBERS = 100_000;

	private final StringRedisTemplate redis;
	private final DefaultRedisScript<Long> deleteByPattern;

	public DevAdminController(StringRedisTemplate redis) {
		this.redis = redis;
		this.deleteByPattern = new DefaultRedisScript<>();
		this.deleteByPattern.setLocation(new ClassPathResource("lua/queue/delete_by_pattern.lua"));
		this.deleteByPattern.setResultType(Long.class);
	}

	/** 대기열·위험 점수·정책 캐시 키를 지운다. 시나리오를 다시 돌리기 전에 부른다. */
	@PostMapping("/reset")
	public Map<String, Object> reset() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("queue", redis.execute(deleteByPattern, List.of(), "queue:*"));
		out.put("risk", redis.execute(deleteByPattern, List.of(), "risk:*"));
		redis.delete(QueueKeys.POLICY_CACHE);
		out.put("policyCache", "cleared");
		return out;
	}

	/** 위험 점수 하나 설정. body: {"subjectType":"CI","hash":"...","score":80} */
	@PutMapping("/risk")
	public Map<String, Object> setRisk(@RequestBody Map<String, Object> body) {
		String type = String.valueOf(body.get("subjectType"));
		String hash = String.valueOf(body.get("hash"));
		Double score = number(body.get("score"));
		if (!SUBJECT_TYPES.contains(type) || !HASH.matcher(hash).matches() || score == null || score < 0 || score > 100) {
			throw invalid("subjectType은 CI/PAYMENT/DEVICE, hash는 영문·숫자·._-, score는 0~100이어야 합니다");
		}
		redis.opsForValue().set(QueueKeys.risk(type, hash), trim(score));
		return Map.of("key", QueueKeys.risk(type, hash), "score", score);
	}

	/**
	 * 개발용 회원(CI 해시 = dev-ci-{회원ID}) from부터 count명에게 같은 위험 점수를 준다.
	 * 예: PUT /api/v1/dev/risk/dev-members?from=3000000&count=20&score=80
	 */
	@PutMapping("/risk/dev-members")
	public Map<String, Object> setDevMembersRisk(@RequestParam long from, @RequestParam int count,
			@RequestParam double score) {
		if (count < 1 || count > MAX_MEMBERS || score < 0 || score > 100) {
			throw invalid("count는 1~" + MAX_MEMBERS + ", score는 0~100이어야 합니다");
		}
		for (int i = 0; i < count; i++) {
			redis.opsForValue().set(QueueKeys.risk("CI", "dev-ci-" + (from + i)), trim(score));
		}
		return Map.of("from", from, "count", count, "score", score);
	}

	/** 정책 값 설정 (app_policy의 Redis 캐시). 앱이 최대 policy-cache-ms(기본 1초) 뒤에 반영한다. */
	@PutMapping("/policy/{key}")
	public Map<String, Object> setPolicy(@PathVariable String key, @RequestBody Map<String, Object> body) {
		String value = String.valueOf(body.get("value"));
		if (!POLICY_KEY.matcher(key).matches() || !POLICY_VALUE.matcher(value).matches()) {
			throw invalid("key는 queue.*, risk.*, waitlist.* 이고 value는 영문·숫자·._- 32자 이내여야 합니다");
		}
		redis.opsForHash().put(QueueKeys.POLICY_CACHE, key, value);
		return Map.of("key", key, "value", value, "appliesWithinMs", 1000);
	}

	private static Double number(Object o) {
		try {
			return o == null ? null : Double.valueOf(String.valueOf(o));
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private static String trim(double score) {
		return score == Math.rint(score) ? String.valueOf((long) score) : String.valueOf(score);
	}

	private static BusinessException invalid(String message) {
		return new BusinessException(CommonErrorCode.INVALID_REQUEST, message);
	}
}
