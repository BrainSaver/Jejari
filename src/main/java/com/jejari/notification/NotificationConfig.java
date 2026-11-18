package com.jejari.notification;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** 알림 빈. 스캐너·발송기는 테스트에서 끄고 {@code scanOnce()}·{@code dispatchOnce()}를 직접 부릅니다. */
@Configuration
public class NotificationConfig {

	@Bean
	NotificationPolicy notificationPolicy(JdbcTemplate jdbc) {
		return new NotificationPolicy(jdbc);
	}

	@Bean
	@ConditionalOnProperty(name = "jejari.notification.reminder-enabled", havingValue = "true", matchIfMissing = true)
	PayReminderScanner payReminderScanner(JdbcTemplate jdbc, NotificationPolicy policy, Clock clock) {
		return new PayReminderScanner(jdbc, policy, clock);
	}

	@Bean
	@ConditionalOnProperty(name = "jejari.notification.dispatcher-enabled", havingValue = "true", matchIfMissing = true)
	NotificationDispatcher notificationDispatcher(JdbcTemplate jdbc, NotificationPolicy policy) {
		return new NotificationDispatcher(jdbc, policy);
	}
}
