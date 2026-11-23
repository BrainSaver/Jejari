package com.jejari.payment;

import java.time.Clock;
import java.util.OptionalDouble;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.jejari.queue.domain.RiskPenaltyService;
import com.jejari.queue.port.MemberIdentityPort;
import com.jejari.queue.port.MemberIdentityPort.MemberIdentity;
import com.jejari.risk.batch.RiskPolicySource;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 결제 단계 차단 (11-23 봇 대회 보강 7번): 위험 점수가 케이스 기준({@code risk.rule.case_threshold}, 기본 80) 이상이면
 * 결제를 시작하지 못하게 한다. 대기열은 점수만큼 늦게 들여보낼 뿐이라, 기다렸다 들어온 봇이 결제까지 가는 것을 마지막에 막는다.
 *
 * <ul>
 * <li><b>어디서</b>: {@link PaymentService#start} 처음 요청(멱등 키를 처음 잡은 때)에서, PG를 부르기 전. 돈이 나가기 전이라 환불이 없다.
 *     같은 키 재요청(이미 결제 중)은 막지 않고 상태만 돌려준다.</li>
 * <li><b>점수</b>: 대기열과 같은 규칙 — CI·결제 수단·기기 점수 중 최댓값, RESTRICTED 계정은 100 ({@link RiskPenaltyService#knownScore}).</li>
 * <li><b>모르면 통과</b>: Redis·Oracle에서 점수를 못 읽으면 막지 않고 지표({@code reason=unknown})만 남긴다.
 *     장애 한 번에 모든 결제가 멈추지 않게 (대기열에서 이미 한 번 걸렀다).</li>
 * <li><b>기준</b>: 케이스 기준을 그대로 쓴다. 관리자가 A-07에서 기준을 바꾸면 결제 차단도 같이 바뀐다 (새 설정 키를 만들지 않음).
 *     30초 캐시 (결제마다 app_policy를 읽지 않게).</li>
 * <li><b>끄기</b>: {@code jejari.payment.risk-block=false}.</li>
 * </ul>
 * 지표: {@code jejari_payment_risk_check_total{result=passed|blocked|unknown|off}}
 */
@Component
public class PaymentRiskGuard {

	private static final Logger log = LoggerFactory.getLogger(PaymentRiskGuard.class);
	static final long THRESHOLD_CACHE_MS = 30_000;

	/** 결과: 막을지와 그때 본 점수·기준 (점수를 모르면 score = NaN) */
	public record Verdict(boolean blocked, double score, int threshold) {
	}

	private final ObjectProvider<MemberIdentityPort> members;
	private final ObjectProvider<RiskPenaltyService> risk;
	private final RiskPolicySource rules;
	private final MeterRegistry meters;
	private final Clock clock;
	private final boolean enabled;

	private volatile int cachedThreshold = -1;
	private volatile long cachedAt;

	public PaymentRiskGuard(ObjectProvider<MemberIdentityPort> members, ObjectProvider<RiskPenaltyService> risk,
			RiskPolicySource rules, MeterRegistry meters, Clock clock,
			@Value("${jejari.payment.risk-block:true}") boolean enabled) {
		this.members = members;
		this.risk = risk;
		this.rules = rules;
		this.meters = meters;
		this.clock = clock;
		this.enabled = enabled;
	}

	public Verdict check(long memberId) {
		if (!enabled) {
			count("off");
			return new Verdict(false, Double.NaN, -1);
		}
		OptionalDouble score = score(memberId);
		int threshold = threshold();
		if (score.isEmpty()) {
			count("unknown");
			return new Verdict(false, Double.NaN, threshold);
		}
		boolean blocked = score.getAsDouble() >= threshold;
		count(blocked ? "blocked" : "passed");
		return new Verdict(blocked, score.getAsDouble(), threshold);
	}

	private OptionalDouble score(long memberId) {
		MemberIdentityPort port = members.getIfAvailable();
		RiskPenaltyService service = risk.getIfAvailable();
		if (port == null || service == null) {
			return OptionalDouble.empty();
		}
		try {
			MemberIdentity me = port.find(memberId).orElse(null);
			return me == null ? OptionalDouble.empty() : service.knownScore(me);
		} catch (RuntimeException e) {
			log.warn("결제 단계 위험 점수 확인 실패 member={} → 통과: {}", memberId, e.toString());
			return OptionalDouble.empty();
		}
	}

	/** 케이스 기준. RiskPolicySource가 DB를 못 읽으면 마지막 값(처음이면 기본 80)을 준다 */
	int threshold() {
		long now = clock.millis();
		if (cachedThreshold < 0 || now - cachedAt >= THRESHOLD_CACHE_MS) {
			cachedThreshold = rules.current().caseThreshold();
			cachedAt = now;
		}
		return cachedThreshold;
	}

	private void count(String result) {
		meters.counter("jejari.payment.risk.check", "result", result).increment();
	}
}
