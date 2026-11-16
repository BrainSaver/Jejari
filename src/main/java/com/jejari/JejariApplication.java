package com.jejari;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JejariApplication {

	/** 모든 시각은 Asia/Seoul 기준 (concurrency.md §0.7). */
	public static final String ZONE = "Asia/Seoul";

	public static void main(String[] args) {
		// JVM 옵션 -Duser.timezone을 빠뜨려도 같은 결과가 되도록 스프링 기동 전에 고정합니다.
		// Oracle JDBC는 연결할 때 JVM 기본 시간대를 세션 시간대로 씁니다.
		TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
		SpringApplication.run(JejariApplication.class, args);
	}

}
