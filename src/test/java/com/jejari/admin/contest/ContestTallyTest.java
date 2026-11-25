package com.jejari.admin.contest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.jejari.admin.contest.ContestTally.Case;
import com.jejari.admin.contest.ContestTally.KindRow;
import com.jejari.admin.contest.ContestTally.Member;
import com.jejari.admin.contest.ContestTally.Result;
import com.jejari.admin.contest.ContestTally.RuleHit;
import com.jejari.admin.contest.ContestTally.Subject;

/** eval_contest.sh와 같은 규칙으로 종류별 결과를 내는지 (탐지 = 연결 대상에 케이스, 사람이 탐지되면 오탐, 막힌 층) */
class ContestTallyTest {

	private static final Subject CI_B = new Subject("CI", "b1");
	private static final Subject DEV_B = new Subject("DEVICE", "d1");
	private static final Subject CI_C = new Subject("CI", "c1");
	private static final Subject CI_H1 = new Subject("CI", "h1");
	private static final Subject CI_H2 = new Subject("CI", "h2");
	private static final Subject CARD_SHARED = new Subject("PAYMENT", "card");

	@Test
	void 종류별로_좌석_탐지_층을_모은다() {
		List<Member> members = List.of(
				// botb: 명의 하나에 계정 둘, 기기 하나를 같이 씀. 매수 제한 거절 3번
				new Member(1, "botb", "b1", 4, 0, 6, 3, Set.of(CI_B, DEV_B)),
				new Member(2, "botb", "b1", 4, 2, 5, 0, Set.of(CI_B, DEV_B)),
				// botc: 케이스 없음, 점수만
				new Member(3, "botc", "c1", 4, 0, 1, 0, Set.of(CI_C)),
				// 사람 둘: 하나는 봇과 같은 카드를 써서 케이스가 연결됨 (오탐)
				new Member(4, "human", "h1", 1, 0, 1, 0, Set.of(CI_H1, CARD_SHARED)),
				new Member(5, "humanfamily", "h2", 2, 0, 2, 0, Set.of(CI_H2)));
		List<Case> cases = List.of(
				new Case(10, CI_B, "CONFIRMED", List.of("HEADLESS_HOLD", "LINEAR_MOUSE")),
				new Case(11, DEV_B, "OPEN", List.of("HEADLESS_HOLD")),
				new Case(12, CARD_SHARED, "OPEN", List.of("SHARED_PAYMENT")));
		Map<Subject, Double> scores = Map.of(CI_B, 75.0, DEV_B, 40.0, CI_C, 15.0, CI_H1, 5.0);

		Result r = ContestTally.tally(members, cases, scores, 1000);

		// 봇이 먼저, 사람은 뒤 (종류 이름 순)
		assertThat(r.kinds()).extracting(KindRow::kind).containsExactly("botb", "botc", "human", "humanfamily");

		KindRow b = r.kinds().get(0);
		assertThat(b.members()).isEqualTo(2);
		assertThat(b.identities()).isEqualTo(1);
		assertThat(b.paidSeats()).isEqualTo(8);
		assertThat(b.heldSeats()).isEqualTo(2);
		assertThat(b.holdTries()).isEqualTo(11);
		assertThat(b.limitRejects()).isEqualTo(3);
		assertThat(b.detected()).isEqualTo(2);
		assertThat(b.judged()).isEqualTo(2);
		assertThat(b.maxScore()).isEqualTo(75.0);
		assertThat(b.penaltySec()).isEqualTo(75);
		assertThat(b.layers()).containsExactly("매수제한", "규칙(케이스)", "순번조정 75초", "관리자판정");
		// 같은 계정은 규칙마다 한 번만: HEADLESS_HOLD는 CI·기기 두 대상에서 걸렸지만 대상 종류별로 센다
		assertThat(b.rules()).contains(new RuleHit("CI", "HEADLESS_HOLD", 2), new RuleHit("DEVICE", "HEADLESS_HOLD", 2),
				new RuleHit("CI", "LINEAR_MOUSE", 2));

		KindRow c = r.kinds().get(1);
		assertThat(c.detected()).isZero();
		assertThat(c.layers()).containsExactly("순번조정 15초"); // 케이스 없이 순번만 밀림 (사실상 뚫림에 가까움)

		KindRow h = r.kinds().get(2);
		assertThat(h.human()).isTrue();
		assertThat(h.detected()).isEqualTo(1);
		assertThat(h.layers()).isEmpty(); // 사람에는 "막힌 층"을 붙이지 않는다

		assertThat(r.totals().botMembers()).isEqualTo(3);
		assertThat(r.totals().botDetected()).isEqualTo(2);
		assertThat(r.totals().botPaidSeats()).isEqualTo(12);
		assertThat(r.totals().humanMembers()).isEqualTo(2);
		assertThat(r.totals().humanFalsePositive()).isEqualTo(1);
		assertThat(r.totals().humanPaidSeats()).isEqualTo(3);
	}

	@Test
	void 아무_층에도_안_걸린_봇은_층이_비어_뚫림() {
		Result r = ContestTally.tally(List.of(new Member(1, "botx", "x", 4, 0, 1, 0, Set.of(new Subject("CI", "x")))),
				List.of(), Map.of(), 1000);
		assertThat(r.kinds().get(0).layers()).isEmpty();
		assertThat(r.kinds().get(0).penaltySec()).isZero();
	}

	@Test
	void 점수가_없는_대상은_0점으로() {
		Result r = ContestTally.tally(List.of(new Member(1, "bota", null, 0, 0, 0, 0, Set.of())), List.of(), Map.of(), 1000);
		assertThat(r.kinds().get(0).maxScore()).isZero();
		assertThat(r.kinds().get(0).identities()).isZero();
	}
}
