package com.jejari.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * 기동 시 Redis 설정 점검 (concurrency.md §14.5).
 *
 * <p>Redis 기본 설정으로 돌면 메모리가 찰 때 seat:hold·idem·리스 키가 조용히 밀려나
 * 같은 좌석이 두 번 팔릴 수 있습니다. compose.yaml에만 맡기면 compose 없이 띄운 Redis(배포 서버 등)에서
 * 아무도 모르게 기본값으로 돌 수 있으므로, TimeZoneVerifier처럼 맞지 않으면 기동을 멈춥니다.
 *
 * <ul>
 *   <li>maxmemory-policy = noeviction (필수)</li>
 *   <li>appendonly = yes (필수, 재시작 후 키 복원)</li>
 *   <li>appendfsync = everysec 또는 always (필수)</li>
 *   <li>maxmemory = 0(무제한)이면 경고만. 메모리 부족이 쓰기 오류가 아니라 프로세스 종료로 나타납니다</li>
 * </ul>
 * CONFIG 명령을 막아 둔 관리형 Redis에서는 {@code jejari.redis.verify-config=false}로 끄고 콘솔에서 직접 확인합니다.
 */
@Component
@ConditionalOnProperty(name = "jejari.redis.verify-config", havingValue = "true", matchIfMissing = true)
public class RedisConfigVerifier implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(RedisConfigVerifier.class);

	static final Map<String, List<String>> REQUIRED = Map.of(
			"maxmemory-policy", List.of("noeviction"),
			"appendonly", List.of("yes"),
			"appendfsync", List.of("everysec", "always"));

	private final RedisConnectionFactory connectionFactory;

	public RedisConfigVerifier(RedisConnectionFactory connectionFactory) {
		this.connectionFactory = connectionFactory;
	}

	@Override
	public void run(ApplicationArguments args) {
		Properties config = new Properties();
		try (RedisConnection connection = connectionFactory.getConnection()) {
			for (String key : List.of("maxmemory-policy", "appendonly", "appendfsync", "maxmemory")) {
				config.putAll(connection.serverCommands().getConfig(key));
			}
		} catch (RuntimeException e) {
			throw new IllegalStateException("Redis 설정을 읽지 못했습니다 (CONFIG GET). "
					+ "CONFIG가 막힌 환경이면 jejari.redis.verify-config=false로 끄고 직접 확인하세요", e);
		}

		List<String> problems = problems(config);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Redis 설정 불일치 (concurrency.md §14.5): " + String.join(", ", problems));
		}
		if ("0".equals(config.getProperty("maxmemory"))) {
			log.warn("Redis maxmemory=0(무제한): noeviction이 쓰기 오류로 드러나지 않고 메모리 부족 시 프로세스가 종료될 수 있습니다");
		}
		log.info("Redis 설정 점검 통과: maxmemory-policy={}, appendonly={}, appendfsync={}",
				config.getProperty("maxmemory-policy"), config.getProperty("appendonly"),
				config.getProperty("appendfsync"));
	}

	/** 필수 값과 다른 항목 목록. 비어 있으면 통과. */
	static List<String> problems(Properties config) {
		List<String> problems = new ArrayList<>();
		REQUIRED.forEach((key, allowed) -> {
			String actual = config.getProperty(key);
			if (actual == null || !allowed.contains(actual.toLowerCase())) {
				problems.add(key + "=" + actual + " (필요: " + String.join(" 또는 ", allowed) + ")");
			}
		});
		problems.sort(null);
		return problems;
	}
}
