package com.jejari.auth;

import org.springframework.http.HttpStatus;

import com.jejari.common.error.ErrorCode;

/**
 * 회원·인증 실패 코드 (기능설계서 9장).
 * 로그인 검증(AUTH-03, 강다인)과 토큰(AUTH-04, 조영준)이 함께 씁니다.
 */
public enum AuthErrorCode implements ErrorCode {

	AUTH_EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
	AUTH_VERIFY_FAILED(HttpStatus.BAD_REQUEST, "본인인증에 실패했습니다."),
	/** 가입 비밀번호 규칙 위반. 어떤 규칙인지는 메시지로 알려 주고, 입력값은 싣지 않습니다. */
	AUTH_PASSWORD_POLICY(HttpStatus.BAD_REQUEST, "비밀번호가 규칙에 맞지 않습니다."),
	/** 계정 존재 여부를 알려주지 않기 위해 아이디·비밀번호 중 무엇이 틀렸는지 구분하지 않습니다. */
	AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
	AUTH_LOCKED(HttpStatus.LOCKED, "로그인 시도가 많아 잠시 잠겼습니다. 잠시 후 다시 시도해 주세요."),
	AUTH_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요."),
	/** 로그인 대기실이 가득 참 (LoginGate). Retry-After 헤더의 초만큼 뒤 자동으로 다시 */
	AUTH_LOGIN_BUSY(HttpStatus.TOO_MANY_REQUESTS, "로그인하는 사람이 많아 잠시 기다려 주세요. 곧 자동으로 다시 시도합니다."),
	AUTH_BANNED(HttpStatus.FORBIDDEN, "이용이 정지된 계정입니다."),

	/** 액세스 토큰 만료. 클라이언트는 /auth/refresh로 재발급 후 다시 요청합니다. */
	AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다."),
	/** 서명 불일치·형식 오류 등 위조·손상된 액세스 토큰. 재발급하지 말고 다시 로그인합니다. */
	AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증 정보입니다."),
	/** 리프레시 토큰 없음·만료·폐기·재사용. 다시 로그인해야 합니다. */
	AUTH_REFRESH_INVALID(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."),

	ADMIN_FORBIDDEN(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");

	private final HttpStatus status;
	private final String message;

	AuthErrorCode(HttpStatus status, String message) {
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
