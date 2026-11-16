package com.jejari.queue.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.jejari.queue.domain.QueuePassService;

/**
 * 좌석 API(배치도 등)에 입장 토큰 검사를 붙입니다.
 *
 * <p>검증 서비스는 요청이 올 때 꺼내 씁니다(ObjectProvider). 대기열 빈이 없는 웹 테스트(@WebMvcTest)에서도
 * 이 설정이 함께 읽히므로, 생성 시점에 빈을 요구하면 다른 파트의 테스트가 깨집니다.
 */
@Configuration
public class QueueWebConfig implements WebMvcConfigurer {

	private final ObjectProvider<QueuePassService> pass;

	public QueueWebConfig(ObjectProvider<QueuePassService> pass) {
		this.pass = pass;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new QueueSeatTokenInterceptor(pass))
				.addPathPatterns("/api/v1/runs/*/seats", "/api/v1/runs/*/seats/**");
	}
}
