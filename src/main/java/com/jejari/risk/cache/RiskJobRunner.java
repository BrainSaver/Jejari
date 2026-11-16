package com.jejari.risk.cache;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * 위험 점수 작업 전용 실행기. 스레드 1개라 여기에 올린 작업(캐시 재동기화, 이후 1분 점수 배치)은 동시에 돌지 않아
 * 서로의 값을 덮어쓰지 않고, 앱 기본 스케줄러(대기열 입장 1초 주기)와 분리돼 서로 막지 않는다.
 *
 * <p>일부러 {@code TaskScheduler} 빈으로 등록하지 않는다. 그런 빈이 생기면 Spring Boot가 기본 스케줄러를 만들지 않고
 * 다른 {@code @Scheduled}(대기열 입장 등)까지 이 스레드로 몰린다.
 *
 * <p>점수 배치를 만들 때도 {@code @Scheduled} 대신 {@link #scheduleWithFixedDelay}로 올린다.
 */
@Component
public class RiskJobRunner implements DisposableBean {

	private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

	public RiskJobRunner() {
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("risk-");
		scheduler.setWaitForTasksToCompleteOnShutdown(true);
		scheduler.setAwaitTerminationSeconds(10);
		scheduler.initialize();
	}

	/** 지금 한 번 실행하고, 끝난 뒤 delay만큼 쉬고 다시 실행한다. 예외가 나도 다음 주기는 돈다 (작업 안에서 잡을 것). */
	public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
		return scheduler.scheduleWithFixedDelay(task, delay);
	}

	@Override
	public void destroy() {
		scheduler.shutdown();
	}
}
