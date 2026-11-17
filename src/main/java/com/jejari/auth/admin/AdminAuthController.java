package com.jejari.auth.admin;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
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

	public AdminAuthController(AdminLoginService login, TokenResponses tokenResponses, ClientIpResolver ipResolver,
			Pseudonymizer pseudonymizer) {
		this.login = login;
		this.tokenResponses = tokenResponses;
		this.ipResolver = ipResolver;
		this.pseudonymizer = pseudonymizer;
	}

	@PostMapping("/login")
	public ResponseEntity<TokenResponse> login(@RequestBody AdminLoginRequest req, HttpServletRequest http) {
		String ipHash = pseudonymizer.ip(ipResolver.resolve(http));
		return tokenResponses.ok(login.login(req.loginId(), req.password(), ipHash, http.getHeader(HttpHeaders.USER_AGENT)));
	}

	public record AdminLoginRequest(String loginId, String password) {
		@Override
		public String toString() {
			return "AdminLoginRequest[***]";
		}
	}
}
