package com.jejari.common.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.jejari.JejariApplication;

/**
 * 앱 시계. 마감 시각·토큰 만료는 이 시계 하나로 계산합니다 (concurrency.md §0.7).
 * {@code LocalDateTime.now()} 대신 {@code LocalDateTime.now(clock)}을 쓰면 테스트에서 시간을 고정할 수 있습니다.
 */
@Configuration
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.system(ZoneId.of(JejariApplication.ZONE));
	}
}
