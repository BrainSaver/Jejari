package com.jejari.member.web;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청한 클라이언트 IP. 기본은 TCP 연결 주소({@code getRemoteAddr})만 믿는다.
 *
 * <p>{@code X-Forwarded-For}는 누구나 아무 값이나 넣을 수 있어, 그대로 믿으면 공격자가 요청마다 IP를 바꿔
 * IP별 시도 제한을 무력화한다. 그래서 연결 주소가 {@code jejari.auth.trusted-proxies}에 있는 프록시일 때만
 * 헤더를 오른쪽부터 읽어, 신뢰하는 프록시가 아닌 첫 주소를 쓴다 (왼쪽 값은 클라이언트가 위조할 수 있음).
 */
@Component
public class ClientIpResolver {

	/** IP 문자 집합만 허용 (DNS 조회를 일으키는 호스트 이름·이상한 문자를 받지 않음) */
	private static final Pattern IP = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

	private final Set<String> trustedProxies;

	public ClientIpResolver(@Value("${jejari.auth.trusted-proxies:}") String trustedProxies) {
		this.trustedProxies = Arrays.stream(trustedProxies.split(","))
				.map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
	}

	public String resolve(HttpServletRequest request) {
		String remote = request.getRemoteAddr();
		if (trustedProxies.isEmpty() || !trustedProxies.contains(remote)) {
			return remote;
		}
		String xff = request.getHeader("X-Forwarded-For");
		if (xff == null || xff.length() > 1000) {
			return remote;
		}
		String[] hops = xff.split(",");
		for (int i = hops.length - 1; i >= 0; i--) {
			String hop = hops[i].strip();
			if (!IP.matcher(hop).matches()) {
				return remote; // 형식이 이상하면 헤더 전체를 믿지 않는다
			}
			if (!trustedProxies.contains(hop)) {
				return hop;
			}
		}
		return remote;
	}
}
