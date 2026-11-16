package com.jejari.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;

/** Redis 설정 점검 판정 (Redis 없이 CONFIG GET 결과만 흉내). */
class RedisConfigVerifierTest {

	static Properties config(String policy, String appendonly, String fsync, String maxmemory) {
		Properties p = new Properties();
		if (policy != null) p.setProperty("maxmemory-policy", policy);
		if (appendonly != null) p.setProperty("appendonly", appendonly);
		if (fsync != null) p.setProperty("appendfsync", fsync);
		if (maxmemory != null) p.setProperty("maxmemory", maxmemory);
		return p;
	}

	RedisConfigVerifier verifierReturning(Properties all) {
		RedisServerCommands server = mock(RedisServerCommands.class);
		when(server.getConfig(anyString())).thenAnswer(inv -> {
			String key = inv.getArgument(0);
			Properties one = new Properties();
			if (all.getProperty(key) != null) one.setProperty(key, all.getProperty(key));
			return one;
		});
		RedisConnection connection = mock(RedisConnection.class);
		when(connection.serverCommands()).thenReturn(server);
		RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
		when(factory.getConnection()).thenReturn(connection);
		return new RedisConfigVerifier(factory);
	}

	@Test
	void compose_설정이면_통과() {
		verifierReturning(config("noeviction", "yes", "everysec", "536870912")).run(null);
	}

	@Test
	void 기본값_noeviction이_아니면_기동_거부() {
		assertThatThrownBy(() -> verifierReturning(config("allkeys-lru", "yes", "everysec", "536870912")).run(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("maxmemory-policy=allkeys-lru");
	}

	@Test
	void AOF가_꺼져_있으면_기동_거부() {
		assertThat(RedisConfigVerifier.problems(config("noeviction", "no", "everysec", "0")))
				.containsExactly("appendonly=no (필요: yes)");
	}

	@Test
	void appendfsync_always도_허용() {
		assertThat(RedisConfigVerifier.problems(config("noeviction", "yes", "always", "0"))).isEmpty();
	}

	@Test
	void 값을_못_읽으면_모두_문제로_본다() {
		assertThat(RedisConfigVerifier.problems(new Properties())).hasSize(3);
	}

	@Test
	void CONFIG_명령이_막혀_있으면_끄는_방법을_알려준다() {
		RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
		when(factory.getConnection()).thenThrow(new RuntimeException("ERR unknown command 'CONFIG'"));

		assertThatThrownBy(() -> new RedisConfigVerifier(factory).run(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("jejari.redis.verify-config=false");
	}
}
