package com.jejari.auth.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.jejari.auth.AdminRole;
import com.jejari.common.error.BusinessException;
import com.jejari.member.MemberAuthEnabled;
import com.jejari.member.password.PasswordHasher;
import com.jejari.member.password.PasswordPolicy;

/**
 * 첫 관리자 계정 만들기. admin_user가 비어 있고 {@code JEJARI_ADMIN_BOOTSTRAP_ID}·{@code _PASSWORD}가 있으면
 * 기동할 때 SUPERVISOR 1명을 만듭니다. 이미 관리자가 있으면 아무것도 하지 않습니다 (여러 번 기동해도 같은 결과).
 *
 * <p>시드 SQL로 넣지 않는 이유: 비밀번호 해시가 각자의 pepper 키에 묶여 있어 공유할 수 있는 해시 값이 없습니다.
 * 계정이 만들어진 뒤에는 .env의 비밀번호를 지워도 됩니다.
 */
@Component
@MemberAuthEnabled
public class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final AdminAccountRepository accounts;
	private final PasswordHasher hasher;
	private final String loginId;
	private final String password;

	public AdminBootstrap(AdminAccountRepository accounts, PasswordHasher hasher,
			@Value("${jejari.admin.bootstrap.login-id:}") String loginId,
			@Value("${jejari.admin.bootstrap.password:}") String password) {
		this.accounts = accounts;
		this.hasher = hasher;
		this.loginId = loginId;
		this.password = password;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (accounts.count() > 0) {
			return;
		}
		if (loginId.isBlank() || password.isBlank()) {
			log.info("관리자 계정이 없습니다. 만들려면 .env에 JEJARI_ADMIN_BOOTSTRAP_ID·JEJARI_ADMIN_BOOTSTRAP_PASSWORD를 넣고 다시 기동하세요");
			return;
		}
		String id = AdminLoginService.normalizeOrNull(loginId);
		if (id == null) {
			throw new IllegalStateException("JEJARI_ADMIN_BOOTSTRAP_ID 형식이 틀렸습니다 (영문 소문자·숫자·.-_ 3~50자)");
		}
		// 회원과 같은 비밀번호 규칙 (길이·문자 종류, ID 포함 금지). 비밀번호는 메시지에 넣지 않음
		try {
			PasswordPolicy.validate(password, id + "@admin.invalid");
		} catch (BusinessException e) {
			throw new IllegalStateException("JEJARI_ADMIN_BOOTSTRAP_PASSWORD가 비밀번호 규칙에 맞지 않습니다: " + e.getMessage());
		}
		if (accounts.insertFirst(id, hasher.hash(password).hash(), AdminRole.SUPERVISOR)) {
			log.info("첫 관리자 계정을 만들었습니다 (admin_id=1, SUPERVISOR). .env의 JEJARI_ADMIN_BOOTSTRAP_PASSWORD는 지워도 됩니다");
		}
	}
}
