package com.jejari.queue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.jejari.queue.config.QueueConfig;
import com.jejari.support.NoRunningApp;
import com.jejari.support.WebSecurityTestConfig;

/**
 * 실제 Redis에 붙는 대기열 통합 테스트의 공통 설정. DB·Kafka 없이 Redis만 씁니다.
 *
 * <p>{@code docker compose up -d} 뒤 {@code ./gradlew infraTest}로 돌립니다 (기본 {@code test}에서는 제외, CI는 인프라 없음).
 * Redis 주소·비밀번호는 application.yml(.env)을 따르고, 데이터는 15번 DB만 씁니다 (테스트마다 비움).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("infra")
@Tag("no-oracle") // Oracle 없이 돈다 → CI에서 push마다 (infraTest -PnoOracle, .github/workflows/infra-test.yml)
@ExtendWith(NoRunningApp.class)
@DataRedisTest
@Import({QueueConfig.class, QueueTestBeans.class, WebSecurityTestConfig.ClockBean.class})
@TestPropertySource(properties = {
		"jejari.queue.scheduler-enabled=false",
		"jejari.queue.policy-cache-ms=0", // 테스트에서 정책을 바꾸면 바로 보이게 (캐시는 PolicyProviderTest가 따로 확인)
		"spring.data.redis.database=15"})
public @interface QueueInfraTest {
}
