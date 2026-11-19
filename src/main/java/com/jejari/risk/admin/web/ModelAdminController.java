package com.jejari.risk.admin.web;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.risk.model.ModelAdminService;
import com.jejari.risk.model.ModelAdminService.Comparison;
import com.jejari.risk.model.ModelAdminService.ModelVersion;

/**
 * A-09 모델 관리. 보기는 VIEWER, 사용 중 버전 바꾸기는 SUPERVISOR.
 *
 * <pre>
 * GET  /api/v1/admin/models                      버전 목록 (점수 수·봇 쪽으로 본 수·유형별·관리자 판정과 맞은 수)
 * GET  /api/v1/admin/models/compare?a=…&amp;b=…      두 버전이 다르게 본 계정
 * POST /api/v1/admin/models/{version}/activate   관리자 화면에 보여 줄 버전으로 (점수가 없으면 409)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/models")
@PreAuthorize("hasAuthority('ADMIN_VIEWER')")
public class ModelAdminController {

	private final ModelAdminService service;

	public ModelAdminController(ModelAdminService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<List<ModelVersion>> list() {
		return noStore(service.list());
	}

	@GetMapping("/compare")
	public ResponseEntity<Comparison> compare(@RequestParam String a, @RequestParam String b) {
		if (a.isBlank() || b.isBlank() || a.equals(b) || a.length() > 20 || b.length() > 20) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		return noStore(service.compare(a, b));
	}

	@PostMapping("/{version}/activate")
	@PreAuthorize("hasAuthority('ADMIN_SUPERVISOR')")
	public List<ModelVersion> activate(@AuthenticationPrincipal AuthPrincipal me, @PathVariable String version) {
		return service.activate(me.actorId(), version);
	}

	private static <T> ResponseEntity<T> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
