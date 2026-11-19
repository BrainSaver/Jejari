package com.jejari.admin.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.admin.policy.PolicyAdminService.Policy;
import com.jejari.admin.policy.PolicyAdminService.UpdateCommand;
import com.jejari.common.error.BusinessException;
import com.jejari.common.policy.PolicyBounds;

/** ADM-08 저장 뒤 처리: Redis 캐시 실패를 숨기지 않기, 이력 번호 겹침 재시도, 캐시에 넣는 값 */
class PolicyAdminServiceTest {

	static final String KEY = "replay.batch_sec";

	JdbcTemplate jdbc;
	TransactionTemplate tx;
	StringRedisTemplate redis;
	HashOperations<String, Object, Object> hash;
	PolicyAdminService service;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		jdbc = mock(JdbcTemplate.class);
		tx = mock(TransactionTemplate.class);
		redis = mock(StringRedisTemplate.class);
		hash = mock(HashOperations.class);
		when(redis.<Object, Object>opsForHash()).thenReturn(hash);
		when(hash.entries(anyString())).thenReturn(Map.of());
		// 트랜잭션은 그 자리에서 실행 (예외는 그대로 밖으로)
		org.mockito.Mockito.doAnswer(inv -> {
			((Consumer<TransactionStatus>) inv.getArgument(0)).accept(mock(TransactionStatus.class));
			return null;
		}).when(tx).executeWithoutResult(any());
		// 목록 조회: 현재 값 30, 버전 4
		when(jdbc.query(contains("FROM app_policy ORDER BY"), any(RowMapper.class))).thenReturn(List.of(
				new Policy(KEY, "replay", "30", null, 4, "NUMBER", "설명", null, LocalDateTime.now())));
		// 조건부 UPDATE 1행
		when(jdbc.update(contains("UPDATE app_policy"), any(), any(), any(), any())).thenReturn(1);
		when(jdbc.queryForObject(contains("MAX(history_id)"), eq(Long.class))).thenReturn(7L, 8L, 9L);
		when(jdbc.queryForObject(contains("SELECT policy_value FROM app_policy"), eq(String.class), eq(KEY)))
				.thenReturn("45");
		// 이 키는 30~60만 받는 모듈이 있다고 가정
		PolicyBounds module = () -> Map.of(KEY, new PolicyBounds.Range(30, 60));
		service = new PolicyAdminService(jdbc, tx, redis, List.of(module));
	}

	@Test
	void Redis_반영이_실패하면_조용한_500이_아니라_DB는_저장됐다고_알린다() {
		doThrow(new RedisConnectionFailureException("down")).when(hash).put(anyString(), any(), any());

		assertThatThrownBy(() -> service.update(1L, KEY, new UpdateCommand("45", 4L)))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode().code())
				.isEqualTo("POLICY_CACHE_NOT_APPLIED");
		// DB 쪽은 실행됨 (UPDATE + 이력)
		verify(jdbc).update(contains("UPDATE app_policy"), any(), any(), any(), any());
		verify(jdbc).update(contains("INSERT INTO app_policy_history"), any(), any(), any(), any(), any(), any());
	}

	@Test
	void 캐시에는_요청값이_아니라_커밋된_DB_값을_넣는다() {
		service.update(1L, KEY, new UpdateCommand(" 45 ", 4L));

		verify(hash).put(PolicyAdminService.CACHE, KEY, "45");
	}

	@Test
	void 이력_번호가_겹치면_다음_번호로_다시_넣는다() {
		when(jdbc.update(contains("INSERT INTO app_policy_history"), any(), any(), any(), any(), any(), any()))
				.thenThrow(new DuplicateKeyException("pk"))
				.thenReturn(1);

		service.update(1L, KEY, new UpdateCommand("45", 4L));

		verify(jdbc, times(2)).update(contains("INSERT INTO app_policy_history"), any(), any(), any(), any(), any(), any());
		verify(hash).put(PolicyAdminService.CACHE, KEY, "45");
	}

	@Test
	void 이력_번호가_계속_겹치면_포기하고_예외를_던진다() {
		when(jdbc.update(contains("INSERT INTO app_policy_history"), any(), any(), any(), any(), any(), any()))
				.thenThrow(new DuplicateKeyException("pk"));

		assertThatThrownBy(() -> service.update(1L, KEY, new UpdateCommand("45", 4L)))
				.isInstanceOf(DuplicateKeyException.class);
		verify(jdbc, times(PolicyAdminService.HISTORY_RETRY))
				.update(contains("INSERT INTO app_policy_history"), any(), any(), any(), any(), any(), any());
		assertThat(org.mockito.Mockito.mockingDetails(hash).getInvocations().stream()
				.noneMatch(i -> i.getMethod().getName().equals("put"))).isTrue();
	}

	@Test
	void 모듈이_알려_준_범위_밖이면_저장하지_않는다() {
		// 2026-11-19: 10000을 저장했더니 앱은 범위 밖이라 기본값을 써서 관리자만 바뀐 줄 알았던 것
		assertThatThrownBy(() -> service.update(1L, KEY, new UpdateCommand("10000", 4L)))
				.isInstanceOf(BusinessException.class).hasMessageContaining("30~60");
		assertThatThrownBy(() -> service.update(1L, KEY, new UpdateCommand("29", 4L)))
				.isInstanceOf(BusinessException.class);
		verify(jdbc, times(0)).update(contains("UPDATE app_policy"), any(), any(), any(), any());
	}

	@Test
	void 범위가_있는_키에는_소수를_넣을_수_없다() {
		assertThatThrownBy(() -> service.update(1L, KEY, new UpdateCommand("45.5", 4L)))
				.isInstanceOf(BusinessException.class).hasMessageContaining("정수");
	}

	@Test
	void 범위_끝값은_받는다() {
		service.update(1L, KEY, new UpdateCommand("60", 4L));
		verify(jdbc).update(contains("UPDATE app_policy"), eq("60"), eq(1L), eq(KEY), eq(4L));
	}

	@Test
	void 범위를_알려_준_모듈이_없는_키는_형식만_본다() {
		PolicyAdminService.checkRange("queue.admit_per_sec", "100000", null);
	}

	@Test
	void 지금_정수인_키에는_소수를_넣을_수_없다() {
		Policy admit = new Policy("queue.admit_per_sec", "queue", "200", null, 1, "NUMBER", "", null, null);
		assertThatThrownBy(() -> PolicyAdminService.checkKeepsInteger(admit, "200.5"))
				.isInstanceOf(BusinessException.class).hasMessageContaining("정수");
		PolicyAdminService.checkKeepsInteger(admit, "300");
	}

	@Test
	void 지금_소수인_키는_소수를_받는다() {
		Policy ratio = new Policy("replay.full_record_ratio", "replay", "0.10", null, 1, "NUMBER", "", null, null);
		PolicyAdminService.checkKeepsInteger(ratio, "0.25");
		PolicyAdminService.checkKeepsInteger(ratio, "1");
	}
}
