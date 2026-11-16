package com.jejari.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jejari.queue.domain.QueueService;
import com.jejari.queue.port.QueueLogSink.QueueLogEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class DevAdminApiTest extends RedisIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired QueueService queue;

    @Test
    void reset은_대기열과_위험_점수와_정책만_지우고_다른_키는_둔다() throws Exception {
        queue.enter(1, RUN);
        redis.opsForValue().set("risk:CI:x", "50");
        redis.opsForHash().put("policy:cache", "queue.admit_per_sec", "5");
        redis.opsForValue().set("keep:me", "1");

        mvc.perform(post("/api/v1/dev/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queue").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.risk").value(1));

        assertThat(redis.hasKey("queue:member:1")).isFalse();
        assertThat(redis.hasKey("risk:CI:x")).isFalse();
        assertThat(redis.hasKey("policy:cache")).isFalse();
        assertThat(redis.hasKey("keep:me")).isTrue();
    }

    @Test
    void 개발용_회원에게_위험_점수를_주면_진입_때_패널티로_반영된다() throws Exception {
        mvc.perform(put("/api/v1/dev/risk/dev-members?from=900&count=2&score=80"))
                .andExpect(status().isOk());
        assertThat(redis.opsForValue().get("risk:CI:dev-ci-900")).isEqualTo("80");
        assertThat(redis.opsForValue().get("risk:CI:dev-ci-901")).isEqualTo("80");

        queue.enter(900, RUN);
        QueueLogEvent e = sink.events.get(0);
        assertThat(e.riskScore()).isEqualTo(80.0);
        assertThat(e.penaltyMs()).isEqualTo(80_000);
    }

    @Test
    void 위험_점수_하나를_직접_설정한다() throws Exception {
        mvc.perform(put("/api/v1/dev/risk").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"DEVICE\",\"hash\":\"dev-1\",\"score\":42.5}"))
                .andExpect(status().isOk());
        assertThat(redis.opsForValue().get("risk:DEVICE:dev-1")).isEqualTo("42.5");
    }

    @Test
    void 정책을_바꾸면_Redis_해시에_들어간다() throws Exception {
        mvc.perform(put("/api/v1/dev/policy/queue.admit_per_sec").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliesWithinMs").value(1000));
        assertThat(redis.opsForHash().get("policy:cache", "queue.admit_per_sec")).isEqualTo("7");
    }

    @Test
    void 잘못된_입력은_400이고_아무것도_바꾸지_않는다() throws Exception {
        mvc.perform(put("/api/v1/dev/policy/auth.secret").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"1\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/dev/policy/queue.admit_per_sec").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"a b;c\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/dev/risk").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"CI\",\"hash\":\"h\",\"score\":500}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/dev/risk").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"ALL\",\"hash\":\"h\",\"score\":5}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/dev/risk/dev-members?from=1&count=0&score=5")).andExpect(status().isBadRequest());
        assertThat(redis.hasKey("policy:cache")).isFalse();
        assertThat(redis.keys("risk:*")).isEmpty();
    }
}
