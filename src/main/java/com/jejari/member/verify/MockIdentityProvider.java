package com.jejari.member.verify;

import java.text.Normalizer;
import java.time.Clock;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.jejari.auth.AuthErrorCode;
import com.jejari.common.error.BusinessException;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.member.MemberAuthEnabled;

/**
 * 모의 본인인증 (AUTH-02). 실제 본인인증(PASS 등)으로 바꿀 때 이 클래스만 교체한다.
 *
 * <p>이름 + 휴대폰으로 항상 같은 가짜 CI 해시를 만든다 ({@link Pseudonymizer#mockCiHash}). 같은 사람이 계정을 여러 개
 * 만들어도 같은 CI로 묶여 매수 제한·위험 점수가 합산된다. CI 원문은 만들지도 저장하지도 않는다.
 *
 * <p>실패 재현(화면·테스트용): 휴대폰 번호가 0000으로 끝나면 인증 실패.
 */
@Component
@MemberAuthEnabled
public class MockIdentityProvider {

	/** 글자(모든 언어)·공백·가운뎃점·하이픈·아포스트로피만. 숫자·기호·제어 문자 금지 */
	private static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{M}][\\p{L}\\p{M} ·.'\\-]{0,48}[\\p{L}\\p{M}.]$|^[\\p{L}]$");
	private static final Pattern PHONE_CHARS = Pattern.compile("^[0-9 ()+\\-]{9,20}$");
	/** 010은 뒤 8자리만, 예전 번호(011·016~019)는 7~8자리 */
	private static final Pattern KR_MOBILE = Pattern.compile("^(010[0-9]{8}|01[16789][0-9]{7,8})$");

	private final Pseudonymizer pseudonymizer;
	private final Clock clock;

	public MockIdentityProvider(Pseudonymizer pseudonymizer, Clock clock) {
		this.pseudonymizer = pseudonymizer;
		this.clock = clock;
	}

	public VerifiedIdentity verify(String name, String phone, Provider provider) {
		if (provider == null) {
			throw new BusinessException(AuthErrorCode.AUTH_VERIFY_FAILED);
		}
		String n = normalizeName(name);
		String p = normalizePhone(phone);
		if (p.endsWith("0000")) {
			throw new BusinessException(AuthErrorCode.AUTH_VERIFY_FAILED);
		}
		return new VerifiedIdentity(pseudonymizer.mockCiHash(n, p), n, p, provider, clock.instant());
	}

	/** 앞뒤 공백 제거, 연속 공백 하나로, 유니코드 NFC. 2~50자 글자만 */
	static String normalizeName(String name) {
		if (name == null) {
			throw new BusinessException(AuthErrorCode.AUTH_VERIFY_FAILED);
		}
		String n = Normalizer.normalize(name, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
		int len = n.codePointCount(0, n.length());
		if (len < 2 || len > 50 || !NAME.matcher(n).matches()) {
			throw new BusinessException(AuthErrorCode.AUTH_VERIFY_FAILED);
		}
		return n;
	}

	/** 숫자만 남기고 +82는 0으로. 한국 휴대폰(010·011·016~019) 형식만 */
	static String normalizePhone(String phone) {
		if (phone == null || !PHONE_CHARS.matcher(phone.strip()).matches()) {
			throw new BusinessException(AuthErrorCode.AUTH_VERIFY_FAILED);
		}
		String digits = phone.replaceAll("[^0-9]", "");
		if (digits.startsWith("82") && digits.length() >= 11 && digits.length() <= 12) {
			digits = "0" + digits.substring(2);
		}
		if (!KR_MOBILE.matcher(digits).matches()) {
			throw new BusinessException(AuthErrorCode.AUTH_VERIFY_FAILED);
		}
		return digits;
	}

	/** identity_verification.provider */
	public enum Provider { MOCK_PASS, MOCK_PHONE }
}
