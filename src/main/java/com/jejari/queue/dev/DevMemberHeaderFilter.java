package com.jejari.queue.dev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.jejari.auth.ActorType;
import com.jejari.auth.AuthPrincipal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 개발 모드 전용: X-Member-Id 헤더를 로그인한 회원으로 믿는다 (JWT 로그인 없이 부하 테스트·수동 확인을 하려는 용도).
 * 숫자가 아니면 인증하지 않고 넘겨 보통의 401이 나오게 한다.
 *
 * <p>세션 ID는 회원 번호로 정해지는 UUID다 ({@link #sessionOf}). 같은 회원은 늘 같은 세션 (한 번 로그인해 계속 쓰는 것과 같음).
 * 예전에는 모든 회원이 고정 문자열 "dev-session"이었는데, 행동 로그 적재기가 UUID가 아닌 세션을 형식 오류로 건너뛰어
 * dev 모드 부하 시험의 행동 로그가 하나도 저장되지 않았고, 재생 기록은 모든 회원이 세션 하나로 섞였다
 * (2026-11-20 A-10 데이터 놓침·밀림 실측에서 발견).
 */
class DevMemberHeaderFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Member-Id";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HEADER);
		if (header != null) {
			try {
				long memberId = Long.parseLong(header.trim());
				AuthPrincipal principal = new AuthPrincipal(ActorType.MEMBER, memberId, sessionOf(memberId));
				SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
						principal, null, List.of(new SimpleGrantedAuthority(ActorType.MEMBER.authority()))));
			}
			catch (NumberFormatException e) {
				// 인증하지 않음 → 401
			}
		}
		chain.doFilter(request, response);
	}

	/** 회원마다 고정된 세션 UUID (이름 기반 v3). 실제 로그인 세션(무작위 v4)과 겹치지 않는다 */
	static String sessionOf(long memberId) {
		return UUID.nameUUIDFromBytes(("jejari-dev-session:" + memberId).getBytes(StandardCharsets.UTF_8)).toString();
	}
}
