package com.jejari.common.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 프론트엔드(React, {@code frontend/})는 브라우저 안에서 주소를 바꾼다. 그 주소에서 새로고침하면 서버로 요청이 오므로
 * 화면 경로는 모두 {@code index.html}을 돌려줘야 한다. 정적 파일(js·css)은 Spring Boot가 {@code static/}에서 바로 준다.
 *
 * <p>화면 경로를 추가하면 여기와 {@code SecurityConfig}의 {@code SPA_PAGES}, {@code frontend/src/App.jsx}를 함께 고친다.
 */
@Controller
public class SpaForwardController {

	@GetMapping({ "/", "/admin", "/admin/**", "/queue/**", "/reservations", "/reservations/**", "/waitlist", "/notifications",
			"/me", "/login", "/signup", "/runs/**", "/pay/**" })
	public String index() {
		return "forward:/index.html";
	}
}
