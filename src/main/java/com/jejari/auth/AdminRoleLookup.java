package com.jejari.auth;

import java.util.Optional;

/**
 * 관리자의 현재 세부 권한. 활성(ACTIVE) 관리자만 값이 있고, 비활성(DISABLED)·삭제면 비어 있습니다.
 * 토큰을 발급·재발급할 때마다 DB에서 다시 읽어서, 권한이 바뀌거나 비활성화되면 다음 재발급부터 반영됩니다.
 */
@FunctionalInterface
public interface AdminRoleLookup {

	Optional<AdminRole> activeRole(long adminId);
}
