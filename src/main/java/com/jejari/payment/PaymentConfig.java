package com.jejari.payment;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jejari.payment.pg.MockPgClient;
import com.jejari.payment.pg.PgClient;

/** 결제 빈. PG는 다른 구현(실제 PG 테스트 모드)을 빈으로 등록하면 Mock 대신 그걸 씁니다. */
@Configuration
public class PaymentConfig {

	@Bean
	@ConditionalOnMissingBean(PgClient.class)
	PgClient mockPgClient() {
		return new MockPgClient();
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
