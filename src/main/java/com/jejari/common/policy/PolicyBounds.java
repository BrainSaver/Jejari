package com.jejari.common.policy;

import java.util.Map;

/**
 * 정책값(app_policy)을 읽는 모듈이 "이 키는 이 범위만 받는다"를 알려 주는 창구.
 *
 * <p>모듈은 범위 밖 값을 만나면 기본값으로 돌아가므로, 관리자 화면(ADM-08)이 그런 값을 저장하면 관리자는 바뀐 줄 알지만
 * 실제로는 그대로다 (2026-11-19 공용 서버에서 실제로 겪음). 그래서 관리자 API는 저장 전에 이 범위로 거절한다.
 * 범위의 원본은 각 모듈이 읽을 때 쓰는 값과 같은 상수여야 한다.
 */
public interface PolicyBounds {

	/** 정수만, min 이상 max 이하 */
	record Range(long min, long max) {
	}

	/** 키 → 범위 */
	Map<String, Range> bounds();
}
