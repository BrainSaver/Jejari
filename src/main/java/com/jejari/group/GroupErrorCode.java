package com.jejari.group;

import org.springframework.http.HttpStatus;

import com.jejari.common.error.ErrorCode;

/** 단체 예매 실패 코드 (concurrency.md §18) */
public enum GroupErrorCode implements ErrorCode {
	GROUP_SIZE_OUT_OF_RANGE(HttpStatus.valueOf(422), "단체 인원은 정해진 범위(기본 11~200명) 안이어야 합니다."),
	GROUP_CONSENT_REQUIRED(HttpStatus.BAD_REQUEST, "탑승자 개인정보 제공 동의가 필요합니다."),
	GROUP_REP_INVALID(HttpStatus.BAD_REQUEST, "대표자는 한 명이고, 휴대폰이 있어야 합니다."),
	GROUP_REP_PHONE_MISMATCH(HttpStatus.FORBIDDEN, "대표자 번호가 본인인증한 번호와 다릅니다."),
	GROUP_DUPLICATE_PHONE(HttpStatus.BAD_REQUEST, "같은 휴대폰 번호가 명단에 두 번 있습니다. 휴대폰이 없는 분은 '휴대폰 없음'으로 체크해 주세요."),
	GROUP_NOT_OPEN(HttpStatus.CONFLICT, "아직 단체 신청을 받지 않는 열차이거나 신청 기간이 지났습니다."),
	GROUP_ALREADY_REQUESTED(HttpStatus.CONFLICT, "이 열차에 처리 중인 단체 신청이 이미 있습니다."),
	GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "단체 신청을 찾을 수 없습니다."),
	GROUP_NOT_REQUESTED(HttpStatus.CONFLICT, "승인 대기 중인 신청이 아닙니다."),
	GROUP_SEAT_SHORT(HttpStatus.CONFLICT, "남은 좌석이 모자라 배정하지 못했습니다.");

	private final HttpStatus status;
	private final String message;

	GroupErrorCode(HttpStatus status, String message) {
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
