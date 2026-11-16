package com.jejari.auth.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthErrorCode;
import com.jejari.auth.AuthPrincipal;
import com.jejari.auth.session.AuthTokenService;
import com.jejari.auth.web.TokenResponses.TokenResponse;
import com.jejari.common.error.BusinessException;

/** AUTH-04 토큰 재발급·로그아웃. 로그인(AUTH-03)은 회원 기능 쪽 컨트롤러가 AuthTokenService.issue를 호출합니다. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthTokenController {

	private final AuthTokenService authTokenService;
	private final TokenResponses tokenResponses;

	public AuthTokenController(AuthTokenService authTokenService, TokenResponses tokenResponses) {
		this.authTokenService = authTokenService;
		this.tokenResponses = tokenResponses;
	}

	/** 리프레시 쿠키로 새 액세스 토큰 + 새 리프레시 쿠키 발급. */
	@PostMapping("/refresh")
	public ResponseEntity<TokenResponse> refresh(
			@CookieValue(name = TokenResponses.REFRESH_COOKIE, required = false) String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new BusinessException(AuthErrorCode.AUTH_REFRESH_INVALID);
		}
		return tokenResponses.ok(authTokenService.refresh(refreshToken));
	}

	/** 현재 세션 로그아웃 + 쿠키 삭제. */
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthPrincipal principal) {
		authTokenService.logout(principal);
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, tokenResponses.clearCookie())
				.build();
	}
}
