package com.jejari.common.policy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 정책값(app_policy)을 읽는 모듈이 "이 키는 이런 값만 받는다"를 알려 주는 창구.
 *
 * <p>모듈은 범위 밖 값을 만나면 기본값으로 돌아가거나(인증·행동 로그) 예외가 나므로(대기열·AppPolicy), 관리자 화면(ADM-08)이
 * 그런 값을 저장하면 관리자는 바뀐 줄 알지만 실제로는 그대로이거나 기능이 멈춘다 (2026-11-19 공용 서버에서 실제로 겪음).
 * 그래서 관리자 API는 저장 전에 여기 적힌 기준으로 거절한다. 기준의 원본은 각 모듈이 읽을 때 쓰는 값과 같아야 한다.
 *
 * <p>네 가지를 알려 줄 수 있고, 필요한 것만 구현하면 된다.
 * <ul>
 *   <li>{@link #bounds()} 정수 범위</li>
 *   <li>{@link #decimalBounds()} 소수 범위 (예: 비율 0~1)</li>
 *   <li>{@link #allowedValues()} 문자열 허용값 (예: MAX만)</li>
 *   <li>{@link #rules()} 여러 키를 함께 보는 조건 (예: 재확인 주기 × 3 ≤ 결과 모름 최대 시간)</li>
 * </ul>
 * 같은 키를 두 모듈이 알려 주면 관리자 API는 둘 다 만족해야 받는다 (범위는 겹치는 구간).
 */
public interface PolicyBounds {

	/** 정수만, min 이상 max 이하 */
	record Range(long min, long max) {
	}

	/** 소수 가능, min 이상 max 이하 */
	record DecimalRange(BigDecimal min, BigDecimal max) {
		public static DecimalRange of(String min, String max) {
			return new DecimalRange(new BigDecimal(min), new BigDecimal(max));
		}
	}

	/** 바꾼 뒤의 값들. 저장된 값에 이번에 바꿀 키 하나를 덮어쓴 것 */
	interface Values {
		/** 숫자 값. 키가 없거나 숫자가 아니면 그 조건은 검사하지 않는다 ({@link MissingValue}) */
		BigDecimal num(String key);

		String str(String key);
	}

	/** 조건이 보는 키가 아직 app_policy에 없을 때. 관리자 API는 그 조건을 건너뛴다 */
	final class MissingValue extends RuntimeException {
		public MissingValue(String key) {
			super(key, null, false, false);
		}
	}

	/**
	 * 여러 키를 함께 보는 조건. {@code keys} 중 하나를 바꿀 때만 검사한다.
	 * @param message 어긋났을 때 관리자에게 보여 줄 문장 (무엇을 어떻게 맞추면 되는지)
	 */
	record Rule(Set<String> keys, Predicate<Values> ok, String message) {
	}

	/** 키 → 정수 범위 */
	Map<String, Range> bounds();

	/** 키 → 소수 범위 */
	default Map<String, DecimalRange> decimalBounds() {
		return Map.of();
	}

	/** 키 → 허용 문자열 (대소문자 구분) */
	default Map<String, Set<String>> allowedValues() {
		return Map.of();
	}

	default List<Rule> rules() {
		return List.of();
	}
}
