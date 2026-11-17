package com.jejari.synthetic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.jejari.member.MemberAuthEnabled;

/**
 * DATA-02 생성기 실행 모드. 프로필 {@code synthetic}으로 켜면 생성(또는 삭제)만 하고 앱을 끈다.
 *
 * <pre>
 * 생성: gradlew bootRun --args="--spring.profiles.active=synthetic --jejari.synthetic.tag=syn1 --jejari.synthetic.members=1000"
 * 삭제: gradlew bootRun --args="--spring.profiles.active=synthetic --jejari.synthetic.tag=syn1 --jejari.synthetic.cleanup=true"
 * </pre>
 *
 * 생성 뒤 앱을 평소대로 켜면 1분 배치가 점수·케이스를 만들고, {@code loadtest/tools/eval_risk.sh syn1}로 평가한다.
 */
@Component
@Profile("synthetic")
@MemberAuthEnabled
public class SyntheticDataRunner implements ApplicationRunner {
	private static final Logger log = LoggerFactory.getLogger(SyntheticDataRunner.class);

	private final SyntheticDataService service;
	private final ApplicationContext context;
	private final String tag;
	private final int members;
	private final long seed;
	private final boolean cleanup;

	public SyntheticDataRunner(SyntheticDataService service, ApplicationContext context,
			@Value("${jejari.synthetic.tag:}") String tag, @Value("${jejari.synthetic.members:1000}") int members,
			@Value("${jejari.synthetic.seed:42}") long seed, @Value("${jejari.synthetic.cleanup:false}") boolean cleanup) {
		this.service = service;
		this.context = context;
		this.tag = tag;
		this.members = members;
		this.seed = seed;
		this.cleanup = cleanup;
	}

	@Override
	public void run(ApplicationArguments args) {
		int code = 0;
		try {
			if (cleanup) {
				log.info("가상 데이터 {} 삭제 완료: 회원 {}명", tag, service.cleanup(tag));
			} else {
				var r = service.generate(new SyntheticDataService.Spec(tag, members, seed));
				log.info("가상 데이터 생성 완료: 태그 {}, {} / 예약 {} · 취소 {} · 행동 로그 {}. 앱을 평소대로 켜고 1분 뒤 "
						+ "loadtest/tools/eval_risk.sh {} 로 평가하세요", r.tag(), r.counts(), r.reservations(),
						r.cancellations(), r.events(), r.tag());
			}
		} catch (RuntimeException e) {
			log.error("가상 데이터 작업 실패: {}", e.getMessage(), e);
			code = 1;
		}
		int exit = code;
		System.exit(SpringApplication.exit(context, () -> exit));
	}
}
