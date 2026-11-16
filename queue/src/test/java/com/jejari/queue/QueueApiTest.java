package com.jejari.queue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jejari.queue.domain.QueueAdmitter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class QueueApiTest extends RedisIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired QueueAdmitter admitter;
    @Autowired ObjectMapper mapper;

    private static final String Q = "/api/v1/runs/" + RUN + "/queue";
    private static final String SEATS = "/api/v1/runs/" + RUN + "/seats";

    @Test
    void 진입_조회_이탈_흐름() throws Exception {
        mvc.perform(post(Q).header("X-Member-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WAITING"))
                .andExpect(jsonPath("$.rank").value(1))
                .andExpect(jsonPath("$.ahead").value(0))
                .andExpect(jsonPath("$.passToken").doesNotExist());

        mvc.perform(get(Q + "/me").header("X-Member-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketNo").value(1));

        mvc.perform(delete(Q + "/me").header("X-Member-Id", "1")).andExpect(status().isNoContent());
        mvc.perform(delete(Q + "/me").header("X-Member-Id", "1")).andExpect(status().isNoContent());
        mvc.perform(get(Q + "/me").header("X-Member-Id", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUEUE_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mvc.perform(post(Q)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void 판매중이_아니면_409() throws Exception {
        mvc.perform(post("/api/v1/runs/999/queue").header("X-Member-Id", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_NOT_ON_SALE"));
    }

    @Test
    void 다른_운행_중복_진입은_409() throws Exception {
        runs.open(2002);
        mvc.perform(post(Q).header("X-Member-Id", "1")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/runs/2002/queue").header("X-Member-Id", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUEUE_DUPLICATED"));
    }

    @Test
    void 좌석_API는_토큰_없이_부르면_모두_403() throws Exception {
        mvc.perform(post(Q).header("X-Member-Id", "1")).andExpect(status().isOk());

        mvc.perform(get(SEATS).header("X-Member-Id", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUEUE_TOKEN_REQUIRED"));
        mvc.perform(get(SEATS).header("X-Member-Id", "1").header("X-Queue-Token", "fake"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUEUE_TOKEN_INVALID"));
        // 아예 줄을 안 선 회원
        mvc.perform(get(SEATS).header("X-Member-Id", "2").header("X-Queue-Token", "fake"))
                .andExpect(status().isForbidden());
        mvc.perform(get(SEATS)).andExpect(status().isUnauthorized());
    }

    @Test
    void 입장_후_받은_토큰으로_좌석_API가_열리고_이탈하면_다시_막힌다() throws Exception {
        mvc.perform(post(Q).header("X-Member-Id", "1")).andExpect(status().isOk());
        admitter.admit(RUN);

        JsonNode me = mapper.readTree(mvc.perform(get(Q + "/me").header("X-Member-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ADMITTED"))
                .andExpect(jsonPath("$.passToken").exists())
                .andExpect(jsonPath("$.passExpiresAt").exists())
                .andReturn().getResponse().getContentAsString());
        String token = me.get("passToken").asText();

        mvc.perform(get(SEATS).header("X-Member-Id", "1").header("X-Queue-Token", token))
                .andExpect(status().isOk());
        // 다른 회원이 훔쳐 써도 막힌다
        mvc.perform(get(SEATS).header("X-Member-Id", "2").header("X-Queue-Token", token))
                .andExpect(status().isForbidden());

        mvc.perform(delete(Q + "/me").header("X-Member-Id", "1")).andExpect(status().isNoContent());
        mvc.perform(get(SEATS).header("X-Member-Id", "1").header("X-Queue-Token", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUEUE_TOKEN_INVALID"));
    }
}
