package com.jejari.common.web;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;

import jakarta.servlet.http.HttpServletRequest;

/**
 * User-Agent 정리 한 곳. 회원·비회원·관리자 로그인과 행동 로그가 같은 규칙을 쓴다.
 *
 * <ul>
 *   <li>제어 문자 제거 — 줄바꿈을 넣어 로그 줄을 위조하지 못하게</li>
 *   <li><b>UTF-8 바이트 기준</b> {@value #MAX_BYTES}바이트로 자름 — {@code auth_session.user_agent}가 VARCHAR2(300) BYTE라
 *       글자 수로 자르면 한글·이모지 200자(400바이트)에서 ORA-12899로 로그인이 500이 됐다 (보안 점검 2026-11-25 §2.3).
 *       글자 중간(서로게이트 쌍·멀티바이트)을 끊지 않는다.</li>
 * </ul>
 */
public final class UserAgents {

	public static final int MAX_BYTES = 300;

	private UserAgents() {
	}

	public static String from(HttpServletRequest request) {
		return clean(request.getHeader(HttpHeaders.USER_AGENT));
	}

	/** 제어 문자를 지우고 {@value #MAX_BYTES}바이트로 자른다. null이면 null */
	public static String clean(String ua) {
		if (ua == null) {
			return null;
		}
		return truncateUtf8(ua.replaceAll("\\p{Cntrl}", ""), MAX_BYTES);
	}

	/** UTF-8로 maxBytes 이하가 되도록 코드 포인트 단위로 자른다 */
	public static String truncateUtf8(String s, int maxBytes) {
		if (s == null || s.length() * 3 <= maxBytes || s.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
			return s;
		}
		int bytes = 0;
		int i = 0;
		while (i < s.length()) {
			int cp = s.codePointAt(i);
			int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
			if (bytes + len > maxBytes) {
				break;
			}
			bytes += len;
			i += Character.charCount(cp);
		}
		return s.substring(0, i);
	}
}
