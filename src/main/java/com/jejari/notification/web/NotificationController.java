package com.jejari.notification.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.notification.NotificationService;
import com.jejari.notification.NotificationService.Page;

/** U-11 알림함 API (기능설계서 7장: GET /notifications, 회원). */
@RestController
public class NotificationController {

	private final NotificationService notifications;

	public NotificationController(NotificationService notifications) {
		this.notifications = notifications;
	}

	/** 최신순 알림 + 안 읽은 수. 다음 페이지는 응답의 nextBefore를 before로 */
	@GetMapping("/api/v1/notifications")
	public Page list(@AuthenticationPrincipal AuthPrincipal me, @RequestParam(required = false) Long before,
			@RequestParam(defaultValue = "20") int size) {
		return notifications.list(me.memberId(), before, size);
	}

	/** 읽음 표시. 이미 읽었어도 204 */
	@PatchMapping("/api/v1/notifications/{notificationId}/read")
	public ResponseEntity<Void> read(@AuthenticationPrincipal AuthPrincipal me, @PathVariable long notificationId) {
		notifications.markRead(me.memberId(), notificationId);
		return ResponseEntity.noContent().build();
	}
}
