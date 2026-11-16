package com.jejari.queue;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jejari.auth.ActorType;
import com.jejari.auth.jwt.AccessTokenIssuer;
import com.jejari.common.error.BusinessException;
import com.jejari.queue.api.QueueController;
import com.jejari.queue.domain.QueuePassService;
import com.jejari.queue.domain.QueueService;
import com.jejari.queue.domain.QueueStatus;
import com.jejari.support.WebSecurityTestConfig;

/** 대기열 API의 인증·응답 형식. 서비스는 목이라 Redis 없이 돕니다. */
@WebMvcTest(controllers = QueueController.class)
@WebSecurityTestConfig
class QueueControllerTest {

	static final String SID = "3f2b8c1e-1111-4222-8333-444455556666";

	@Autowired
	MockMvc mvc;

	@Autowired
	AccessTokenIssuer issuer;

	@MockitoBean
	QueueService queue;

	@MockitoBean
	QueuePassService pass; // 좌석 토큰 인터셉터(QueueWebConfig)가 필요로 함

	String bearer(ActorType type, long id) {
		return "Bearer " + issuer.issue(type, id, SID).value();
	}

	private static QueueStatus waiting(long rank) {
		return new QueueStatus(QueueStatus.State.WAITING, 1001, rank, rank, rank - 1, 3L, 1, null, null);
	}

	@Test
	void 로그인하지_않으면_401() throws Exception {
		mvc.perform(post("/api/v1/runs/1001/queue")).andExpect(status().isUnauthorized());
		verify(queue, never()).enter(anyLong(), anyLong());
	}

	@Test
	void 회원_ID는_토큰에서_꺼내고_번호표를_돌려준다() throws Exception {
		when(queue.enter(1001, 7)).thenReturn(waiting(5));

		mvc.perform(post("/api/v1/runs/7/queue").header(HttpHeaders.AUTHORIZATION, bearer(ActorType.MEMBER, 1001)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("WAITING"))
				.andExpect(jsonPath("$.rank").value(5))
				.andExpect(jsonPath("$.ahead").value(4))
				.andExpect(jsonPath("$.estimatedWaitSec").value(3))
				.andExpect(jsonPath("$.passToken").doesNotExist());
	}

	@Test
	void 관리자_토큰은_회원_API에_들어올_수_없다() throws Exception {
		mvc.perform(post("/api/v1/runs/7/queue").header(HttpHeaders.AUTHORIZATION, bearer(ActorType.ADMIN, 1)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		verify(queue, never()).enter(anyLong(), anyLong());
	}

	@Test
	void 업무_오류는_코드와_상태로_바뀐다() throws Exception {
		when(queue.enter(1001, 9)).thenThrow(new BusinessException(QueueErrorCode.RUN_NOT_ON_SALE));
		when(queue.status(1001, 9)).thenThrow(new BusinessException(QueueErrorCode.QUEUE_NOT_FOUND));

		String auth = bearer(ActorType.MEMBER, 1001);
		mvc.perform(post("/api/v1/runs/9/queue").header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("RUN_NOT_ON_SALE"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
		mvc.perform(get("/api/v1/runs/9/queue/me").header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("QUEUE_NOT_FOUND"));
	}

	@Test
	void 이탈은_204() throws Exception {
		mvc.perform(delete("/api/v1/runs/7/queue/me").header(HttpHeaders.AUTHORIZATION, bearer(ActorType.MEMBER, 1001)))
				.andExpect(status().isNoContent());
		verify(queue).leave(1001, 7);
	}
}
