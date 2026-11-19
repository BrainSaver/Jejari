package com.jejari.admin.traffic.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.admin.traffic.TrafficFlow;
import com.jejari.admin.traffic.TrafficService;
import com.jejari.admin.traffic.TrafficView;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;

/**
 * A-10 트래픽 관제 API. 읽기 전용이고 개인정보가 없어(요청 수·응답 시간·자원) VIEWER부터 본다.
 * 화면은 기간만 고른다 (30m · 1h · 6h · 24h). 쿼리는 서버에 고정 ({@link TrafficService}).
 */
@RestController
@RequestMapping("/api/v1/admin/traffic")
@PreAuthorize("hasAuthority('ADMIN_VIEWER')")
public class TrafficAdminController {

	private final TrafficService traffic;

	public TrafficAdminController(TrafficService traffic) {
		this.traffic = traffic;
	}

	@GetMapping
	public TrafficView view(@RequestParam(defaultValue = "1h") String range) {
		if (!TrafficService.validRange(range)) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		return traffic.view(range);
	}

	/** 상단 실시간 흐름도 (최근 30초 초당 값). 화면이 2초마다 부른다 */
	@GetMapping("/flow")
	public TrafficFlow flow() {
		return traffic.flow();
	}
}
