package com.jejari.risk.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import com.jejari.risk.PreviousScore;
import com.jejari.risk.RiskEngine;
import com.jejari.risk.RiskPolicy;
import com.jejari.risk.RiskResult;
import com.jejari.risk.RiskRule;
import com.jejari.risk.RiskSignals;
import com.jejari.risk.SignalCollector;
import com.jejari.risk.SubjectType;
import com.jejari.risk.batch.RiskInputReader.StoredScore;

/** 배치의 순수 로직: 대상 형식, 저장 판단(감쇠 멈춤 방지), 행동 로그 문서 → 신호 */
class RiskBatchUnitTest {

	static final String H = "a".repeat(64);
	static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
	static final Duration DAY = Duration.ofDays(1);
	final RiskEngine engine = new RiskEngine(RiskPolicy.defaults());

	// ---- 대상 형식

	@Test
	void 대상은_유형과_64자_해시로만_만든다() {
		RiskSubject s = new RiskSubject(SubjectType.CI, H);
		assertThat(RiskSubject.parse(s.member())).isEqualTo(s);
		assertThat(RiskSubject.parse("CI:01012345678")).isNull(); // 원문 전화번호
		assertThat(RiskSubject.parse("ADMIN:" + H)).isNull();
		assertThat(RiskSubject.parse("CI:" + H.toUpperCase())).isNull();
		assertThat(RiskSubject.parse("CI:" + H + "*")).isNull();
		assertThat(RiskSubject.parse(null)).isNull();
		assertThat(RiskSubject.ofNullable(SubjectType.DEVICE, null)).isNull();
		assertThatThrownBy(() -> new RiskSubject(SubjectType.CI, "x")).isInstanceOf(IllegalArgumentException.class);
	}

	// ---- 저장 판단

	RiskResult score(long fastClicks, PreviousScore prev, Instant at) {
		SignalCollector c = new SignalCollector(engine.policy(), SubjectType.CI, H, at.minus(DAY));
		for (int i = 0; i < fastClicks; i++) {
			c.interval(50);
		}
		return engine.score(c.build(), prev, at);
	}

	StoredScore stored(RiskResult r) {
		return new StoredScore(new PreviousScore(r.finalScore(), r.calculatedAt(), PreviousScore.ReviewStatus.NONE, null),
				r.ruleScore(), r.reasonsJson());
	}

	@Test
	void 처음_보는_대상이_0점이면_저장하지_않는다() {
		assertThat(RiskScoreBatch.shouldWrite(null, score(0, null, NOW), DAY)).isFalse();
		assertThat(RiskScoreBatch.shouldWrite(null, score(25, null, NOW), DAY)).isTrue();
	}

	@Test
	void 값과_근거가_그대로면_저장하지_않는다() {
		RiskResult first = score(25, null, NOW);
		RiskResult again = score(25, stored(first).previous(), NOW.plusSeconds(60));
		assertThat(RiskScoreBatch.shouldWrite(stored(first), again, DAY)).isFalse();
	}

	@Test
	void 새_증거로_점수가_오르면_바로_저장한다() {
		RiskResult first = score(25, null, NOW);
		RiskResult more = score(70, stored(first).previous(), NOW.plusSeconds(60));
		assertThat(more.finalScore()).isGreaterThan(first.finalScore());
		assertThat(RiskScoreBatch.shouldWrite(stored(first), more, DAY)).isTrue();
	}

	@Test
	void 매분_저장했다면_감쇠가_멈춘다_그래서_1점_내려갈_때까지_기다린다() {
		// 80점이 기록된 뒤 신호가 없어진 대상을 1분마다 다시 계산
		PreviousScore p80 = new PreviousScore(new BigDecimal("80.00"), NOW, PreviousScore.ReviewStatus.NONE, null);
		StoredScore s80 = new StoredScore(p80, new BigDecimal("80.00"), "[]");

		// 반례: 매분 "저장"(기준 시각을 옮김)하면 반올림 때문에 하루가 지나도 80.00 그대로
		PreviousScore naive = p80;
		for (int m = 1; m <= 24 * 60; m++) {
			RiskResult r = score(0, naive, NOW.plusSeconds(60L * m));
			naive = new PreviousScore(r.finalScore(), r.calculatedAt(), PreviousScore.ReviewStatus.NONE, null);
		}
		assertThat(naive.finalScore()).isEqualByComparingTo("80.00");

		// shouldWrite를 따르면: 1분 뒤에는 저장 안 함, 기준 시각이 남아 하루 뒤엔 실제로 내려감
		RiskResult oneMin = score(0, p80, NOW.plusSeconds(60));
		assertThat(RiskScoreBatch.shouldWrite(s80, oneMin, DAY)).isFalse();
		RiskResult oneDay = score(0, p80, NOW.plus(DAY));
		assertThat(oneDay.finalScore()).isLessThan(new BigDecimal("79"));
		assertThat(RiskScoreBatch.shouldWrite(s80, oneDay, DAY)).isTrue();
	}

