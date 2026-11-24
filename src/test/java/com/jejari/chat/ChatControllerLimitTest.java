package com.jejari.chat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.jejari.common.error.BusinessException;
import com.jejari.member.web.ClientIpResolver;

/** IP당 분당·하루 질문 수 제한 */
class ChatControllerLimitTest {

	/** 시험 중에 시각을 앞으로 돌릴 수 있는 시계 */
	static class MovableClock extends Clock {
		Instant now = Instant.parse("2026-11-24T00:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneId.of("Asia/Seoul");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private final MovableClock clock = new MovableClock();
	private final ChatController c = new ChatController(mock(ChatService.class), mock(ClientIpResolver.class), clock);

	@Test
	void 분당_한도를_넘으면_막고_1분_뒤엔_다시_된다() {
		for (int i = 0; i < ChatController.PER_MINUTE; i++) {
			c.allow("1.1.1.1");
		}
		assertThatThrownBy(() -> c.allow("1.1.1.1")).isInstanceOf(BusinessException.class);
		assertThatCode(() -> c.allow("2.2.2.2")).doesNotThrowAnyException(); // 다른 IP는 따로 센다

		clock.now = clock.now.plus(Duration.ofSeconds(61));
		assertThatCode(() -> c.allow("1.1.1.1")).doesNotThrowAnyException();
	}

	@Test
	void 하루_한도를_넘으면_분당_여유가_있어도_막고_하루_뒤엔_다시_된다() {
		for (int i = 0; i < ChatController.PER_DAY; i++) {
			if (i > 0 && i % ChatController.PER_MINUTE == 0) {
				clock.now = clock.now.plus(Duration.ofSeconds(61));
			}
			c.allow("3.3.3.3");
		}
		clock.now = clock.now.plus(Duration.ofSeconds(61));
		assertThatThrownBy(() -> c.allow("3.3.3.3")).isInstanceOf(BusinessException.class)
				.hasMessageContaining("오늘");

		clock.now = clock.now.plus(Duration.ofDays(1));
		assertThatCode(() -> c.allow("3.3.3.3")).doesNotThrowAnyException();
	}
}
