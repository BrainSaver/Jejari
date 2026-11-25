package com.jejari.member.guest.web;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.web.TokenResponses.TokenResponse;
import com.jejari.auth.web.TokenResponses;
import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.common.web.UserAgents;
import com.jejari.member.MemberAuthEnabled;
import com.jejari.member.guest.GuestService;
import com.jejari.member.guest.GuestService.GuestReservation;
import com.jejari.member.web.ClientIpResolver;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 비회원(게스트) 예매 API. /api/v1/auth/** 아래라 로그인 없이 부를 수 있다 (SecurityConfig).
 *
 * <pre>
 * GET  /api/v1/auth/guest/enabled   {"enabled": true|false}  화면이 "비회원으로 계속" 버튼을 보일지 (app_policy guest.enabled)
 * POST /api/v1/auth/guest           {"verificationToken": "…"}  본인인증(/auth/verify) 뒤 게스트로 입장 → 로그인과 같은 토큰 응답
 * POST /api/v1/auth/guest/lookup    {"reservationId": 1, "phone": "010-…"}  로그인 없이 예약 한 건 조회
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/auth/guest")
@MemberAuthEnabled
public class GuestController {

	private final GuestService guests;
	private final TokenResponses tokenResponses;
	private final ClientIpResolver ipResolver;
	private final Pseudonymizer pseudonymizer;

	public GuestController(GuestService guests, TokenResponses tokenResponses, ClientIpResolver ipResolver,
			Pseudonymizer pseudonymizer) {
		this.guests = guests;
		this.tokenResponses = tokenResponses;
		this.ipResolver = ipResolver;
		this.pseudonymizer = pseudonymizer;
	}

	@GetMapping("/enabled")
	public ResponseEntity<Map<String, Boolean>> enabled() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("enabled", guests.enabled()));
	}

	public record EnterRequest(String verificationToken) {
		@Override
		public String toString() {
			return "EnterRequest[***]";
		}
	}

	@PostMapping
	public ResponseEntity<TokenResponse> enter(@RequestBody EnterRequest req, HttpServletRequest http) {
		return tokenResponses.ok(guests.enter(req.verificationToken(), ipHash(http), userAgent(http)));
	}

	public record LookupRequest(Long reservationId, String phone) {
		@Override
		public String toString() {
			return "LookupRequest[reservationId=" + reservationId + "]"; // 휴대폰은 찍지 않는다
		}
	}

	@PostMapping("/lookup")
	public ResponseEntity<GuestReservation> lookup(@RequestBody LookupRequest req, HttpServletRequest http) {
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(guests.lookup(req.reservationId(), req.phone(), ipHash(http)));
	}

	private String ipHash(HttpServletRequest http) {
		return pseudonymizer.ip(ipResolver.resolve(http));
	}

	/** 제어 문자 제거 + UTF-8 300바이트 (UserAgents, 보안 점검 2026-11-25 §2.3) */
	private static String userAgent(HttpServletRequest http) {
		return UserAgents.from(http);
	}
}
