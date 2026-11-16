package com.jejari.behavior;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.member.web.ClientIpResolver;

/**
 * 행동 로그 인터셉터를 API 전체에 건다. 이벤트는 {@link BehaviorLog#mark}를 부른 요청만 만들어진다.
 *
 * <p>필요한 빈은 요청이 올 때 꺼낸다(ObjectProvider). 다른 파트의 {@code @WebMvcTest}에서 이 설정이 함께 읽혀도
 * 행동 로그 빈이 없어 깨지지 않게 하기 위해서다 (QueueWebConfig와 같은 방식).
 */
@Configuration
public class BehaviorWebConfig implements WebMvcConfigurer {

	private final ObjectProvider<BehaviorPublisher> publisher;
	private final ObjectProvider<Pseudonymizer> pseudonymizer;
	private final ObjectProvider<ClientIpResolver> ipResolver;
	private final ObjectProvider<Clock> clock;

	public BehaviorWebConfig(ObjectProvider<BehaviorPublisher> publisher, ObjectProvider<Pseudonymizer> pseudonymizer,
			ObjectProvider<ClientIpResolver> ipResolver, ObjectProvider<Clock> clock) {
		this.publisher = publisher;
		this.pseudonymizer = pseudonymizer;
		this.ipResolver = ipResolver;
		this.clock = clock;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		ClientIpResolver ip = ipResolver.getIfAvailable(() -> new ClientIpResolver(""));
		Clock c = clock.getIfAvailable(Clock::systemDefaultZone);
		registry.addInterceptor(new BehaviorInterceptor(publisher, pseudonymizer, ip, c)).addPathPatterns("/api/**");
	}
}
