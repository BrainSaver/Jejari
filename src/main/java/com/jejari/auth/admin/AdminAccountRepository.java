package com.jejari.auth.admin;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.jejari.auth.AdminRole;
import com.jejari.auth.AdminRoleLookup;

/**
 * admin_user 접근. 회원 쪽(LoginService)처럼 JdbcTemplate으로 필요한 컬럼만 읽고, 상태 변경은 조건부 UPDATE로 합니다.
 *
 * <p>admin_user에는 pepper_key_id 컬럼이 없어 비밀번호는 항상 현재(active) pepper로 해시합니다.
 * pepper를 바꾸면 관리자 비밀번호는 다시 설정해야 합니다 (계정이 몇 개뿐이라 ERD를 늘리지 않음, 2026-11-17 결정).
 */
@Repository
public class AdminAccountRepository implements AdminRoleLookup {

	private final JdbcTemplate jdbc;

	public AdminAccountRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<AdminRole> activeRole(long adminId) {
		List<String> roles = jdbc.queryForList(
				"SELECT role FROM admin_user WHERE admin_id = ? AND status = 'ACTIVE'", String.class, adminId);
		return roles.isEmpty() ? Optional.empty() : Optional.of(AdminRole.valueOf(roles.get(0)));
	}

	public Optional<AdminAccount> findByLoginId(String loginId) {
		List<AdminAccount> rows = jdbc.query(
				"SELECT admin_id, password_hash, role, status FROM admin_user WHERE login_id = ?",
				(rs, i) -> new AdminAccount(rs.getLong(1), rs.getString(2), AdminRole.valueOf(rs.getString(3)),
						rs.getString(4)),
				loginId);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	public void markLogin(long adminId, Timestamp now) {
		jdbc.update("UPDATE admin_user SET last_login_at = ?, updated_at = SYSTIMESTAMP WHERE admin_id = ?",
				now, adminId);
	}

	/** 해시 파라미터가 바뀌어 다시 해시할 때. 그 사이 비밀번호가 바뀌었으면(해시가 다르면) 덮어쓰지 않음 */
	public void rehash(long adminId, String oldHash, String newHash) {
		jdbc.update("""
				UPDATE admin_user SET password_hash = ?, updated_at = SYSTIMESTAMP
				 WHERE admin_id = ? AND password_hash = ?""", newHash, adminId, oldHash);
	}

	public long count() {
		Long n = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user", Long.class);
		return n == null ? 0 : n;
	}

	/**
	 * 테이블이 비어 있을 때만 첫 관리자(admin_id = 1)를 넣습니다. admin_user에는 시퀀스가 없어 첫 계정만 이 방법으로 만듭니다.
	 * 앱 두 대가 동시에 시도해도 PK 충돌로 한 대만 성공합니다.
	 *
	 * @return 새로 만들었으면 true
	 */
	public boolean insertFirst(String loginId, String passwordHash, AdminRole role) {
		try {
			return jdbc.update("""
					INSERT INTO admin_user (admin_id, login_id, password_hash, role, status)
					SELECT 1, ?, ?, ?, 'ACTIVE' FROM dual
					 WHERE NOT EXISTS (SELECT 1 FROM admin_user)""", loginId, passwordHash, role.name()) == 1;
		} catch (DuplicateKeyException e) {
			return false;
		}
	}

	public record AdminAccount(long adminId, String passwordHash, AdminRole role, String status) {

		public boolean active() {
			return "ACTIVE".equals(status);
		}

		@Override
		public String toString() {
			return "AdminAccount[" + adminId + ", " + role + ", " + status + "]";
		}
	}
}
