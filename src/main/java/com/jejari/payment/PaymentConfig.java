package com.jejari.payment;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jejari.payment.pg.HttpPgClient;
import com.jejari.payment.pg.MockPgClient;
import com.jejari.payment.pg.PgClient;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * 결제 빈. PG는 {@code jejari.payment.pg.mode}로 고른다.
 * <ul>
 *   <li>{@code mock} (기본): 같은 JVM 안의 {@link MockPgClient}. 0초 만에 답한다 (테스트·시연)</li>
 *   <li>{@code http}: {@link HttpPgClient}로 {@code base-url}의 PG를 네트워크로 부른다. 지금은 가짜 PG 서버
 *       ({@code loadtest/mock-pg/server.js}) — 느린 PG·몰림·응답 없음을 재현해 부하 시험에 쓴다. 실제 PG도 이 자리에 붙는다</li>
 * </ul>
 * 다른 PgClient 빈을 등록하면(테스트) 그걸 쓴다.
 */
@Configuration
public class PaymentConfig {

	@Bean
	@ConditionalOnMissingBean(PgClient.class)
	PgClient pgClient(@Value("${jejari.payment.pg.mode:mock}") String mode,
			@Value("${jejari.payment.pg.base-url:http://127.0.0.1:8090}") String baseUrl,
			@Value("${jejari.payment.pg.connect-timeout-ms:1000}") long connectMs,
			@Value("${jejari.payment.pg.read-timeout-ms:3000}") long readMs, JsonMapper json, MeterRegistry meters) {
		return switch (mode) {
			case "mock" -> new MockPgClient();
			case "http" -> new HttpPgClient(baseUrl, Duration.ofMillis(connectMs), Duration.ofMillis(readMs), json, meters);
			default -> throw new IllegalStateException("jejari.payment.pg.mode는 mock 또는 http: " + mode);
		};
	}

	/** PG 동시 승인 상한. max-concurrent 0 이하면 끔 (기본) */
	@Bean
	PgBulkhead pgBulkhead(@Value("${jejari.payment.pg.max-concurrent:0}") int maxConcurrent,
			@Value("${jejari.payment.pg.acquire-wait-ms:0}") long waitMs,
			@Value("${jejari.payment.pg.busy-retry-after-sec:3}") long retryAfterSec, MeterRegistry meters) {
		return new PgBulkhead(maxConcurrent, waitMs, retryAfterSec, meters);
	}

	@Bean
	PaymentPolicy paymentPolicy(JdbcTemplate jdbc) {
		return new PaymentPolicy(jdbc);
	}

	/** PAY-04. 테스트에서는 끄고 {@link PaymentRecheckScanner#scanOnce()}를 직접 부릅니다 */
	@Bean
	@ConditionalOnProperty(name = "jejari.payment.recheck-scanner-enabled", havingValue = "true", matchIfMissing = true)
	PaymentRecheckScanner paymentRecheckScanner(JdbcTemplate jdbc, PgClient pg, PaymentSaga saga, PaymentPolicy policy,
			Clock clock) {
		return new PaymentRecheckScanner(jdbc, pg, saga, policy, clock);
	}
}
