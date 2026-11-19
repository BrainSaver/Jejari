package com.jejari.auth.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.ActorType;
import com.jejari.auth.AdminRole;
import com.jejari.auth.AuthErrorCode;
import com.jejari.auth.AuthPrincipal;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.auth.jwt.JwtTestFactory;
import com.jejari.auth.session.AuthTokenService;
import com.jejari.auth.session.AuthTokenService.IssuedTokens;
import com.jejari.common.error.BusinessException;
import com.jejari.support.MutableClock;
import com.jejari.support.TestJwt;
import com.jejari.support.WebSecurityTestConfig;

/** Bearer 인증 필터·401/403 코드·재발급 쿠키·로그아웃 (서비스는 목). */
@WebMvcTest(controllers = {AuthTokenController.class, AuthWebTest.ProbeController.class})
@WebSecurityTestConfig
@Import(AuthWebTest.ProbeController.class)
class AuthWebTest {

	static final String SID = "3f2b8c1e-1111-4222-8333-444455556666";

	@Autowired
	MockMvc mvc;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	MutableClock clock;

	@MockitoBean
	AuthTokenService authTokenService;

	@BeforeEach
	void resetClock() {
		clock.set(MutableClock.DEFAULT);
	}

	String bearer(ActorType type, long id) {
		// 관리자 토큰은 세부 권한(adm)이 있어야 유효 (AUTH-05)
		return "Bearer " + issuer.issue(type, id, SID, type == ActorType.ADMIN ? AdminRole.VIEWER : null).value();
	}

