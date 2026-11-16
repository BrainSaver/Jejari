package com.jejari.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class EventPolicyTest {

	final JdbcTemplate jdbc = mock(JdbcTemplate.class);

	@Test
	void DB_값을_쓴다() {
		when(jdbc.queryForList(any(String.class), eq(String.class), eq(EventPolicy.MAX_RETRY))).thenReturn(List.of(" 7 "));
		assertThat(new EventPolicy(jdbc, 0).getLong(EventPolicy.MAX_RETRY)).isEqualTo(7);
	}

	@Test
	void 행이_없으면_기본값을_쓴다() {
		when(jdbc.queryForList(any(String.class), eq(String.class), any())).thenReturn(List.of());
		assertThat(new EventPolicy(jdbc, 0).getLong(EventPolicy.RELAY_BATCH)).isEqualTo(500);
	}

	@Test
	void DB를_못_읽거나_숫자가_아니면_기본값을_쓴다() {
		when(jdbc.queryForList(any(String.class), eq(String.class), eq(EventPolicy.LOCK_WAIT_SEC)))
				.thenThrow(new DataAccessResourceFailureException("DB 끊김"));
		when(jdbc.queryForList(any(String.class), eq(String.class), eq(EventPolicy.MAX_RETRY)))
				.thenReturn(List.of("abc"));
		var policy = new EventPolicy(jdbc, 0);
		assertThat(policy.getLong(EventPolicy.LOCK_WAIT_SEC)).isEqualTo(5);
		assertThat(policy.getLong(EventPolicy.MAX_RETRY)).isEqualTo(5);
	}

	@Test
	void 캐시_시간_안에는_DB를_다시_읽지_않는다() {
		when(jdbc.queryForList(any(String.class), eq(String.class), any())).thenReturn(List.of("3"));
		var policy = new EventPolicy(jdbc, 60_000);
		policy.getLong(EventPolicy.MAX_RETRY);
		policy.getLong(EventPolicy.MAX_RETRY);
		verify(jdbc, times(1)).queryForList(any(String.class), eq(String.class), eq(EventPolicy.MAX_RETRY));
	}

	@Test
	void 모르는_키는_거절한다() {
		assertThatThrownBy(() -> new EventPolicy(jdbc, 0).getLong("outbox.typo"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
