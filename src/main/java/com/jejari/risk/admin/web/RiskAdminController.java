package com.jejari.risk.admin.web;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.common.error.BusinessException;
import com.jejari.common.error.CommonErrorCode;
import com.jejari.risk.PreviousScore.ReviewStatus;
import com.jejari.risk.SubjectType;
import com.jejari.risk.admin.RiskAdminService;
import com.jejari.risk.admin.RiskAdminService.Filter;
import com.jejari.risk.admin.RiskAdminService.Page;

/**
 * ADM-04 위험 사용자 목록 (A-05). VIEWER 이상. 개인정보는 마스킹된 값만 나간다.
 *
 * <pre>
 * GET /api/v1/admin/risks?minScore=80&amp;maxScore=100&amp;status=PENDING,CONFIRMED&amp;type=CI&amp;page=0&amp;size=20
 * </pre>
 *
 * 응답은 캐시하지 않는다 (마스킹이어도 개인정보 조각). 모르는 필터 값은 400.
 */
@RestController
@RequestMapping("/api/v1/admin/risks")
@PreAuthorize("hasAuthority('ADMIN_VIEWER')")
public class RiskAdminController {

	private final RiskAdminService service;

	public RiskAdminController(RiskAdminService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<Page> list(@RequestParam(required = false) BigDecimal minScore,
			@RequestParam(required = false) BigDecimal maxScore, @RequestParam(required = false) String status,
			@RequestParam(required = false) String type, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		int p = page == null ? 0 : page;
		int n = size == null ? RiskAdminService.DEFAULT_SIZE : size;
		if (p < 0 || p > 10_000 || n < 1 || n > RiskAdminService.MAX_SIZE || !score(minScore) || !score(maxScore)
				|| (minScore != null && maxScore != null && minScore.compareTo(maxScore) > 0)) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		Filter f = new Filter(minScore, maxScore, parse(status, ReviewStatus.class, ReviewStatus::valueOf),
				parse(type, SubjectType.class, SubjectType::valueOf), p, n);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(f));
	}

	private static boolean score(BigDecimal s) {
		return s == null || (s.signum() >= 0 && s.compareTo(BigDecimal.valueOf(100)) <= 0);
	}

	/** "A,B" → enum 집합. 모르는 값이 하나라도 있으면 400 */
	static <E extends Enum<E>> Set<E> parse(String raw, Class<E> type, Function<String, E> of) {
		Set<E> out = EnumSet.noneOf(type);
		if (raw == null || raw.isBlank()) {
			return out;
		}
		String[] parts = raw.split(",");
		if (parts.length > type.getEnumConstants().length) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		for (String s : parts) {
			try {
				out.add(of.apply(s.strip().toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException e) {
				throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
			}
		}
		return out;
	}
}