	@Test
	void 유효한_토큰이면_AuthPrincipal로_받는다() throws Exception {
		mvc.perform(get("/api/v1/probe/me").header(HttpHeaders.AUTHORIZATION, bearer(ActorType.MEMBER, 1001)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.memberId").value(1001))
				.andExpect(jsonPath("$.sessionId").value(SID));
	}

	@Test
	void 만료된_토큰은_AUTH_TOKEN_EXPIRED() throws Exception {
		String token = bearer(ActorType.MEMBER, 1001);
		clock.advance(Duration.ofMinutes(16));

		mvc.perform(get("/api/v1/probe/me").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_TOKEN_EXPIRED"))
				.andExpect(jsonPath("$.traceId").isNotEmpty())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
	}

	@Test
	void 위조된_토큰은_AUTH_TOKEN_INVALID() throws Exception {
		String token = bearer(ActorType.MEMBER, 1001);
		String forged = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

		mvc.perform(get("/api/v1/probe/me").header(HttpHeaders.AUTHORIZATION, forged))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
	}

	@Test
	void 다른_키로_서명된_토큰은_AUTH_TOKEN_INVALID() throws Exception {
		var otherKey = TestJwt.props("k1", Map.of("k1", TestJwt.K2));
		String token = JwtTestFactory.issuer(otherKey, clock)
				.issue(ActorType.ADMIN, 1, SID).value();

		mvc.perform(get("/api/v1/probe/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
	}

	@Test
	void 회원_토큰으로_관리자_API는_ADMIN_FORBIDDEN() throws Exception {
		mvc.perform(get("/api/v1/admin/probe").header(HttpHeaders.AUTHORIZATION, bearer(ActorType.MEMBER, 1001)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ADMIN_FORBIDDEN"));
	}

	@Test
	void 관리자_토큰으로_관리자_API_통과() throws Exception {
		mvc.perform(get("/api/v1/admin/probe").header(HttpHeaders.AUTHORIZATION, bearer(ActorType.ADMIN, 7)))
				.andExpect(status().isOk());
	}

	@Test
	void 쿠키_없이_재발급하면_AUTH_REFRESH_INVALID() throws Exception {
		mvc.perform(post("/api/v1/auth/refresh"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REFRESH_INVALID"));
	}

	@Test
	void 재발급_성공하면_본문엔_액세스만_리프레시는_HttpOnly_쿠키() throws Exception {
		when(authTokenService.refresh("old-rt", ActorType.MEMBER)).thenReturn(new IssuedTokens(SID, "new-access",
				clock.instant().plus(Duration.ofMinutes(15)), "new-rt", clock.instant().plus(Duration.ofDays(3))));

		mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(TokenResponses.REFRESH_COOKIE, "old-rt")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value("new-access"))
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(900))
				.andExpect(jsonPath("$.refreshToken").doesNotExist())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
						containsString("jejari_rt=new-rt"),
						containsString("Path=/api/v1/auth"),
						containsString("Max-Age=259200"),
						containsString("HttpOnly"),
						containsString("Secure"),
						containsString("SameSite=Strict"))));
	}

	@Test
	void 만료된_Bearer를_달고_재발급해도_막히지_않는다() throws Exception {
		String expired = bearer(ActorType.MEMBER, 1001);
		clock.advance(Duration.ofMinutes(16));
		when(authTokenService.refresh("old-rt", ActorType.MEMBER)).thenReturn(new IssuedTokens(SID, "new-access",
				clock.instant().plus(Duration.ofMinutes(15)), "new-rt", clock.instant().plus(Duration.ofDays(3))));

		mvc.perform(post("/api/v1/auth/refresh")
						.header(HttpHeaders.AUTHORIZATION, expired)
						.cookie(new Cookie(TokenResponses.REFRESH_COOKIE, "old-rt")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value("new-access"));
	}

	@Test
	void 유지하지_않는_회원_세션은_브라우저를_닫으면_사라지는_쿠키() throws Exception {
		when(authTokenService.refresh("old-rt", ActorType.MEMBER)).thenReturn(new IssuedTokens(SID, "new-access",
				clock.instant().plus(Duration.ofMinutes(15)), "new-rt", clock.instant().plus(Duration.ofHours(12)),
				ActorType.MEMBER, false));
		mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(TokenResponses.REFRESH_COOKIE, "old-rt")))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(containsString("jejari_rt=new-rt"),
						containsString("Path=/api/v1/auth"), not(containsString("Max-Age")))));
	}

	@Test
	void 관리자가_로그아웃하면_관리자_쿠키를_지운다() throws Exception {
		mvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, bearer(ActorType.ADMIN, 7)))
				.andExpect(status().isNoContent())
				.andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(containsString("jejari_art="),
						containsString("Path=/api/v1/admin/auth"), containsString("Max-Age=0"))));
	}

	@Test
	void 로그아웃은_인증_필요() throws Exception {
		mvc.perform(post("/api/v1/auth/logout"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
	}

	@Test
	void 로그아웃하면_현재_세션_폐기와_쿠키_삭제() throws Exception {
		mvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, bearer(ActorType.MEMBER, 1001)))
				.andExpect(status().isNoContent())
				.andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
						containsString("jejari_rt="), containsString("Max-Age=0"))));
		verify(authTokenService).logout(new AuthPrincipal(ActorType.MEMBER, 1001, SID));
	}

	@Test
	void 인증_경로는_토큰_없이_열려_있다() throws Exception {
		when(authTokenService.refresh(any(), any())).thenThrow(
				new BusinessException(AuthErrorCode.AUTH_REFRESH_INVALID));
		mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(TokenResponses.REFRESH_COOKIE, "x")))
				.andExpect(jsonPath("$.code").value("AUTH_REFRESH_INVALID"));
	}

	@RestController
	static class ProbeController {

		@GetMapping("/api/v1/probe/me")
		Map<String, Object> me(@AuthenticationPrincipal AuthPrincipal me) {
			return Map.of("memberId", me.memberId(), "sessionId", me.sessionId());
		}

		@GetMapping("/api/v1/admin/probe")
		String admin(@AuthenticationPrincipal AuthPrincipal me) {
			return "admin " + me.adminId();
		}
	}
}
