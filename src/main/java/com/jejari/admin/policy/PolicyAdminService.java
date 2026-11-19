package com.jejari.admin.policy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.common.error.ErrorCode;
import com.jejari.common.policy.PolicyBounds;
import com.jejari.common.policy.PolicyBounds.Range;

/**
 * ADM-08 운영 설정 (화면 A-07). {@code app_policy} 값을 보고 바꾼다.
 *
 * <ul>
 *   <li><b>낙관적 잠금</b>: 화면이 읽은 {@code version}을 그대로 보내고, {@code WHERE version = ?} 조건부 UPDATE가 0행이면
 *       다른 관리자가 먼저 바꾼 것이라 409로 거절한다 (concurrency.md §0.3, {@code version = version + 1}).</li>
 *   <li><b>이력</b>: 같은 트랜잭션에서 {@code app_policy_history}에 이전 값 → 새 값을 남긴다 (INSERT만 하는 표).</li>
 *   <li><b>반영</b>: 앱은 Redis {@code policy:cache} → Oracle 순으로 읽는다. 그래서 커밋 뒤 캐시 해시도 같은 값으로 바꾼다.
 *       캐시를 먼저 바꾸면 롤백됐을 때 DB와 어긋난다. 각 앱의 메모리 캐시(최대 1초) 뒤 모든 대에 반영된다.</li>
 *   <li><b>범위</b>: 값을 읽는 모듈이 {@link PolicyBounds}로 알려 준 범위 밖이면 저장하지 않는다. 모듈은 범위 밖 값을
 *       조용히 기본값으로 바꾸므로, 저장해 버리면 관리자는 바뀐 줄 알지만 실제로는 그대로다.</li>
 * </ul>
 */
@Service
public class PolicyAdminService {

	private static final Logger log = LoggerFactory.getLogger(PolicyAdminService.class);
	static final String CACHE = "policy:cache";
	static final int HISTORY_RETRY = 3;
	private static final Pattern NUMBER = Pattern.compile("-?\\d{1,15}(\\.\\d{1,6})?");
	private static final Pattern STRING = Pattern.compile("[\\p{L}\\p{N} ._,=:/+-]{1,200}");

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final StringRedisTemplate redis;
	private final Map<String, Range> bounds;

	@Autowired
	public PolicyAdminService(JdbcTemplate jdbc, TransactionTemplate tx, StringRedisTemplate redis,
			ObjectProvider<PolicyBounds> modules) {
		this(jdbc, tx, redis, modules.orderedStream().toList());
	}

