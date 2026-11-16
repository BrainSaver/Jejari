package com.jejari.queue.dev;

import java.io.IOException;
import java.util.List;

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
				AuthPrincipal principal = new AuthPrincipal(ActorType.MEMBER, memberId, "dev-session");
				SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
						principal, null, List.of(new SimpleGrantedAuthority(ActorType.MEMBER.authority()))));
			}
			catch (NumberFormatException e) {
				// 인증하지 않음 → 401
			}
		}
		chain.doFilter(request, response);
	}
}
