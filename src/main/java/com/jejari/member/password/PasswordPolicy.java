package com.jejari.member.password;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import com.jejari.auth.AuthErrorCode;
import com.jejari.common.error.BusinessException;

/**
 * 가입 비밀번호 규칙 (NIST SP 800-63B 방식: 길이를 길게, 조합 강제 대신 흔한·추측 쉬운 비밀번호 차단).
 *
 * <ul>
 *   <li>10~64자, UTF-8 256바이트 이하 (해시 전 입력 폭탄 방지)</li>
 *   <li>제어 문자 금지, 앞뒤 공백 금지 (복사·붙여넣기 실수로 다른 비밀번호가 되는 것 방지)</li>
 *   <li>서로 다른 문자 4종 이상 (aaaaaaaaaa, 1212121212 차단)</li>
 *   <li>흔한 비밀번호 목록, 키보드·숫자 연속 패턴 차단</li>
 *   <li>이메일 아이디 부분을 포함하면 차단</li>
 * </ul>
 * 어떤 규칙에 걸렸는지는 응답에 알려 주되, 입력값은 메시지·로그에 싣지 않는다.
 */
public final class PasswordPolicy {

	public static final int MIN_LENGTH = 10;
	public static final int MAX_LENGTH = 64;
	public static final int MAX_BYTES = 256;

	/** 유출 사전 상위권과 서비스 이름 변형. 소문자로 비교한다 */
	private static final Set<String> COMMON = Set.of(
			"1234567890", "12345678910", "0123456789", "1111111111", "0000000000", "1q2w3e4r5t", "1q2w3e4r!@",
			"qwertyuiop", "qwerty1234", "qwer1234!@", "asdfghjkl;", "zxcvbnm123", "password12", "password123",
			"password1!", "passw0rd!!", "p@ssw0rd12", "iloveyou12", "abcd123456", "abc1234567", "a123456789",
			"q1w2e3r4t5", "admin12345", "letmein123", "welcome123", "sunshine12", "football12", "baseball12",
			"princess12", "dragon1234", "master1234", "monkey1234", "superman12", "trustno1!!", "1234qwer!@",
			"jejari1234", "jejari12345", "jejari123!", "korail1234", "korail123!", "letskorail", "train12345",
			"ktx1234567", "ticket1234", "rail123456");

	private static final String[] SEQUENCES = {
			"01234567890", "09876543210", "abcdefghijklmnopqrstuvwxyz", "zyxwvutsrqponmlkjihgfedcba",
			"qwertyuiop", "poiuytrewq", "asdfghjkl", "lkjhgfdsa", "zxcvbnm", "mnbvcxz", "1q2w3e4r5t6y7u8i9o0p"};

	private PasswordPolicy() {
	}

	/** @param email 정규화된 이메일 (포함 여부 검사용) */
	public static void validate(String password, String email) {
		if (password == null) {
			fail("비밀번호를 입력해 주세요.");
		}
		int length = password.codePointCount(0, password.length());
		if (length < MIN_LENGTH || length > MAX_LENGTH
				|| password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
			fail("비밀번호는 " + MIN_LENGTH + "~" + MAX_LENGTH + "자로 입력해 주세요.");
		}
		if (password.codePoints().anyMatch(Character::isISOControl)) {
			fail("비밀번호에 쓸 수 없는 문자가 있습니다.");
		}
		if (!password.equals(password.strip())) {
			fail("비밀번호 앞뒤에 공백을 넣을 수 없습니다.");
		}
		if (password.codePoints().distinct().count() < 4) {
			fail("같은 문자를 반복한 비밀번호는 쓸 수 없습니다.");
		}
		String lower = password.toLowerCase(Locale.ROOT);
		if (COMMON.contains(lower) || isSequence(lower)) {
			fail("너무 흔하거나 추측하기 쉬운 비밀번호입니다.");
		}
		if (email != null) {
			int at = email.indexOf('@');
			String local = at > 0 ? email.substring(0, at).toLowerCase(Locale.ROOT) : "";
			if (lower.equals(email.toLowerCase(Locale.ROOT)) || (local.length() >= 4 && lower.contains(local))) {
				fail("비밀번호에 이메일 아이디를 넣을 수 없습니다.");
			}
		}
	}

	/**
	 * 연속 패턴(6자 이상)을 빼고 나면 남는 게 4자 이하인 경우. 예: zxcvbnm123!, abcdef1234, qwerty!!!!
	 * 연속 패턴을 여러 번 이어 붙인 것도 잡는다 (1234567890qwerty).
	 */
	private static boolean isSequence(String lower) {
		String rest = lower;
		boolean removed = true;
		while (removed) {
			removed = false;
			for (String seq : SEQUENCES) {
				String longest = longestCommon(rest, seq);
				if (longest.length() >= 6) {
					rest = rest.replace(longest, "");
					removed = true;
				}
			}
		}
		return rest.length() < lower.length() && rest.codePointCount(0, rest.length()) <= 4;
	}

	/** a 안에서 b의 부분 문자열로 가장 긴 것 */
	private static String longestCommon(String a, String b) {
		String best = "";
		for (int i = 0; i < a.length(); i++) {
			for (int j = a.length(); j > i + best.length(); j--) {
				String sub = a.substring(i, j);
				if (b.contains(sub)) {
					best = sub;
					break;
				}
			}
		}
		return best;
	}

	private static void fail(String message) {
		throw new BusinessException(AuthErrorCode.AUTH_PASSWORD_POLICY, message);
	}
}
