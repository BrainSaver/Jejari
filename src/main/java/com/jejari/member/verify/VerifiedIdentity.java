package com.jejari.member.verify;

import java.time.Instant;

import com.jejari.member.verify.MockIdentityProvider.Provider;

/**
 * 본인인증 결과. 이름·휴대폰 원문이 들어 있으므로 로그에 찍지 않는다 (toString에서 가림).
 *
 * @param ciHash     HMAC(가짜 CI). 계정 묶음 키
 * @param name       정규화한 이름
 * @param phone      숫자만 남긴 휴대폰 번호
 * @param verifiedAt 인증 시각
 */
public record VerifiedIdentity(String ciHash, String name, String phone, Provider provider, Instant verifiedAt) {

	@Override
	public String toString() {
		return "VerifiedIdentity[provider=" + provider + ", verifiedAt=" + verifiedAt + "]";
	}
}
