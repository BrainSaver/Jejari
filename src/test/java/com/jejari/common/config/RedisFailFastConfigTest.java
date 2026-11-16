package com.jejari.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

import io.lettuce.core.ClientOptions;

/** Redis가 끊기면 명령을 쌓지 않고 바로 거절하는 설정이 클라이언트에 실제로 들어가는지. 인프라 없이 돕니다. */
class RedisFailFastConfigTest {

	@Test
	void 끊긴_상태의_명령은_거절하고_재연결은_계속한다() {
		var builder = LettuceClientConfiguration.builder();
		new RedisFailFastConfig().lettuceFailFast().customize(builder);

		ClientOptions options = builder.build().getClientOptions().orElseThrow();
		assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
		assertThat(options.isAutoReconnect()).isTrue();
	}
}
