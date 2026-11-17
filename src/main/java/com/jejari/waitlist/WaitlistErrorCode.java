package com.jejari.waitlist;

import org.springframework.http.HttpStatus;

import com.jejari.common.error.ErrorCode;

/** 취소표 대기 실패 코드 (기능설계서 9장, WL-01·02). */
public enum WaitlistErrorCode implements ErrorCode {

	WAITLIST_DUPLICATED(HttpStatus.CONFLICT, "이미 이 열차의 취소표를 기다리고 있습니다."),
	WAITLIST_LIMIT(HttpStatus.valueOf(422), "동시에 기다릴 수 있는 건수를 넘었습니다."),
	/** 아직 살 수 있는 좌석이 있으면 대기가 아니라 바로 예매해야 한다 */
	WAITLIST_NOT_SOLD_OUT(HttpStatus.CONFLICT, "아직 남은 좌석이 있습니다. 좌석을 바로 선택해 주세요."),
	/**
	 * 취소표는 대기열 입장 토큰 없이 신청한다. 막지 않으면 위험 점수로 대기열 뒤로 밀린 회원이
	 * 취소표로 우회하는 길이 된다 (concurrency.md §9.3-0).
	 */
	WAITLIST_RESTRICTED(HttpStatus.FORBIDDEN, "이용이 제한된 계정은 취소표를 신청할 수 없습니다."),
	WAITLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "대기 신청을 찾을 수 없습니다."),
	WAITLIST_NOT_WAITING(HttpStatus.CONFLICT, "이미 제안받았거나 끝난 대기입니다."),
	/** 회원 정보를 읽을 수 없으면 위험 점수를 계산할 수 없다. 순서가 틀리느니 받지 않는다 */
	WAITLIST_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "취소표 대기를 잠시 이용할 수 없습니다.");

	private final HttpStatus status;
	private final String message;

	WaitlistErrorCode(HttpStatus status, String message) {
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
