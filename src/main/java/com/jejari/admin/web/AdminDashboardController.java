package com.jejari.admin.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.admin.DashboardService;
import com.jejari.admin.RunDashboard;
import com.jejari.train.TrainRunReader;

/**
 * ADM-03 실시간 예매 현황 API (기능설계서 7장 {@code GET /admin/dashboard/runs/{runId}}).
 *
 * <p>{@code /api/v1/admin/**} 는 SecurityConfig에서 {@code hasRole("ADMIN")}이다.
 * 설계서는 이 화면을 VIEWER 권한으로 적어 두었지만 관리자 등급(VIEWER·REVIEWER·SUPERVISOR)은
 * 아직 코드에 없다. 지금은 관리자면 볼 수 있고, 등급이 생기면 여기에 {@code @PreAuthorize}를 붙인다.
 *
 * <p>행동 로그(BehaviorLog)는 남기지 않는다. 그 로그는 이용자 행동을 모으는 것이고(D 강다인),
 * 10초 폴링을 섞으면 분석 대상이 관리자 조회로 덮인다.
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class AdminDashboardController {

	private final DashboardService dashboard;
	private final TrainRunReader runs;

	public AdminDashboardController(DashboardService dashboard, TrainRunReader runs) {
		this.dashboard = dashboard;
		this.runs = runs;
	}

	/** 판매 중인 운행 ID 목록. 화면이 "어느 운행을 볼까"를 고르는 데 쓴다 */
	@GetMapping("/runs")
	public List<Long> onSaleRuns() {
		return runs.onSaleRunIds();
	}

	@GetMapping("/runs/{runId}")
	public RunDashboard run(@PathVariable long runId) {
		return dashboard.of(runId);
	}
}
