package com.jejari.common.config;

import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.lettuce.core.ClientOptions;

/**
 * Redis가 끊기면 명령을 큐에 쌓아 두지 않고 바로 실패시킵니다.
 *
 * <p>Lettuce 기본값은 재연결을 기다리며 명령을 쌓아 두고 60초 뒤에야 실패해서, Redis가 잠깐만 끊겨도
 * 요청마다 서버 스레드를 붙잡아 앱 전체가 멈춥니다. 재연결은 계속 시도하므로 Redis가 돌아오면 앱 재시작 없이 복구됩니다.
 * 실패는 RedisExceptionHandler가 503으로 바꿉니다. 응답 대기 시간은 application.yml의 spring.data.redis.timeout입니다.
 */
@Configuration
public class RedisFailFastConfig {

	@Bean
	LettuceClientConfigurationBuilderCustomizer lettuceFailFast() {
		return builder -> builder.clientOptions(ClientOptions.builder()
				.autoReconnect(true)
				.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
				.build());
	}
}
