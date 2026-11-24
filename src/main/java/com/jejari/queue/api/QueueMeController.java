package com.jejari.queue.api;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.queue.domain.QueueService;

/**
 * 운행을 모를 때 쓰는 이탈. 다른 열차 줄에 서 있어 새 줄을 못 설 때(QUEUE_DUPLICATED) 화면이
 * "기다리던 줄 그만두고 이 열차 줄 서기"로 부른다. 운행을 알면 {@code DELETE /api/v1/runs/{runId}/queue/me}를 쓴다.
 */
@RestController
public class QueueMeController {

	private final QueueService queue;

	public QueueMeController(QueueService queue) {
		this.queue = queue;
	}

	@DeleteMapping("/api/v1/queue/me")
	public ResponseEntity<Void> leaveCurrent(@AuthenticationPrincipal AuthPrincipal me) {
		queue.leaveCurrent(me.memberId());
		return ResponseEntity.noContent().build();
	}
}
