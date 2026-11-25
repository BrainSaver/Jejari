package com.jejari.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.jejari.auth.session.ClientInfo;

/** User-Agent는 auth_session.user_agent(VARCHAR2(300) BYTE)에 들어가므로 바이트 기준으로 잘려야 한다 (보안 점검 2026-11-25 §2.3) */
class UserAgentsTest {

	static int bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8).length;
	}

	@Test
	void 영문은_300자까지_그대로() {
		String ua = "a".repeat(300);
		assertThat(UserAgents.clean(ua)).isEqualTo(ua);
		assertThat(UserAgents.clean("a".repeat(301))).hasSize(300);
	}

	@Test
	void 한글_200자는_글자_수로는_300_이하지만_바이트로_잘린다() {
		String out = UserAgents.clean("가".repeat(200)); // 600바이트
		assertThat(bytes(out)).isEqualTo(300);
		assertThat(out).isEqualTo("가".repeat(100));
	}

	@Test
	void 이모지_서로게이트_쌍을_중간에서_끊지_않는다() {
		String out = UserAgents.clean("a" + "😀".repeat(100)); // 1 + 4×100바이트
		assertThat(bytes(out)).isLessThanOrEqualTo(300);
		assertThat(out).isEqualTo("a" + "😀".repeat(74)); // 1 + 296 = 297, 하나 더 넣으면 301
		assertThat(Character.isHighSurrogate(out.charAt(out.length() - 1))).isFalse();
	}

	@Test
	void 제어_문자는_지운다() {
		assertThat(UserAgents.clean("Mozilla\r\nX-Fake: 1\t")).isEqualTo("MozillaX-Fake: 1");
		assertThat(UserAgents.clean(null)).isNull();
	}

	@Test
	void ClientInfo도_DB에_넣기_전에_바이트로_자른다() {
		ClientInfo c = new ClientInfo(null, null, "가".repeat(200));
		assertThat(bytes(c.userAgent())).isEqualTo(300);
	}
}
