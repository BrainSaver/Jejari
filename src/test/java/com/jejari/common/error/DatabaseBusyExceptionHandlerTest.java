package com.jejari.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DB 연결을 얻지 못하면 503 + Retry-After, 그 밖의 DB 오류는 지금처럼 500 (11-23 강다인 님 제보: 가입 몰림에 500).
 * 예외 모양은 Hikari가 풀 대기 시간을 넘길 때 실제로 던지는 것과 같게 만든다. 인프라 없이 돕니다.
 */
class DatabaseBusyExceptionHandlerTest {

	/** HikariPool이 connection-timeout(5초)을 넘기면 던지는 예외 */
	static SQLTransientConnectionException hikariTimeout() {
		return new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 5000ms.");
	}

	private final DatabaseBusyExceptionHandler handler = new DatabaseBusyExceptionHandler();

	@Test
	void JdbcTemplate에서_풀이_바닥나면_503_재시도_안내() {
		var res = handler.handle(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", hikariTimeout()));
		assertThat(res.getStatusCode().value()).isEqualTo(503);
		assertThat(res.getBody().code()).isEqualTo("SERVICE_UNAVAILABLE");
		assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("2");
	}

	@Test
	void 트랜잭션을_시작하다_풀이_바닥나도_503() {
		var e = new CannotCreateTransactionException("Could not open JDBC Connection for transaction", hikariTimeout());
		assertThat(handler.handle(e).getStatusCode().value()).isEqualTo(503);
	}

	@Test
	void JPA_경로도_503() {
		var e = new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
				new JDBCConnectionException("Unable to acquire JDBC Connection", hikariTimeout()));
		assertThat(handler.handle(e).getStatusCode().value()).isEqualTo(503);
	}

	@Test
	void 제약_위반_같은_DB_오류는_숨기지_않고_500() {
		var dup = handler.handle(new DuplicateKeyException("ORA-00001: unique constraint violated"));
		assertThat(dup.getStatusCode().value()).isEqualTo(500);
		assertThat(dup.getBody().code()).isEqualTo("INTERNAL_ERROR");
		assertThat(dup.getHeaders().getFirst("Retry-After")).isNull();
		var fk = new DataIntegrityViolationException("ORA-02291", new SQLException("ORA-02291: integrity constraint violated"));
		assertThat(handler.handle(fk).getStatusCode().value()).isEqualTo(500);
	}

	@Test
	void 연결과_상관없는_원인의_트랜잭션_시작_실패는_500() {
		var e = new CannotCreateTransactionException("Could not open JDBC Connection", new IllegalStateException("설정 오류"));
		assertThat(handler.handle(e).getStatusCode().value()).isEqualTo(500);
	}

	// ── 실제 요청 경로: 컨트롤러 → 예외 처리기 순서 (Redis 처리기 → DB 처리기 → 일반 처리기) ──────────

	@RestController
	static class Boom {
		@GetMapping("/pool")
		String pool() {
			throw new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", hikariTimeout());
		}

		@GetMapping("/dup")
		String dup() {
			throw new DuplicateKeyException("ORA-00001");
		}

		@GetMapping("/redis-timeout")
		String redisTimeout() {
			throw new QueryTimeoutException("Command timed out");
		}
	}

	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Boom())
			.setControllerAdvice(new GlobalExceptionHandler(), new RedisExceptionHandler(), new DatabaseBusyExceptionHandler())
			.build();

	@Test
	void 요청_경로에서_풀_바닥은_503과_Retry_After() throws Exception {
		mvc.perform(get("/pool")).andExpect(status().isServiceUnavailable())
				.andExpect(header().string("Retry-After", "2"))
				.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
	}

	@Test
	void 요청_경로에서_다른_DB_오류는_500() throws Exception {
		mvc.perform(get("/dup")).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
	}

	@Test
	void Redis_응답_지연은_지금처럼_Redis_처리기가_503() throws Exception {
		mvc.perform(get("/redis-timeout")).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
	}
}
