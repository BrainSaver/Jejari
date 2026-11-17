package com.jejari.risk.batch;

import java.util.regex.Pattern;

import com.jejari.risk.SubjectType;

/**
 * 점수를 매길 대상 하나 (CI·결제 수단·기기 해시). Redis 집합에는 {@code "CI:<해시>"} 모양으로 들어간다.
 *
 * <p>해시는 소문자 16진수 64자(HMAC)만 받는다. 원문 개인정보나 이상한 문자열이 집합에 섞여 배치가 엉뚱한 값을
 * 쿼리하는 일을 막는다.
 */
public record RiskSubject(SubjectType type, String hash) {

	private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");

	public RiskSubject {
		if (type == null || hash == null || !HASH.matcher(hash).matches()) {
			throw new IllegalArgumentException("대상은 유형과 소문자 16진수 64자 해시가 있어야 합니다");
		}
	}

	/** 해시가 없거나(비로그인 등) 형식이 틀리면 null */
	public static RiskSubject ofNullable(SubjectType type, String hash) {
		return hash != null && HASH.matcher(hash).matches() ? new RiskSubject(type, hash) : null;
	}

	/** Redis 집합 원소 → 대상. 형식이 틀리면 null */
	public static RiskSubject parse(String member) {
		if (member == null) {
			return null;
		}
		int i = member.indexOf(':');
		if (i <= 0) {
			return null;
		}
		SubjectType t;
		try {
			t = SubjectType.valueOf(member.substring(0, i));
		} catch (IllegalArgumentException e) {
			return null;
		}
		return ofNullable(t, member.substring(i + 1));
	}

	public String member() {
		return type.name() + ":" + hash;
	}
}
