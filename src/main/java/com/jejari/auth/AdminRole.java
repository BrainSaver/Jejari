package com.jejari.auth;

import java.util.Arrays;
import java.util.List;

/**
 * 관리자 세부 권한 (admin_user.role: VIEWER / REVIEWER / SUPERVISOR). 아래로 갈수록 위 권한을 모두 포함합니다.
 *
 * <p>1차는 관리자 API를 {@code hasRole("ADMIN")} 하나로만 막고, 세부 권한은 토큰(adm 클레임)과 권한 목록에만 담아 둡니다.
 * 관리자 API를 만들 때 {@code @PreAuthorize("hasAuthority('ADMIN_REVIEWER')")}처럼 붙이면 SUPERVISOR도 통과합니다.
 */
public enum AdminRole {
	/** 현황·위험 사용자 조회 (개인정보 마스킹) */
	VIEWER,
	/** 케이스 판정·결과 모름 결제 확인 */
	REVIEWER,
	/** 기준 데이터·운영 설정·강제 취소 */
	SUPERVISOR;

	/** 이 권한이 가진 Spring Security 권한 이름. SUPERVISOR → ADMIN_VIEWER, ADMIN_REVIEWER, ADMIN_SUPERVISOR */
	public List<String> authorities() {
		return Arrays.stream(values())
				.filter(r -> r.ordinal() <= ordinal())
				.map(r -> "ADMIN_" + r.name())
				.toList();
	}
}