	@Test
	void 감쇠가_1점_미만이어도_하루가_지나면_저장한다() {
		PreviousScore p = new PreviousScore(new BigDecimal("10.00"), NOW, PreviousScore.ReviewStatus.NONE, null);
		StoredScore s = new StoredScore(p, BigDecimal.ZERO, "[]");
		RiskResult halfDay = score(0, p, NOW.plus(Duration.ofHours(12)));
		RiskResult day = score(0, p, NOW.plus(DAY));
		assertThat(p.finalScore().subtract(day.finalScore())).isLessThan(BigDecimal.ONE);
		assertThat(RiskScoreBatch.shouldWrite(s, halfDay, DAY)).isFalse();
		assertThat(RiskScoreBatch.shouldWrite(s, day, DAY)).isTrue();
	}

	@Test
	void 감쇠_중에도_더_높은_새_증거가_오면_저장한다() {
		PreviousScore p = new PreviousScore(new BigDecimal("30.00"), NOW, PreviousScore.ReviewStatus.NONE, null);
		StoredScore s = new StoredScore(p, new BigDecimal("30.00"), "[]");
		RiskResult r = score(70, p, NOW.plusSeconds(60)); // FAST_CLICK HIGH 35점
		assertThat(RiskScoreBatch.shouldWrite(s, r, DAY)).isTrue();
	}

	// ---- 행동 로그 문서 → 신호

	Document doc(String type, long memberId, Instant ts, Integer interval, int status, List<Long> seats) {
		Document d = new Document("event_type", type).append("member_id", memberId).append("ts", Date.from(ts))
				.append("interval_ms", interval).append("response_status", status);
		if (seats != null) {
			d.append("meta", new Document("seatIds", seats));
		}
		return d;
	}

	RiskSignals collect(List<Document> docs) {
		SignalCollector c = new SignalCollector(engine.policy(), SubjectType.CI, H, NOW.minus(DAY));
		docs.forEach(d -> RiskInputReader.apply(c, d));
		return c.build();
	}

	@Test
	void 같은_좌석_409는_좌석별로_세고_성공은_세지_않는다() {
		RiskSignals s = collect(List.of(
				doc("SEAT_HOLD", 1, NOW, 50, 409, List.of(7L, 8L)),
				doc("SEAT_HOLD", 1, NOW.plusMillis(100), 50, 409, List.of(7L)),
				doc("SEAT_HOLD", 1, NOW.plusMillis(200), 50, 201, List.of(7L))));
		assertThat(s.maxSameSeatFailures()).isEqualTo(2);
		assertThat(s.fastIntervalCount()).isEqualTo(3);
	}

	@Test
	void 여러_계정이_짧은_시간에_선점하면_동시_계정으로_센다() {
		RiskSignals s = collect(List.of(
				doc("SEAT_HOLD", 1, NOW, null, 201, null),
				doc("SEAT_HOLD", 2, NOW.plusSeconds(10), null, 201, null),
				doc("SEAT_HOLD", 3, NOW.plusSeconds(20), null, 201, null),
				doc("VIEW_SEAT", 4, NOW.plusSeconds(21), null, 200, null))); // 조회는 선점이 아님
		assertThat(s.maxSyncAccounts()).isEqualTo(3);
		RiskResult r = engine.score(s, null, NOW);
		assertThat(r.reasons()).anyMatch(x -> x.rule() == RiskRule.MULTI_ACCOUNT_SYNC);
	}

	@Test
	void 필드가_비거나_이상해도_멈추지_않는다() {
		Document weird = new Document("event_type", "SEAT_HOLD").append("response_status", 409)
				.append("meta", new Document("seatIds", List.of("x", 3L)));
		RiskSignals s = collect(List.of(weird, new Document()));
		assertThat(s.maxSameSeatFailures()).isEqualTo(1);
		assertThat(s.intervalCount()).isZero();
	}
}
