package com.jejari.auth.admin;

import com.jejari.common.error.BusinessException;
import com.jejari.auth.session.AuthTokenService;
import com.jejari.auth.AuthErrorCode;
import com.jejari.auth.ActorType;
import org.springframework.web.bind.annotation.CookieValue;
import jakarta.servlet.http.HttpServletRequest;

import com.jejari.common.web.UserAgents;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.web.TokenResponses;
import com.jejari.auth.web.TokenResponses.TokenResponse;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.member.MemberAuthEnabled;
import com.jejari.member.web.ClientIpResolver;

/**
 * AUTH-05 관리자 로그인 (A-01). {@code POST /api/v1/admin/auth/login} (기능설계서 7장).
 * 응답 형식은 회원 로그인과 같습니다: 본문에 액세스 토큰, 리프레시 토큰은 HttpOnly 쿠키.
 * 재발급·로그아웃은 회원과 같은 {@code /api/v1/auth/refresh}, {@code /api/v1/auth/logout}을 씁니다.
 */
@RestController
@RequestMapping("/api/v1/admin/auth")
@MemberAuthEnabled
public class AdminAuthController {

	private final AdminLoginService login;
	private final TokenResponses tokenResponses;
	private final ClientIpResolver ipResolver;
	private final Pseudonymizer pseudonymizer;
	private final AuthTokenService tokens;

	public AdminAuthController(AdminLoginService login, TokenResponses tokenResponses, ClientIpResolver ipResolver,
			Pseudonymizer pseudonymizer, AuthTokenService tokens) {
		this.tokens = tokens;
		this.login = login;
		this.tokenResponses = tokenResponses;
		this.ipResolver = ipResolver;
		this.pseudonymizer = pseudonymizer;
	}

	@PostMapping("/login")
	public ResponseEntity<TokenResponse> login(@RequestBody AdminLoginRequest req, HttpServletRequest http) {
		String ipHash = pseudonymizer.ip(ipResolver.resolve(http));
		// 회원 로그인과 같은 정리(제어 문자 제거·바이트 제한). 전에는 원문을 그대로 넘겨 로그인 기록에 제어 문자가 남았다 (§2.3)
		return tokenResponses.ok(login.login(req.loginId(), req.password(), ipHash, UserAgents.from(http)));
	}

	/**
	 * 관리자 재발급. 쿠키는 관리자 전용({@code jejari_art}, 경로 /api/v1/admin/auth)이고 관리자 세션만 받는다.
	 * 회원 쿠키·회원 세션으로는 관리자 토큰을 받을 수 없다.
	 */
	@PostMapping("/refresh")
	public ResponseEntity<TokenResponse> refresh(
			@CookieValue(name = TokenResponses.ADMIN_REFRESH_COOKIE, required = false) String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new BusinessException(AuthErrorCode.AUTH_REFRESH_INVALID);
		}
		return tokenResponses.ok(tokens.refresh(refreshToken, ActorType.ADMIN));
	}

	public record AdminLoginRequest(String loginId, String password) {
		@Override
		public String toString() {
			return "AdminLoginRequest[***]";
		}
	}
}
