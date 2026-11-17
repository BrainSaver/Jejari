package com.jejari.train.admin.web;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.train.admin.RunAdminService;
import com.jejari.train.admin.RunAdminService.BulkCommand;
import com.jejari.train.admin.RunAdminService.BulkResult;
import com.jejari.train.admin.RunAdminService.EventCommand;
import com.jejari.train.admin.RunAdminService.Run;
import com.jejari.train.admin.RunAdminService.RunCommand;
import com.jejari.train.admin.RunAdminService.RunFilter;
import com.jejari.train.admin.RunAdminService.SaleEvent;

/**
 * ADM-02 운행·명절 행사 관리 API (화면 A-03, 기능설계서 7장 {@code /admin/runs|sale-events}).
 * 보기는 VIEWER, 저장은 SUPERVISOR. 화면은 10초마다 목록을 다시 읽어 스케줄러의 상태 전이를 보여 준다.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAuthority('ADMIN_VIEWER')")
public class RunAdminController {

	private static final String SUPERVISOR = "hasAuthority('ADMIN_SUPERVISOR')";

	private final RunAdminService runs;

	public RunAdminController(RunAdminService runs) {
		this.runs = runs;
	}

	@GetMapping("/sale-events")
	public List<SaleEvent> events() {
		return runs.events();
	}

	@PostMapping("/sale-events")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(SUPERVISOR)
	public SaleEvent createEvent(@RequestBody EventCommand body) {
		return runs.createEvent(body);
	}

	@PutMapping("/sale-events/{id}")
	@PreAuthorize(SUPERVISOR)
	public SaleEvent updateEvent(@PathVariable long id, @RequestBody EventCommand body) {
		return runs.updateEvent(id, body);
	}

	/** 기본 기간은 오늘부터 62일 (평시 판매 창 2개월) */
	@GetMapping("/runs")
	public List<Run> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long trainId, @RequestParam(required = false) String status,
			@RequestParam(required = false) Long saleEventId) {
		return runs.runs(new RunFilter(from, to, trainId, status, saleEventId));
	}

	@GetMapping("/runs/{runId}")
	public Run run(@PathVariable long runId) {
		return runs.run(runId);
	}

	/** 기간·요일로 일괄 생성. 같은 날 운행이 이미 있으면 건너뛰고 skipped로 알려 준다 */
	@PostMapping("/runs")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(SUPERVISOR)
	public BulkResult create(@RequestBody BulkCommand body) {
		return runs.createRuns(body);
	}

	@PutMapping("/runs/{runId}")
	@PreAuthorize(SUPERVISOR)
	public Run update(@PathVariable long runId, @RequestBody RunCommand body) {
		return runs.updateRun(runId, body);
	}

	/** 오픈 시각을 기다리지 않고 지금 판매를 연다 */
	@PostMapping("/runs/{runId}/open")
	@PreAuthorize(SUPERVISOR)
	public Run open(@PathVariable long runId) {
		return runs.openNow(runId);
	}

	@DeleteMapping("/runs/{runId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(SUPERVISOR)
	public void delete(@PathVariable long runId) {
		runs.deleteRun(runId);
	}
}
