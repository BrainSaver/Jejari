package com.jejari.behavior;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 행동 로그 meta 거르기. 로그에 개인정보가 섞이거나 크기 폭탄이 들어가지 않게, 허용한 모양만 통과시킨다.
 *
 * <ul>
 *   <li>키: 영문으로 시작하는 영문·숫자 30자 이내, 최대 {@value #MAX_KEYS}개</li>
 *   <li>값: 숫자(유한), true/false, 코드 문자열(영문·숫자·{@code _ - : .} 64자 이내), 그 목록(최대 {@value #MAX_LIST}개)</li>
 *   <li>숫자만 9자리 이상인 문자열은 버린다 — 휴대폰·카드번호일 수 있다 (ID는 숫자 타입으로 넣는다)</li>
 *   <li>공백·한글·@ 가 들어간 문자열은 버린다 — 이름·이메일·자유 입력이 섞이는 것을 구조적으로 막는다</li>
 * </ul>
 * 걸러진 키는 조용히 빠진다 (요청을 실패시키지 않음). 몇 개 빠졌는지는 {@link Result#dropped()}로 알 수 있다.
 */
public final class BehaviorMeta {

	static final int MAX_KEYS = 20;
	static final int MAX_LIST = 50;
	private static final Pattern KEY = Pattern.compile("^[a-zA-Z][a-zA-Z0-9]{0,29}$");
	private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_\\-:.]{1,64}$");
	private static final Pattern LONG_DIGITS = Pattern.compile("^[0-9]{9,}$");

	private BehaviorMeta() {
	}

	public record Result(Map<String, Object> meta, int dropped) {
	}

	public static Result sanitize(Map<String, ?> raw) {
		Map<String, Object> out = new LinkedHashMap<>();
		int dropped = 0;
		if (raw == null) {
			return new Result(out, 0);
		}
		for (Map.Entry<String, ?> e : raw.entrySet()) {
			String k = e.getKey();
			Object v = value(e.getValue());
			if (k == null || !KEY.matcher(k).matches() || v == null || out.size() >= MAX_KEYS) {
				dropped++;
				continue;
			}
			out.put(k, v);
		}
		return new Result(out, dropped);
	}

	/** 허용하는 값이면 저장할 형태로, 아니면 null */
	static Object value(Object v) {
		if (v instanceof Collection<?> c) {
			if (c.size() > MAX_LIST) {
				return null;
			}
			List<Object> list = new ArrayList<>(c.size());
			for (Object item : c) {
				Object x = scalar(item);
				if (x == null) {
					return null; // 목록에 하나라도 이상한 값이 있으면 통째로 버린다
				}
				list.add(x);
			}
			return list;
		}
		return scalar(v);
	}

	private static Object scalar(Object v) {
		if (v instanceof Boolean b) {
			return b;
		}
		if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
			return ((Number) v).longValue();
		}
		if (v instanceof Double || v instanceof Float) {
			double d = ((Number) v).doubleValue();
			return Double.isFinite(d) ? d : null;
		}
		if (v instanceof Enum<?> en) {
			return scalar(en.name());
		}
		if (v instanceof String s && CODE.matcher(s).matches() && !LONG_DIGITS.matcher(s).matches()) {
			return s;
		}
		return null;
	}
}
