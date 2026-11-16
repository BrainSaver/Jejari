package com.jejari.queue.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.behavior.BehaviorLog;
import com.jejari.behavior.BehaviorType;
import com.jejari.common.error.BusinessException;
import com.jejari.queue.QueueErrorCode;
import com.jejari.queue.domain.QueueService;
import com.jejari.queue.domain.QueueStatus;

/** 기능설계서 7장 대기열 API. 로그인한 회원(JWT)만 쓸 수 있습니다. */
@RestController
@RequestMapping("/api/v1/runs/{runId}/queue")
public class QueueController {

	private final QueueService queue;

	public QueueController(QueueService queue) {
		this.queue = queue;
	}

	/** 대기열 진입 (QUE-01). 이미 같은 운행에 있으면 기존 번호표를 그대로 돌려준다. */
	@PostMapping
	public QueueStatus enter(@PathVariable long runId, @AuthenticationPrincipal AuthPrincipal me) {
		try {
			QueueStatus status = queue.enter(me.memberId(), runId);
			// 행동 로그 (RISK-01). 같은 운행에 다시 진입하면 기존 번호표가 오므로 rejected=false
			BehaviorLog.mark(BehaviorType.QUEUE_ENTER, runId, Map.of("ticketNo", status.ticketNo(), "rejected", false));
			return status;
		} catch (BusinessException e) {
			// 예외를 던지기 전에 표시해야 거절된 요청(409 등)도 남는다. 응답 코드는 인터셉터가 붙인다
			BehaviorLog.mark(BehaviorType.QUEUE_ENTER, runId,
					Map.of("rejected", e.getErrorCode() == QueueErrorCode.QUEUE_DUPLICATED));
			throw e;
		}
	}

	/** 번호표 조회 (QUE-02). 입장했으면 입장 토큰이 함께 온다. */
	@GetMapping("/me")
	public QueueStatus me(@PathVariable long runId, @AuthenticationPrincipal AuthPrincipal me) {
		return queue.status(me.memberId(), runId);
	}

	/** 대기열 이탈 (QUE-05) */
	@DeleteMapping("/me")
	public ResponseEntity<Void> leave(@PathVariable long runId, @AuthenticationPrincipal AuthPrincipal me) {
		queue.leave(me.memberId(), runId);
		return ResponseEntity.noContent().build();
	}
}
