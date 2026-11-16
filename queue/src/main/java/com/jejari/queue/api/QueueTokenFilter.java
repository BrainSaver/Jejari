package com.jejari.queue.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jejari.queue.domain.ErrorCode;
import com.jejari.queue.domain.QueueException;
import com.jejari.queue.domain.QueuePassService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * QUE-04 좌석 API 앞단. /api/v1/runs/{runId}/seats 로 오는 요청은 X-Queue-Token이 없거나 맞지 않으면
 * 컨트롤러에 닿기 전에 403으로 막는다. (POST /reservations는 runId가 본문에 있어 B가 QueuePassService를 직접 호출한다.)
 */
public class QueueTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Queue-Token";
    private static final Pattern SEATS = Pattern.compile("^/api/v1/runs/(\\d+)/seats(/.*)?$");

    private final QueuePassService pass;
    private final MemberAuthenticator auth;
    private final ObjectMapper mapper;

    public QueueTokenFilter(QueuePassService pass, MemberAuthenticator auth, ObjectMapper mapper) {
        this.pass = pass;
        this.auth = auth;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SEATS.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Matcher m = SEATS.matcher(request.getRequestURI());
        m.matches();
        try {
            long memberId = auth.memberId(request).orElseThrow(() -> new QueueException(ErrorCode.AUTH_REQUIRED));
            pass.verify(Long.parseLong(m.group(1)), memberId, request.getHeader(HEADER));
        } catch (QueueException e) {
            write(response, e.code());
            return;
        } catch (DataAccessException e) {
            if (!ApiExceptionHandler.isRedisUnavailable(e)) {
                throw e;
            }
            // Redis를 읽지 못하면 토큰을 확인할 수 없으므로 통과시키지 않는다 (닫는 쪽으로 실패)
            write(response, ErrorCode.SERVICE_UNAVAILABLE);
            return;
        }
        chain.doFilter(request, response);
    }

    private void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), ErrorResponse.of(code));
    }
}
