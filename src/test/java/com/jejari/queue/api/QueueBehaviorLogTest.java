package com.jejari.queue.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.jejari.auth.ActorType;
import com.jejari.auth.AuthPrincipal;
import com.jejari.behavior.BehaviorType;
import com.jejari.common.error.BusinessException;
import com.jejari.queue.QueueErrorCode;
import com.jejari.queue.domain.QueueService;
import com.jejari.queue.domain.QueueStatus;
import com.jejari.queue.domain.QueueStatus.State;

/**
 * 대기열 진입이 행동 로그(QUEUE_ENTER)를 남기는지. 컨트롤러를 직접 불러 요청에 표시된 내용을 본다
 * (이벤트를 Kafka로 보내는 부분은 behavior 모듈의 인터셉터가 맡고 거기서 테스트한다).
 * 중요한 것은 거절되어 예외로 끝난 요청도 표시가 남는다는 점이다.
 */
class QueueBehaviorLogTest {

	/** BehaviorLog.ATTR 값. 접근 제한자가 패키지 전용이라 같은 문자열로 읽는다 */
	private static final String ATTR = "com.jejari.behavior.BehaviorLog.pending";
	private static final AuthPrincipal ME = new AuthPrincipal(ActorType.MEMBER, 7, "s-1");

	private final QueueService queue = mock(QueueService.class);
	private final QueueController controller = new QueueController(queue);
	private final MockHttpServletRequest request = new MockHttpServletRequest();

	@BeforeEach
	void bind() {
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}

	@AfterEach
	void unbind() {
		RequestContextHolder.resetRequestAttributes();
	}

	private static QueueStatus waiting(long ticketNo) {
		return new QueueStatus(State.WAITING, 9001, ticketNo, 3L, 2L, 10L, 2, null, (OffsetDateTime) null);
	}

	private Object pending() {
		return request.getAttribute(ATTR);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> meta(Object pending, String method) throws Exception {
		return (Map<String, Object>) call(pending, method);
	}

	private static Object call(Object target, String method) throws Exception {
		Method m = target.getClass().getDeclaredMethod(method);
		m.setAccessible(true);
		return m.invoke(target);
	}

	@Test
	void 진입에_성공하면_번호표를_담아_표시한다() throws Exception {
		when(queue.enter(7, 9001)).thenReturn(waiting(42));

		controller.enter(9001, ME);

		Object p = pending();
		assertThat(p).isNotNull();
		assertThat(call(p, "type")).isEqualTo(BehaviorType.QUEUE_ENTER);
		assertThat(call(p, "runId")).isEqualTo(9001L);
		assertThat(meta(p, "meta")).containsEntry("ticketNo", 42L).containsEntry("rejected", false);
	}

	@Test
	void 중복_진입으로_거절돼도_예외를_던지기_전에_표시한다() throws Exception {
		when(queue.enter(7, 9001)).thenThrow(new BusinessException(QueueErrorCode.QUEUE_DUPLICATED));

		assertThatThrownBy(() -> controller.enter(9001, ME)).isInstanceOf(BusinessException.class);

		Object p = pending();
		assertThat(p).as("거절된 요청도 행동 로그에 남아야 한다").isNotNull();
		assertThat(call(p, "type")).isEqualTo(BehaviorType.QUEUE_ENTER);
		assertThat(meta(p, "meta")).containsEntry("rejected", true).doesNotContainKey("ticketNo");
	}

	@Test
	void 중복이_아닌_거절은_rejected가_false이고_예외는_그대로_전달된다() throws Exception {
		BusinessException original = new BusinessException(QueueErrorCode.RUN_NOT_ON_SALE);
		when(queue.enter(7, 9001)).thenThrow(original);

		assertThatThrownBy(() -> controller.enter(9001, ME)).isSameAs(original);

		assertThat(meta(pending(), "meta")).containsEntry("rejected", false);
	}

	@Test
	void 번호표_조회와_이탈은_행동_로그를_남기지_않는다() throws Exception {
		when(queue.status(7, 9001)).thenReturn(waiting(42));

		controller.me(9001, ME);
		controller.leave(9001, ME);

		assertThat(pending()).isNull();
	}
}
