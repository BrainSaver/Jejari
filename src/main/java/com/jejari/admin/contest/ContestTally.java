package com.jejari.admin.contest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 봇 대회 결과를 종류(bot… · human…)별로 모은다. DB를 읽지 않는 계산만 (단위 테스트 대상).
 * 규칙은 loadtest/tools/eval_contest.sh와 같다 — 화면과 스크립트가 같은 숫자를 내야 한다.
 *
 * <ul>
 *   <li>탐지 = 그 계정에 연결된 대상(CI · 결제수단 · 기기)에 의심 케이스가 하나라도 있음</li>
 *   <li>사람(human…)이 탐지되면 오탐</li>
 *   <li>막힌 층: 매수제한(선점이 LIMIT_*로 거절) · 규칙(케이스) · 순번조정(최고 점수 × 점당 ms) · 관리자판정. 하나도 없으면 "뚫림"</li>
 * </ul>
 */
public final class ContestTally {

	private ContestTally() {
	}

	/** 대상: CI · PAYMENT · DEVICE 와 그 해시 */
	public record Subject(String type, String hash) {
	}

	/** 참가 계정 한 명의 재료 */
	public record Member(long memberId, String kind, String ciHash, int paidSeats, int heldSeats, int holdTries,
			int limitRejects, Set<Subject> subjects) {
	}

	/** 의심 케이스 하나 (대상 + 상태 + 걸린 규칙들) */
	public record Case(long caseId, Subject subject, String status, List<String> rules) {
	}

	public record RuleHit(String subjectType, String rule, int members) {
	}

	public record KindRow(String kind, boolean human, int members, int identities, int paidSeats, int heldSeats,
			int holdTries, int limitRejects, int detected, int judged, double maxScore, long penaltySec,
			List<String> layers, List<RuleHit> rules) {
	}

	public record Totals(int botMembers, int botDetected, int botPaidSeats, int humanMembers, int humanFalsePositive,
			int humanPaidSeats) {
	}

	public record Result(List<KindRow> kinds, Totals totals) {
	}

	private static final Set<String> JUDGED = Set.of("CONFIRMED", "DISMISSED");

	public static boolean isHuman(String kind) {
		return kind != null && kind.startsWith("human");
	}

	/**
	 * @param scores        대상별 위험 점수 (risk_score.final_score)
	 * @param penaltyMsPerPoint risk.penalty_ms_per_point (점수 1점당 대기열에서 밀리는 ms)
	 */
	public static Result tally(List<Member> members, List<Case> cases, Map<Subject, Double> scores, long penaltyMsPerPoint) {
		Map<Subject, List<Case>> bySubject = new HashMap<>();
		for (Case c : cases) {
			bySubject.computeIfAbsent(c.subject(), k -> new ArrayList<>()).add(c);
		}
		Map<String, List<Member>> byKind = new TreeMap<>(Comparator.comparing(ContestTally::isHuman)
				.thenComparing(Comparator.naturalOrder()));
		for (Member m : members) {
			byKind.computeIfAbsent(m.kind(), k -> new ArrayList<>()).add(m);
		}
		List<KindRow> rows = new ArrayList<>();
		int botMembers = 0, botDetected = 0, botPaid = 0, humanMembers = 0, humanFp = 0, humanPaid = 0;
		for (Map.Entry<String, List<Member>> e : byKind.entrySet()) {
			String kind = e.getKey();
			boolean human = isHuman(kind);
			Set<String> ids = new HashSet<>();
			int paid = 0, held = 0, tries = 0, lim = 0, det = 0, judged = 0;
			double maxScore = 0;
			// 규칙별로 그 규칙의 케이스가 연결된 계정 수 (같은 계정은 한 번만)
			Map<String, Set<Long>> ruleMembers = new LinkedHashMap<>();
			for (Member m : e.getValue()) {
				if (m.ciHash() != null) {
					ids.add(m.ciHash());
				}
				paid += m.paidSeats();
				held += m.heldSeats();
				tries += m.holdTries();
				lim += m.limitRejects();
				boolean hit = false;
				boolean done = false;
				for (Subject s : m.subjects()) {
					maxScore = Math.max(maxScore, scores.getOrDefault(s, 0.0));
					for (Case c : bySubject.getOrDefault(s, List.of())) {
						hit = true;
						done |= JUDGED.contains(c.status());
						for (String r : c.rules()) {
							ruleMembers.computeIfAbsent(s.type() + "|" + r, k -> new HashSet<>()).add(m.memberId());
						}
					}
				}
				det += hit ? 1 : 0;
				judged += done ? 1 : 0;
			}
			long penSec = Math.round(maxScore * penaltyMsPerPoint / 1000.0);
			List<String> layers = new ArrayList<>();
			if (!human) {
				if (lim > 0) {
					layers.add("매수제한");
				}
				if (det > 0) {
					layers.add("규칙(케이스)");
				}
				if (penSec > 0) {
					layers.add("순번조정 " + penSec + "초");
				}
				if (judged > 0) {
					layers.add("관리자판정");
				}
			}
			List<RuleHit> rules = ruleMembers.entrySet().stream()
					.map(r -> {
						String[] p = r.getKey().split("\\|", 2);
						return new RuleHit(p[0], p[1], r.getValue().size());
					})
					.sorted(Comparator.comparingInt(RuleHit::members).reversed().thenComparing(RuleHit::rule))
					.toList();
			int n = e.getValue().size();
			rows.add(new KindRow(kind, human, n, ids.size(), paid, held, tries, lim, det, judged, maxScore, penSec,
					layers, rules));
			if (human) {
				humanMembers += n;
				humanFp += det;
				humanPaid += paid;
			} else {
				botMembers += n;
				botDetected += det;
				botPaid += paid;
			}
		}
		return new Result(rows, new Totals(botMembers, botDetected, botPaid, humanMembers, humanFp, humanPaid));
	}
}
