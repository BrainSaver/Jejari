package com.jejari.queue.dev;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 개발 모드(jejari.dev-mode=true)에서만 켜지는 가짜 회원·운행 연동. 운영에서는 이 설정이 통째로 빠집니다.
 * 회원은 모두 ACTIVE, 지정한 운행(jejari.queue.dev-on-sale-runs=9001,9002)만 판매 중으로 봅니다.
 * 실제 구현(MemberIdentityPort·OnSaleRunProvider 빈)이 생기기 전까지 대기열을 켜 보려는 용도입니다.
 */
@Configuration
@ConditionalOnProperty(name = "jejari.dev-mode", havingValue = "true")
public class DevStubsConfig {

	@Bean
	DevMemberIdentityPort devMemberIdentityPort() {
		return new DevMemberIdentityPort();
	}

	@Bean
	DevOnSaleRunProvider devOnSaleRunProvider(@Value("${jejari.queue.dev-on-sale-runs:}") String runs) {
		DevOnSaleRunProvider provider = new DevOnSaleRunProvider();
		for (String s : runs.split(",")) {
			if (!s.isBlank()) {
				provider.open(Long.parseLong(s.trim()));
			}
		}
		return provider;
	}
}
