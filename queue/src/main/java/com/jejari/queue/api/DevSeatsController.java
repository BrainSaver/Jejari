package com.jejari.queue.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개발·부하 테스트 전용: B의 좌석 배치도 API 자리. 토큰 필터(QueueTokenFilter)를 통과하면 200을 돌려준다.
 * jejari.dev-mode=true일 때만 만들어지며, 실제 좌석 API가 붙으면 제거한다.
 */
@RestController
@RequestMapping("/api/v1/runs/{runId}/seats")
@ConditionalOnProperty(name = "jejari.dev-mode", havingValue = "true")
public class DevSeatsController {

    @GetMapping
    public String seats(@PathVariable long runId) {
        return "{\"runId\":" + runId + ",\"dev\":true}";
    }
}
