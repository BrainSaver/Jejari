package com.jejari.member.guest;

import org.springframework.http.HttpStatus;

import com.jejari.common.error.ErrorCode;

/** 비회원(게스트) 예매 실패 코드 */
public enum GuestErrorCode implements ErrorCode {
	GUEST_DISABLED(HttpStatus.FORBIDDEN, "지금은 비회원 예매를 쓸 수 없습니다. 로그인해 주세요."),
	GUEST_NOT_ALLOWED(HttpStatus.FORBIDDEN, "비회원은 쓸 수 없는 기능입니다. 회원으로 가입하면 이용할 수 있어요."),
	GUEST_IS_MEMBER(HttpStatus.CONFLICT, "이미 가입된 회원입니다. 로그인해 주세요."),
	GUEST_BUSY(HttpStatus.TOO_MANY_REQUESTS, "같은 본인인증으로 처리 중입니다. 잠시 뒤 다시 시도해 주세요."),
	/** 예약이 없든, 휴대폰이 다르든, 게스트가 아니든 똑같이 이 응답 (존재 여부를 알려 주지 않는다) */
	GUEST_LOOKUP_NOT_FOUND(HttpStatus.NOT_FOUND, "예약 번호나 휴대폰 번호가 맞지 않습니다.");

	private final HttpStatus status;
	private final String message;

	GuestErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	@Override
	public String code() {
		return name();
	}

	@Override
	public HttpStatus status() {
		return status;
	}

	@Override
	public String message() {
		return message;
	}
}
