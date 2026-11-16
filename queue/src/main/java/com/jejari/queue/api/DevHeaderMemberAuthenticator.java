package com.jejari.queue.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.OptionalLong;

/** 개발·테스트 전용: X-Member-Id 헤더를 회원으로 믿는다. jejari.dev-mode=true일 때만 만들어진다. */
public class DevHeaderMemberAuthenticator implements MemberAuthenticator {
    @Override
    public OptionalLong memberId(HttpServletRequest request) {
        String h = request.getHeader("X-Member-Id");
        if (h == null || h.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(h.trim()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
