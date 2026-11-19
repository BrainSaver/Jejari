package com.jejari.auth.admin;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.ActorType;
import com.jejari.auth.AdminRole;
import com.jejari.auth.AuthErrorCode;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.auth.session.AuthTokenService.IssuedTokens;
import com.jejari.auth.web.TokenResponses;
import com.jejari.auth.session.AuthTokenService;
import com.jejari.common.error.BusinessException;
import com.jejari.common.hmac.HmacConfig;
import com.jejari.member.web.ClientIpResolver;
import com.jejari.support.MutableClock;
import com.jejari.support.TestJwt;
import com.jejari.support.WebSecurityTestConfig;

/** 관리자 로그인 API·관리자 경로 권한 (서비스는 목, DB 없이). */
@WebMvcTest(controllers = {AdminAuthController.class, AdminAuthWebTest.AdminProbe.class},
		// @MemberAuthEnabled 조건: 비밀값 세 개가 모두 있어야 컨트롤러가 생김 (테스트 전용 값)
		properties = {"jejari.hmac.key=" + TestJwt.K2, "jejari.pii.keys=k1:" + TestJwt.K1,
				"jejari.auth.pepper-keys=p1:" + TestJwt.K1})
@WebSecurityTestConfig
@Import({AdminAuthController.class, AdminAuthWebTest.AdminProbe.class, HmacConfig.class, ClientIpResolver.class})
class AdminAuthWebTest {

	static final String SID = "3f2b8c1e-1111-4222-8333-444455556666";
	static final String BODY = "{\"loginId\":\"admin\",\"password\":\"Correct-Horse-9\"}";

	@Autowired
	MockMvc mvc;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	MutableClock clock;

	@MockitoBean
	AuthTokenService authTokenService;
	@MockitoBean
	AdminLoginService adminLoginService;

	@BeforeEach
	void resetClock() {
		clock.set(MutableClock.DEFAULT);
	}

	String bearer(ActorType type, AdminRole role) {
		return "Bearer " + issuer.issue(type, 7L, SID, role).value();
	}

	@Test
	void 관리자_로그인은_토큰_없이_열려_있고_회원과_같은_응답_형식() throws Exception {
		when(adminLoginService.login(eq("admin"), eq("Correct-Horse-9"), anyString(), any()))
				.thenReturn(new IssuedTokens(SID, "admin-access", clock.instant().plus(Duration.ofMinutes(15)),
						"admin-rt", clock.instant().plus(Duration.ofHours(8)), ActorType.ADMIN, false));

		mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value("admin-access"))
				.andExpect(jsonPath("$.refreshToken").doesNotExist())
				.andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
						containsString("jejari_art=admin-rt"), containsString("HttpOnly"),
						containsString("Path=/api/v1/admin/auth"), not(containsString("Max-Age")))));
	}

	@Test
	void 만료된_Bearer를_달고_로그인해도_막히지_않는다() throws Exception {
		String expired = bearer(ActorType.ADMIN, AdminRole.VIEWER);
		clock.advance(Duration.ofMinutes(16));
		when(adminLoginService.login(anyString(), anyString(), anyString(), any()))
				.thenReturn(new IssuedTokens(SID, "a", clock.instant().plusSeconds(900), "r", clock.instant().plusSeconds(60)));

		mvc.perform(post("/api/v1/admin/auth/login").header(HttpHeaders.AUTHORIZATION, expired)
						.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isOk());
	}

	@Test
	void 관리자_재발급은_관리자_쿠키로만_토큰_없이_열려_있다() throws Exception {
		when(authTokenService.refresh("admin-rt", ActorType.ADMIN)).thenReturn(new IssuedTokens(SID, "admin-access-2",
				clock.instant().plus(Duration.ofMinutes(15)), "admin-rt-2", clock.instant().plus(Duration.ofHours(7)),
				ActorType.ADMIN, false));
		mvc.perform(post("/api/v1/admin/auth/refresh")
						.cookie(new jakarta.servlet.http.Cookie(TokenResponses.ADMIN_REFRESH_COOKIE, "admin-rt")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").value("admin-access-2"))
				.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("jejari_art=admin-rt-2")));
		// 회원 쿠키만 있으면 관리자 재발급은 쿠키가 없는 것과 같다
		mvc.perform(post("/api/v1/admin/auth/refresh")
						.cookie(new jakarta.servlet.http.Cookie(TokenResponses.REFRESH_COOKIE, "member-rt")))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REFRESH_INVALID"));
	}

	@Test
	void 로그인_실패는_공통_실패_형식() throws Exception {
		when(adminLoginService.login(anyString(), anyString(), anyString(), any()))
				.thenThrow(new BusinessException(AuthErrorCode.AUTH_INVALID_CREDENTIALS));

		mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
	}

	@Test
	void 다른_관리자_API는_여전히_토큰_필요() throws Exception {
		mvc.perform(get("/api/v1/admin/probe/any"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
	}

	@Test
	void 회원_토큰은_관리자_API에서_ADMIN_FORBIDDEN() throws Exception {
		mvc.perform(get("/api/v1/admin/probe/any").header(HttpHeaders.AUTHORIZATION, bearer(ActorType.MEMBER, null)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ADMIN_FORBIDDEN"));
	}

	@Test
	void 세부_권한이_모자라면_403_공통_형식() throws Exception {
		mvc.perform(get("/api/v1/admin/probe/reviewer").header(HttpHeaders.AUTHORIZATION,
						bearer(ActorType.ADMIN, AdminRole.VIEWER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ADMIN_FORBIDDEN"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
	}

	@Test
	void 상위_권한은_하위_권한_API를_통과() throws Exception {
		mvc.perform(get("/api/v1/admin/probe/reviewer").header(HttpHeaders.AUTHORIZATION,
						bearer(ActorType.ADMIN, AdminRole.REVIEWER)))
				.andExpect(status().isOk());
		mvc.perform(get("/api/v1/admin/probe/reviewer").header(HttpHeaders.AUTHORIZATION,
						bearer(ActorType.ADMIN, AdminRole.SUPERVISOR)))
				.andExpect(status().isOk());
	}

	@RestController
	static class AdminProbe {

		@GetMapping("/api/v1/admin/probe/any")
		String any() {
			return "ok";
		}

		@GetMapping("/api/v1/admin/probe/reviewer")
		@PreAuthorize("hasAuthority('ADMIN_REVIEWER')")
		String reviewer() {
			return "ok";
		}
	}
}
