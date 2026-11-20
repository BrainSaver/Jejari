package com.jejari.queue.dev;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 개발 모드는 공용 서버(JEJARI_DOMAIN이 있는 곳)에서 기동을 멈춘다 — 헤더 하나로 아무 회원이 되는 문을 서버에 열지 않게 */
class DevSecurityConfigGuardTest {

	@Test
	void 서버에서는_기동을_멈춘다() {
		assertThatThrownBy(() -> DevSecurityConfig.refuseOnServer("113.198.238.102.nip.io"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("공용 서버");
	}

	@Test
	void 각자_PC에서는_그대로() {
		assertThatCode(() -> DevSecurityConfig.refuseOnServer(null)).doesNotThrowAnyException();
		assertThatCode(() -> DevSecurityConfig.refuseOnServer(" ")).doesNotThrowAnyException();
	}
}
