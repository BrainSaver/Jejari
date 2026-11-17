package com.jejari.admin.policy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.common.error.ErrorCode;

/**
 * ADM-08 운영 설정 (화면 A-07). {@code app_policy} 값을 보고 바꾼다.
 *
 * <ul>
 *   <li><b>낙관적 잠금</b>: 화면이 읽은 {@code version}을 그대로 보내고, {@code WHERE version = ?} 조건부 UPDATE가 0행이면
 *       다른 관리자가 먼저 바꾼 것이라 409로 거절한다 (concurrency.md §0.3, {@code version = version + 1}).</li>
 *   <li><b>이력</b>: 같은 트랜잭션에서 {@code app_policy_history}에 이전 값 → 새 값을 남긴다 (INSERT만 하는 표).</li>
 *   <li><b>반영</b>: 앱은 Redis {@code policy:cache} → Oracle 순으로 읽는다. 그래서 커밋 뒤 캐시 해시도 같은 값으로 바꾼다.
 *       캐시를 먼저 바꾸면 롤백됐을 때 DB와 어긋난다. 각 앱의 메모리 캐시(최대 1초) 뒤 모든 대에 반영된다.</li>
 * </ul>
 */
@Service
public class PolicyAdminService {

	static final String CACHE = "policy:cache";
	private static final Pattern NUMBER = Pattern.compile("-?\\d{1,15}(\\.\\d{1,6})?");
	private static final Pattern STRING = Pattern.compile("[\\p{L}\\p{N} ._,=:/+-]{1,200}");

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final StringRedisTemplate redis;

	public PolicyAdminService(JdbcTemplate jdbc, TransactionTemplate tx, StringRedisTemplate redis) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.redis = redis;
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
		tx.executeWithoutResult(s -> {
			int n = jdbc.update("""
					UPDATE app_policy SET policy_value = ?, version = version + 1, updated_by = ?, updated_at = SYSTIMESTAMP
					 WHERE policy_key = ? AND version = ?""", value, adminId, key, c.version());
			if (n == 0) {
				throw new BusinessException(PolicyErrorCode.POLICY_VERSION_CONFLICT);
			}
			// 이력 표에는 시퀀스가 없다. 관리자 화면이라 경합이 드물고, 겹치면 PK 오류로 트랜잭션 전체가 롤백된다
			Long next = jdbc.queryForObject("SELECT NVL(MAX(history_id), 0) + 1 FROM app_policy_history", Long.class);
			jdbc.update("""
					INSERT INTO app_policy_history (history_id, policy_key, old_value, new_value, version, changed_by, changed_at)
					VALUES (?, ?, ?, ?, ?, ?, SYSTIMESTAMP)""", next, key, cur.value(), value, c.version() + 1, adminId);
		});
		redis.opsForHash().put(CACHE, key, value); // 커밋 뒤에만
		return get(key);
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

	private Map<Object, Object> cachedValues() {
		try {
			return redis.opsForHash().entries(CACHE);
		} catch (RuntimeException e) {
			return new HashMap<>(); // Redis가 없어도 DB 값은 보여 준다
		}
	}

	enum PolicyErrorCode implements ErrorCode {
		POLICY_VERSION_CONFLICT;

		@Override
		public String code() {
			return name();
		}

		@Override
		public HttpStatus status() {
			return HttpStatus.CONFLICT;
		}

		@Override
		public String message() {
			return "다른 관리자가 먼저 바꿨습니다. 다시 읽은 뒤 바꿔 주세요.";
		}
	}
}
