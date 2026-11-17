package com.jejari.member.me.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.member.MemberAuthEnabled;
import com.jejari.member.me.MyAccountService;
import com.jejari.member.me.MyAccountService.Me;

/**
 * AUTH-06 내 정보·탈퇴 (U-09).
 *
 * <pre>
 * GET    /api/v1/me                               내 정보 (이름·휴대폰 마스킹)
 * DELETE /api/v1/me   {"password":"…"}            탈퇴 (비밀번호 재확인) → 204
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me")
@MemberAuthEnabled
public class MyAccountController {

	public record WithdrawRequest(String password) {
	}

	private final MyAccountService service;

	public MyAccountController(MyAccountService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<Me> me(@AuthenticationPrincipal AuthPrincipal me) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.me(me.memberId()));
	}

	@DeleteMapping
	public ResponseEntity<Void> withdraw(@AuthenticationPrincipal AuthPrincipal me,
			@RequestBody(required = false) WithdrawRequest body) {
		service.withdraw(me.memberId(), body == null ? null : body.password());
		return ResponseEntity.noContent().build();
	}
}
