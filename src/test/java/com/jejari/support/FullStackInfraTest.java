package com.jejari.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * 앱 전체(Oracle·Redis·MongoDB)를 띄우는 통합 테스트의 공통 설정. {@code docker compose up -d} 뒤 {@code ./gradlew infraTest}.
 * Oracle은 최신 DDL·시드로 만들어져 있어야 한다 (시드의 상시 판매 운행 {@link #ON_SALE_RUN}을 쓴다).
 *
 * <p>이 애너테이션을 쓰는 테스트는 <b>Spring 컨텍스트 하나를 같이 쓴다</b>. 테스트마다 속성·설정이 조금씩 다르면 컨텍스트가
 * 따로 떠서 끝날 때까지 메모리에 남고, 메모리가 작은 PC에서는 다른 통합 테스트가 느려져 시간 초과가 난다.
 * 그래서 설정을 바꾸지 말고 여기에 모은다.
 *
 * <ul>
 *   <li>Redis는 15번 DB만 쓴다 (각 테스트가 시작할 때 비움)</li>
 *   <li>대기열 입장 스케줄러·위험 점수 재동기화·위험 점수 배치 주기 실행은 끈다 (테스트가 직접 부름)</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("infra")
@SpringBootTest(properties = {
		"spring.data.redis.database=15",
		"jejari.queue.scheduler-enabled=false",
		"jejari.risk.cache-resync-enabled=false",
		"jejari.risk.batch-enabled=false",
		"jejari.train.sale-open-scheduler-enabled=false"})
@AutoConfigureMockMvc
@Import(BehaviorProbeController.class) // 행동 로그 통합 테스트용 엔드포인트 (테스트에만 있음)
public @interface FullStackInfraTest {

	/** 시드(db/seed)의 상시 ON_SALE 운행 */
	long ON_SALE_RUN = 9001L;
}
