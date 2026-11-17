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
import com.jejari.risk.batch.RiskDirtyQueue;
import com.jejari.risk.batch.RiskScoreBatch;

/**
 * DATA-02 생성기 실행 모드. 프로필 {@code synthetic}으로 켜면 생성(또는 삭제)만 하고 앱을 끈다.
 *
 * <pre>
 * 생성: gradlew bootRun --args="--spring.profiles.active=synthetic --jejari.synthetic.tag=syn1 --jejari.synthetic.members=1000"
 * 점수만: gradlew bootRun --args="--spring.profiles.active=synthetic --jejari.synthetic.score-only=true"
 * 삭제: gradlew bootRun --args="--spring.profiles.active=synthetic --jejari.synthetic.tag=syn1 --jejari.synthetic.cleanup=true"
 * </pre>
 *
 * 생성이 끝나면 <b>위험 점수 배치를 그 자리에서 다 돌린다</b> (앱을 따로 켜고 1분씩 기다리지 않게). 바로
 * {@code loadtest/tools/eval_risk.sh syn1}로 평가할 수 있다.
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
	private final boolean scoreOnly;
	private final RiskScoreBatch batch;
	private final RiskDirtyQueue dirty;

	public SyntheticDataRunner(SyntheticDataService service, ApplicationContext context, RiskScoreBatch batch,
			RiskDirtyQueue dirty, @Value("${jejari.synthetic.score-only:false}") boolean scoreOnly,
			@Value("${jejari.synthetic.tag:}") String tag, @Value("${jejari.synthetic.members:1000}") int members,
			@Value("${jejari.synthetic.seed:42}") long seed, @Value("${jejari.synthetic.cleanup:false}") boolean cleanup) {
		this.service = service;
		this.context = context;
		this.tag = tag;
		this.members = members;
		this.seed = seed;
		this.cleanup = cleanup;
		this.scoreOnly = scoreOnly;
		this.batch = batch;
		this.dirty = dirty;
	}

	@Override
	public void run(ApplicationArguments args) {
		int code = 0;
		try {
			if (scoreOnly) {
				score();
			} else if (cleanup) {
				log.info("가상 데이터 {} 삭제 완료: 회원 {}명", tag, service.cleanup(tag));
			} else {
				var r = service.generate(new SyntheticDataService.Spec(tag, members, seed));
				log.info("가상 데이터 생성 완료: 태그 {}, {} / 예약 {} · 취소 {} · 행동 로그 {}", r.tag(), r.counts(),
						r.reservations(), r.cancellations(), r.events());
				score();
				log.info("평가: loadtest/tools/eval_risk.sh {}  (앱을 켤 필요 없음)", r.tag());
			}
		} catch (RuntimeException e) {
			log.error("가상 데이터 작업 실패: {}", e.getMessage(), e);
			code = 1;
		}
		int exit = code;

		System.exit(SpringApplication.exit(context, () -> exit));
	}

	/** 남은 재계산 대상이 없을 때까지 위험 점수 배치를 돌린다 (한 번에 40초 한도라 여러 번) */
	private void score() {
		for (int i = 1; i <= 100 && dirty.pending() > 0; i++) {
			RiskScoreBatch.Result r = batch.run();
			log.info("위험 점수 계산 {}회차: 계산 {} · 저장 {} · 케이스 {} · 실패 {} · 남은 대상 {}", i, r.scored(), r.written(),
					r.opened(), r.failed(), r.pending());
			if (r.pending() < 0) {
				throw new IllegalStateException("다른 앱이 위험 점수 배치를 돌리는 중입니다. 앱을 끄고 다시 실행하세요");
			}
		}
		log.info("위험 점수 계산 끝 (남은 대상 {})", dirty.pending());
	}
}
