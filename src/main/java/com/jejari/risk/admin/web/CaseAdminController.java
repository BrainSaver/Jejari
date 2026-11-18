package com.jejari.risk.admin.web;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.risk.admin.CaseAdminService;
import com.jejari.risk.admin.CaseAdminService.CaseDetail;
import com.jejari.risk.admin.CaseAdminService.CasePage;
import com.jejari.risk.admin.CaseAdminService.Timeline;

/**
 * ADM-05 의심 케이스 (A-06). 조회는 VIEWER 이상. 판정·조치·마스킹 해제(ADM-06)는 {@code CaseActionController}.
 *
 * <pre>
 * GET /api/v1/admin/cases?status=OPEN,REVIEWING&amp;page=0&amp;size=20   목록 (최근 순)
 * GET /api/v1/admin/cases/{id}                                     요약·마커·연결 계정·점수 이력
 * GET /api/v1/admin/cases/{id}/timeline?from=…&amp;to=…               연결 계정 전부의 행동 로그 (기본: 의심 구간, 최대 24시간)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/cases")
@PreAuthorize("hasAuthority('ADMIN_VIEWER')")
public class CaseAdminController {

	static final Set<String> STATUSES = Set.of("OPEN", "REVIEWING", "CONFIRMED", "DISMISSED");

	private final CaseAdminService service;

	public CaseAdminController(CaseAdminService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<CasePage> list(@RequestParam(required = false) String status,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		int p = page == null ? 0 : page;
		int n = size == null ? CaseAdminService.DEFAULT_SIZE : size;
		if (p < 0 || p > 10_000 || n < 1 || n > CaseAdminService.MAX_SIZE) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		Set<String> st = new LinkedHashSet<>();
		if (status != null && !status.isBlank()) {
			for (String s : status.split(",")) {
				String v = s.strip().toUpperCase(Locale.ROOT);
				if (!STATUSES.contains(v)) {
					throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
				}
				st.add(v);
			}
		}
		return noStore(service.list(st, p, n));
	}

	@GetMapping("/{caseId}")
	public ResponseEntity<CaseDetail> detail(@PathVariable long caseId) {
		return noStore(service.detail(caseId));
	}

	@GetMapping("/{caseId}/timeline")
	public ResponseEntity<Timeline> timeline(@PathVariable long caseId, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to) {
		return noStore(service.timeline(caseId, instant(from), instant(to)));
	}

	private static Instant instant(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(s);
		} catch (DateTimeParseException e) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
	}

	private static <T> ResponseEntity<T> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
