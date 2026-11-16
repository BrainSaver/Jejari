package com.jejari.member.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

	static MockHttpServletRequest req(String remote, String xff) {
		MockHttpServletRequest r = new MockHttpServletRequest();
		r.setRemoteAddr(remote);
		if (xff != null) {
			r.addHeader("X-Forwarded-For", xff);
		}
		return r;
	}

	@Test
	void 프록시_설정이_없으면_헤더를_믿지_않는다_위조_방지() {
		ClientIpResolver r = new ClientIpResolver("");
		assertThat(r.resolve(req("203.0.113.5", "1.2.3.4"))).isEqualTo("203.0.113.5");
	}

	@Test
	void 신뢰하는_프록시에서_온_요청만_헤더를_오른쪽부터_읽는다() {
		ClientIpResolver r = new ClientIpResolver("10.0.0.1, 10.0.0.2");
		// 클라이언트가 왼쪽에 가짜 값을 넣어도 프록시가 붙인 진짜 주소(198.51.100.7)를 쓴다
		assertThat(r.resolve(req("10.0.0.1", "6.6.6.6, 198.51.100.7, 10.0.0.2"))).isEqualTo("198.51.100.7");
		// 신뢰하지 않는 곳에서 온 헤더는 무시
		assertThat(r.resolve(req("203.0.113.5", "1.2.3.4"))).isEqualTo("203.0.113.5");
	}

	@Test
	void 헤더_형식이_이상하면_연결_주소() {
		ClientIpResolver r = new ClientIpResolver("10.0.0.1");
		assertThat(r.resolve(req("10.0.0.1", "evil.example.com"))).isEqualTo("10.0.0.1");
		assertThat(r.resolve(req("10.0.0.1", "1.2.3.4\r\nX-Injected: 1"))).isEqualTo("10.0.0.1");
	}
}
