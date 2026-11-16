package com.jejari.support;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthErrorCode;
import com.jejari.behavior.BehaviorLog;
import com.jejari.behavior.BehaviorType;
import com.jejari.common.error.BusinessException;

/**
 * 테스트 전용: 다른 파트 컨트롤러처럼 {@link BehaviorLog#mark}를 부르는 엔드포인트 (FullStackInfraTest에만 등록).
 * fail=true면 mark 뒤에 409 예외를 던진다 (실패 요청도 남는지 확인).
 */
@RestController
public class BehaviorProbeController {

	@PostMapping("/api/v1/test-probe/seat-hold")
	public ResponseEntity<Void> hold(@RequestParam long runId, @RequestParam(defaultValue = "false") boolean fail) {
		BehaviorLog.mark(BehaviorType.SEAT_HOLD, runId,
				Map.of("seatIds", List.of(12, 13), "seatCount", 2, "name", "홍길동"));
		if (fail) {
			throw new BusinessException(AuthErrorCode.AUTH_EMAIL_DUPLICATED); // 409
		}
		return ResponseEntity.ok().build();
	}
}
