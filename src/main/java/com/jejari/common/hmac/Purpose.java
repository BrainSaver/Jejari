package com.jejari.common.hmac;

/**
 * 가명처리하는 값의 종류. 이름이 HMAC 입력에 함께 들어가므로 같은 문자열이라도 종류가 다르면 해시가 다르다.
 * (예: 어떤 값이 우연히 전화번호이자 기기 지문이어도 두 해시를 맞대어 사람을 잇지 못한다.)
 *
 * 이름을 바꾸거나 순서를 고치면 이미 저장된 모든 해시가 틀어진다. 새 종류는 끝에 추가만 한다.
 */
public enum Purpose {
	/** 모의 본인인증 CI → member.ci_hash, identity_verification.ci_hash, behavior_log.ci_hash */
	CI,
	/** 휴대폰 번호 → member.phone_hash */
	PHONE,
	/** 이메일 → login_attempt.email_hash */
	EMAIL,
	/** 카드·계좌 번호 → payment_method.method_hash */
	PAYMENT,
	/** 기기 지문 → member_device.device_hash, auth_session.device_hash */
	DEVICE,
	/** IP 주소 → auth_session.ip_hash, login_attempt.ip_hash, rate:login:{ipHash} */
	IP
}
