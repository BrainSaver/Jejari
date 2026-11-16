package com.jejari.member.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.web.TokenResponses;
import com.jejari.auth.web.TokenResponses.TokenResponse;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.member.MemberAuthEnabled;
import com.jejari.member.login.LoginService;
import com.jejari.member.signup.SignupService;
import com.jejari.member.verify.MockIdentityProvider.Provider;
import com.jejari.member.verify.VerificationTokens;

import jakarta.servlet.http.HttpServletRequest;

/**
 * AUTH-01·02·03 본인인증·회원가입·로그인. 경로는 {@code /api/v1/auth/**} (로그인 없이 호출, SecurityConfig).
 * 모든 응답에 {@code Cache-Control: no-store} — 토큰·마스킹 정보가 브라우저·프록시 캐시에 남지 않게.
 */
@RestController
@RequestMapping("/api/v1/auth")
@MemberAuthEnabled
public class MemberAuthController {

	private static final int USER_AGENT_MAX = 300;

	private final SignupService signup;
	private final LoginService login;
	private final TokenResponses tokenResponses;
	private final ClientIpResolver ipResolver;
	private final Pseudonymizer pseudonymizer;

	public MemberAuthController(SignupService signup, LoginService login, TokenResponses tokenResponses,
			ClientIpResolver ipResolver, Pseudonymizer pseudonymizer) {
		this.signup = signup;
		this.login = login;
		this.tokenResponses = tokenResponses;
		this.ipResolver = ipResolver;
		this.pseudonymizer = pseudonymizer;
	}

	/** 1단계: 모의 본인인증 → 1회용 verificationToken (가입 때 한 번만 쓸 수 있음) */
	@PostMapping("/verify")
	public ResponseEntity<VerifyResponse> verify(@RequestBody VerifyRequest req, HttpServletRequest http) {
		VerificationTokens.Issued issued = signup.verify(req.name(), req.phone(), req.provider(), ipHash(http));
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(new VerifyResponse(issued.token(), issued.expiresIn()));
	}

	/** 2단계: 가입. 로그인 세션은 주지 않는다 (가입 후 로그인 화면으로) */
	@PostMapping("/signup")
	public ResponseEntity<SignupResponse> signup(@RequestBody SignupRequest req, HttpServletRequest http) {
		String masked = signup.signup(req.verificationToken(), req.email(), req.password(), ipHash(http));
		return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(new SignupResponse(masked));
	}

	/** 로그인 → 액세스 토큰(본문) + 리프레시 토큰(HttpOnly·Secure·SameSite=Strict 쿠키) */
	@PostMapping("/login")
	public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest req, HttpServletRequest http) {
		return tokenResponses.ok(login.login(req.email(), req.password(), ipHash(http), userAgent(http)));
	}

	private String ipHash(HttpServletRequest http) {
		return pseudonymizer.ip(ipResolver.resolve(http));
	}

	private static String userAgent(HttpServletRequest http) {
		String ua = http.getHeader(HttpHeaders.USER_AGENT);
		if (ua == null) {
			return null;
		}
		// 로그 위조 방지: 제어 문자 제거, 길이 제한
		String clean = ua.replaceAll("\\p{Cntrl}", "");
		return clean.length() > USER_AGENT_MAX ? clean.substring(0, USER_AGENT_MAX) : clean;
	}

	// ---- 요청·응답. 비밀값이 든 요청은 toString에서 가린다 (실수로 로그에 찍혀도 새지 않게) ----

	public record VerifyRequest(String name, String phone, Provider provider) {
		@Override
		public String toString() {
			return "VerifyRequest[provider=" + provider + "]";
		}
	}

	public record VerifyResponse(String verificationToken, long expiresIn) {
		@Override
		public String toString() {
			return "VerifyResponse[expiresIn=" + expiresIn + "]";
		}
	}

	public record SignupRequest(String verificationToken, String email, String password) {
		@Override
		public String toString() {
			return "SignupRequest[***]";
		}
	}

	public record SignupResponse(String email) {
	}

	public record LoginRequest(String email, String password) {
		@Override
		public String toString() {
			return "LoginRequest[***]";
		}
	}
}
