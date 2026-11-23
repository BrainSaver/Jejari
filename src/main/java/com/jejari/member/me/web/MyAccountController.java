package com.jejari.member.me.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.member.MemberAuthEnabled;
import com.jejari.member.me.MyAccountService;
import com.jejari.member.me.MyAccountService.Me;
import com.jejari.member.me.MyAccountService.Revealed;
import com.jejari.member.me.MyAccountService.UpdateRequest;
import com.jejari.member.me.MyAccountService.UpdateResult;

/**
 * AUTH-06 내 정보·탈퇴 (U-09).
 *
 * <pre>
 * GET    /api/v1/me                               내 정보 (이름·휴대폰 마스킹)
 * POST   /api/v1/me/reveal {"password":"…"}       가리지 않은 이름·휴대폰 (비밀번호 재확인)
 * PATCH  /api/v1/me   {"currentPassword":"…", "name"?, "phone"?, "newPassword"?}
 *                                                  이름·휴대폰·비밀번호 변경 (현재 비밀번호 재확인). 비밀번호를 바꾸면 reloginRequired=true
 * DELETE /api/v1/me   {"password":"…"}            탈퇴 (비밀번호 재확인) → 204
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me")
@MemberAuthEnabled
public class MyAccountController {

	public record WithdrawRequest(String password) {
	}

	public record RevealRequest(String password) {
	}

	private final MyAccountService service;

	public MyAccountController(MyAccountService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<Me> me(@AuthenticationPrincipal AuthPrincipal me) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.me(me.memberId()));
	}

	/** 비밀번호를 다시 확인해야 가리지 않은 값을 준다. 응답은 어디에도 저장되지 않게 한다 */
	@PostMapping("/reveal")
	public ResponseEntity<Revealed> reveal(@AuthenticationPrincipal AuthPrincipal me,
			@RequestBody(required = false) RevealRequest body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(service.reveal(me.memberId(), body == null ? null : body.password()));
	}

	@PatchMapping
	public ResponseEntity<UpdateResult> update(@AuthenticationPrincipal AuthPrincipal me,
			@RequestBody UpdateRequest body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(me.memberId(), body));
	}

	@DeleteMapping
	public ResponseEntity<Void> withdraw(@AuthenticationPrincipal AuthPrincipal me,
			@RequestBody(required = false) WithdrawRequest body) {
		service.withdraw(me.memberId(), body == null ? null : body.password());
		return ResponseEntity.noContent().build();
	}
}
