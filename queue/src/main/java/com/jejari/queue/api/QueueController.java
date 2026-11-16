package com.jejari.queue.api;

import com.jejari.queue.domain.ErrorCode;
import com.jejari.queue.domain.QueueException;
import com.jejari.queue.domain.QueueService;
import com.jejari.queue.domain.QueueStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 기능설계서 7장 대기열 API */
@RestController
@RequestMapping("/api/v1/runs/{runId}/queue")
public class QueueController {

    private final QueueService queue;
    private final MemberAuthenticator auth;

    public QueueController(QueueService queue, MemberAuthenticator auth) {
        this.queue = queue;
        this.auth = auth;
    }

    /** 대기열 진입 (QUE-01). 이미 같은 운행에 있으면 기존 번호표를 그대로 돌려준다. */
    @PostMapping
    public QueueStatus enter(@PathVariable long runId, HttpServletRequest request) {
        return queue.enter(memberId(request), runId);
    }

    /** 번호표 조회 (QUE-02). 입장했으면 입장 토큰이 함께 온다. */
    @GetMapping("/me")
    public QueueStatus me(@PathVariable long runId, HttpServletRequest request) {
        return queue.status(memberId(request), runId);
    }

    /** 대기열 이탈 (QUE-05) */
    @DeleteMapping("/me")
    public ResponseEntity<Void> leave(@PathVariable long runId, HttpServletRequest request) {
        queue.leave(memberId(request), runId);
        return ResponseEntity.noContent().build();
    }

    private long memberId(HttpServletRequest request) {
        return auth.memberId(request).orElseThrow(() -> new QueueException(ErrorCode.AUTH_REQUIRED));
    }
}