	PolicyAdminService(JdbcTemplate jdbc, TransactionTemplate tx, StringRedisTemplate redis, List<PolicyBounds> modules) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.redis = redis;
		Map<String, Range> all = new HashMap<>();
		for (PolicyBounds m : modules) {
			all.putAll(m.bounds());
		}
		this.bounds = Map.copyOf(all);
	}

	/**
	 * @param cachedValue Redis {@code policy:cache}에 들어 있는 값. DB와 다르면 화면에 표시한다 (개발용 PUT /dev/policy로 바꾼 경우 등)
	 */
	public record Policy(String key, String group, String value, String cachedValue, long version, String valueType,
			String description, Long updatedBy, LocalDateTime updatedAt) {
	}

	public record History(long historyId, String oldValue, String newValue, long version, Long changedBy,
			LocalDateTime changedAt) {
	}

	public record UpdateCommand(String value, Long version) {
	}

	public List<Policy> list() {
		Map<Object, Object> cached = cachedValues();
		return jdbc.query("""
				SELECT policy_key, policy_value, version, value_type, description, updated_by, updated_at
				  FROM app_policy ORDER BY policy_key""", (rs, i) -> {
			String key = rs.getString(1);
			long by = rs.getLong(6);
			Object c = cached.get(key);
			return new Policy(key, key.contains(".") ? key.substring(0, key.indexOf('.')) : key, rs.getString(2),
					c == null ? null : c.toString(), rs.getLong(3), rs.getString(4), rs.getString(5),
					rs.wasNull() ? null : by, rs.getObject(7, LocalDateTime.class));
		});
	}

	public Policy get(String key) {
		return list().stream().filter(p -> p.key().equals(key)).findFirst()
				.orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND, "정책 키가 없습니다: " + key));
	}

	public List<History> history(String key) {
		return jdbc.query("""
				SELECT history_id, old_value, new_value, version, changed_by, changed_at
				  FROM app_policy_history WHERE policy_key = ? ORDER BY history_id DESC FETCH FIRST 20 ROWS ONLY""",
				(rs, i) -> {
					long by = rs.getLong(5);
					return new History(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
							rs.wasNull() ? null : by, rs.getObject(6, LocalDateTime.class));
				}, key);
	}

	public Policy update(long adminId, String key, UpdateCommand c) {
		Policy cur = get(key);
		if (c.version() == null) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "읽은 버전(version)을 같이 보내 주세요.");
		}
		String value = c.value() == null ? "" : c.value().trim();
		check(cur.valueType(), value);
		checkRange(key, value, bounds.get(key));
		tx.executeWithoutResult(s -> {
			int n = jdbc.update("""
					UPDATE app_policy SET policy_value = ?, version = version + 1, updated_by = ?, updated_at = SYSTIMESTAMP
					 WHERE policy_key = ? AND version = ?""", value, adminId, key, c.version());
			if (n == 0) {
				throw new BusinessException(PolicyErrorCode.POLICY_VERSION_CONFLICT);
			}
			insertHistory(key, cur.value(), value, c.version() + 1, adminId);
		});
		applyToCache(key);
		return get(key);
	}

	/**
	 * 이력 한 줄. 이력 표에는 시퀀스가 없어 {@code MAX + 1}로 번호를 정한다. 다른 키를 동시에 바꾸면 번호가 겹칠 수 있어
	 * 겹치면 다음 번호로 다시 넣는다 (Oracle은 실패한 문장만 되돌리므로 트랜잭션의 UPDATE는 그대로 남는다).
	 */
	private void insertHistory(String key, String oldValue, String newValue, long version, long adminId) {
		for (int attempt = 1; ; attempt++) {
			Long next = jdbc.queryForObject("SELECT NVL(MAX(history_id), 0) + 1 FROM app_policy_history", Long.class);
			try {
				jdbc.update("""
						INSERT INTO app_policy_history (history_id, policy_key, old_value, new_value, version, changed_by, changed_at)
						VALUES (?, ?, ?, ?, ?, ?, SYSTIMESTAMP)""", next, key, oldValue, newValue, version, adminId);
				return;
			} catch (DuplicateKeyException e) {
				if (attempt >= HISTORY_RETRY) {
					throw e;
				}
			}
		}
	}

	/**
	 * 커밋 뒤 Redis {@code policy:cache}에 반영한다. 앱들은 Redis를 먼저 읽고, 일부(AuthPolicy·BehaviorPolicy)는 Redis에 없으면 DB가 아니라
	 * 기본값을 쓰므로 "키를 지워 DB를 읽게" 하는 방식은 쓸 수 없다 → 반드시 넣어야 한다.
	 *
	 * <ul>
	 *   <li>넣는 값은 방금 커밋된 DB 값을 다시 읽은 것: 두 관리자가 연달아 바꿀 때 늦게 끝난 쪽의 옛 값이 캐시에 남지 않게</li>
	 *   <li>Redis가 실패하면 DB는 이미 바뀌었는데 앱들은 옛 값을 계속 쓴다. 조용한 500 대신 그 사실을 그대로 알린다 (503).
	 *       목록 화면도 DB 값과 캐시 값이 다르면 표시하므로, Redis가 돌아온 뒤 같은 값을 다시 저장하면 맞춰진다</li>
	 * </ul>
	 */
	private void applyToCache(String key) {
		String committed = jdbc.queryForObject("SELECT policy_value FROM app_policy WHERE policy_key = ?", String.class, key);
		try {
			redis.opsForHash().put(CACHE, key, committed);
		} catch (RuntimeException e) {
			log.warn("운영 설정 {}: DB에는 저장했지만 Redis 캐시 반영 실패 → 앱들은 옛 값을 계속 씀: {}", key, e.toString());
			throw new BusinessException(PolicyErrorCode.POLICY_CACHE_NOT_APPLIED);
		}
	}

	static void check(String type, String value) {
		boolean ok = switch (type) {
			case "NUMBER" -> NUMBER.matcher(value).matches();
			case "BOOL" -> value.equals("Y") || value.equals("N") || value.equals("true") || value.equals("false");
			default -> STRING.matcher(value).matches();
		};
		if (!ok) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
					"값 형식이 맞지 않습니다 (" + type + (type.equals("NUMBER") ? ": 숫자" : "") + ").");
		}
		if (type.equals("NUMBER") && new BigDecimal(value).signum() < 0) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, "음수는 넣을 수 없습니다.");
		}
	}

	/** 모듈이 알려 준 범위 밖이면 거절. 모듈은 정수로 읽으므로 소수도 거절한다 */
	static void checkRange(String key, String value, Range r) {
		if (r == null) {
			return;
		}
		long n;
		try {
			n = Long.parseLong(value);
		} catch (NumberFormatException e) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST, key + "는 정수만 넣을 수 있습니다 (" + r.min() + "~" + r.max() + ").");
		}
		if (n < r.min() || n > r.max()) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
					key + "는 " + r.min() + "~" + r.max() + " 사이만 넣을 수 있습니다. 범위 밖 값은 앱이 무시하고 기본값을 씁니다.");
		}
	}

	private Map<Object, Object> cachedValues() {
		try {
			return redis.opsForHash().entries(CACHE);
		} catch (RuntimeException e) {
			return new HashMap<>(); // Redis가 없어도 DB 값은 보여 준다
		}
	}

	enum PolicyErrorCode implements ErrorCode {
		POLICY_VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 관리자가 먼저 바꿨습니다. 다시 읽은 뒤 바꿔 주세요."),
		/** DB는 바뀌었고 Redis 캐시만 실패. 앱들은 옛 값을 쓰는 중 */
		POLICY_CACHE_NOT_APPLIED(HttpStatus.SERVICE_UNAVAILABLE,
				"DB에는 저장했지만 서버들에 반영하지 못했습니다 (캐시 오류). 잠시 뒤 같은 값을 다시 저장해 주세요.");

		private final HttpStatus status;
		private final String message;

		PolicyErrorCode(HttpStatus status, String message) {
			this.status = status;
			this.message = message;
		}

		@Override
		public String code() {
			return name();
		}

		@Override
		public HttpStatus status() {
			return status;
		}

		@Override
		public String message() {
			return message;
		}
	}
}
