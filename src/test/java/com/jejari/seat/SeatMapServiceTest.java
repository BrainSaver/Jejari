package com.jejari.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import com.jejari.common.config.AppPolicy;
import com.jejari.train.TrainLayoutReader;
import com.jejari.train.TrainLayoutReader.Layout;
import com.jejari.train.TrainLayoutReader.SeatInfo;

import io.lettuce.core.RedisException;

/** 남은 좌석 수: Redis가 안 되면 DB로 센다 (검색·운행 상세가 Redis 장애 중에도 돌게). 인프라 없이 돕니다. */
class SeatMapServiceTest {

	@SuppressWarnings("unchecked")
	private final HashOperations<String, Object, Object> hash = mock(HashOperations.class);
	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final TrainLayoutReader layouts = mock(TrainLayoutReader.class);
	private final SeatMapService service = new SeatMapService(layouts, redis, jdbc, mock(AppPolicy.class));

	SeatMapServiceTest() {
		when(redis.<Object, Object>opsForHash()).thenReturn(hash);
		Layout layout = mock(Layout.class);
		when(layout.seats()).thenReturn(List.of(
				new SeatInfo(1, 1, "1A", "GENERAL", true), new SeatInfo(2, 1, "1B", "GENERAL", false),
				new SeatInfo(3, 1, "1C", "GENERAL", false), new SeatInfo(4, 2, "1A", "FIRST", true)));
		when(layouts.layout(1L)).thenReturn(layout);
	}

	@Test
	void Redis가_끊기면_DB의_좌석_행으로_남은_좌석을_센다() throws Exception {
		when(hash.entries(anyString())).thenThrow(new RedisConnectionFailureException("Unable to connect"));
		dbRows(new long[] {1, 4}, new String[] {"SOLD", "HOLD"});

		assertThat(service.remainingByGrade(9001, 1)).containsEntry("GENERAL", 2).containsEntry("FIRST", 0);
	}

	@Test
	void 연결과_상관없는_Redis_오류는_숨기지_않는다() {
		when(hash.entries(anyString())).thenThrow(new RedisSystemException("Redis exception", new RedisException("ERR wrong type")));

		assertThatThrownBy(() -> service.remainingByGrade(9001, 1)).isInstanceOf(RedisSystemException.class);
	}

	private void dbRows(long[] seats, String[] statuses) throws Exception {
		doAnswer(inv -> {
			RowCallbackHandler h = inv.getArgument(1);
			for (int i = 0; i < seats.length; i++) {
				ResultSet rs = mock(ResultSet.class);
				when(rs.getLong(1)).thenReturn(seats[i]);
				when(rs.getString(2)).thenReturn(statuses[i]);
				h.processRow(rs);
			}
			return null;
		}).when(jdbc).query(anyString(), any(RowCallbackHandler.class), eq(9001L));
	}
}
