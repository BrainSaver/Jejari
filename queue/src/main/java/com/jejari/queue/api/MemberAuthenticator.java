package com.jejari.queue.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.OptionalLong;

/** 요청에서 로그인한 회원 ID를 꺼낸다. E의 JWT 공통 인증이 붙으면 그 구현으로 교체한다. */
public interface MemberAuthenticator {
    OptionalLong memberId(HttpServletRequest request);
}
