package com.jejari.common.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 프론트엔드(React, {@code frontend/})는 브라우저 안에서 주소를 바꾼다. 그 주소에서 새로고침하면 서버로 요청이 오므로
 * 화면 경로는 모두 {@code index.html}을 돌려줘야 한다. 정적 파일(js·css)은 Spring Boot가 {@code static/}에서 바로 준다.
 *
 * <p>화면 경로를 추가하면 여기와 {@code frontend/src/App.jsx}를 함께 고친다. 여기서 빠진 경로도 브라우저 요청이면
 * {@link com.jejari.common.error.GlobalExceptionHandler}가 404 상태로 index.html을 돌려줘 화면의 "없는 페이지" 안내가 뜬다.
 */
@Controller
public class SpaForwardController {

	@GetMapping({ "/", "/admin", "/admin/**", "/queue/**", "/reservations", "/reservations/**", "/waitlist", "/notifications",
			"/me", "/login", "/signup", "/guest", "/guest/**", "/runs/**", "/pay/**", "/groups" })
	public String index() {
		return "forward:/index.html";
	}
}
