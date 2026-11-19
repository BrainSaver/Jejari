package com.jejari.admin.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.jejari.behavior.sink.BehaviorPolicy;
import com.jejari.common.config.AppPolicy;
import com.jejari.common.error.BusinessException;
import com.jejari.common.policy.PolicyBounds;
import com.jejari.common.policy.PolicyBounds.DecimalRange;
import com.jejari.common.policy.PolicyBounds.Range;
import com.jejari.common.policy.PolicyBounds.Rule;
import com.jejari.member.AuthPolicy;
import com.jejari.queue.domain.PolicyProvider;

/** 정책 저장 전 검사: 소수 범위·허용값·묶음 조건, 그리고 ERD 시드값이 모든 모듈 기준을 이미 만족하는지 */
class PolicyBoundsTest {

	static final List<PolicyBounds> MODULES = List.of(new AuthPolicy(null), new BehaviorPolicy(null),
			new PolicyProvider(null, 0), new AppPolicy(null, null));

	/** docs/erd/jejari-erd.ddl.sql의 app_policy 예시 행 (키 → 값) */
	static Map<String, String> seed() throws Exception {
		Path ddl = Path.of("docs/erd/jejari-erd.ddl.sql");
		Matcher m = Pattern.compile("INSERT INTO app_policy \\([^)]*\\) VALUES \\('([^']+)', '([^']*)'")
				.matcher(Files.readString(ddl, StandardCharsets.UTF_8));
		Map<String, String> out = new HashMap<>();
		while (m.find()) {
			out.put(m.group(1), m.group(2));
		}
		return out;
	}

	@Test
	void ERD_시드값은_모든_모듈의_범위와_허용값_안에_있다() throws Exception {
		Map<String, String> s = seed();
		assertThat(s).hasSizeGreaterThan(40);
		for (PolicyBounds m : MODULES) {
			m.bounds().forEach((k, r) -> {
				if (s.containsKey(k)) {
					long v = Long.parseLong(s.get(k));
					assertThat(v).as(k).isBetween(r.min(), r.max());
				}
			});
			m.decimalBounds().forEach((k, r) -> {
				if (s.containsKey(k)) {
					assertThat(new BigDecimal(s.get(k))).as(k).isBetween(r.min(), r.max());
				}
			});
			m.allowedValues().forEach((k, ok) -> {
				if (s.containsKey(k)) {
					assertThat(ok).as(k).contains(s.get(k));
				}
			});
		}
	}

	@Test
	void ERD_시드값은_모든_묶음_조건을_만족한다() throws Exception {
		List<Rule> rules = MODULES.stream().flatMap(m -> m.rules().stream()).toList();
		assertThat(rules).isNotEmpty();
		PolicyAdminService.checkRules(rules, seed()); // 어긋나면 예외
	}

	@Test
	void 소수_범위() {
		DecimalRange r = DecimalRange.of("0", "1");
		PolicyAdminService.checkDecimal("replay.full_record_ratio", "0.25", r);
		PolicyAdminService.checkDecimal("replay.full_record_ratio", "1", r);
		assertThatThrownBy(() -> PolicyAdminService.checkDecimal("replay.full_record_ratio", "1.5", r))
				.isInstanceOf(BusinessException.class).hasMessageContaining("0~1");
	}

	@Test
	void 허용값() {
		PolicyAdminService.checkAllowed("risk.member_penalty_rule", "MAX", Set.of("MAX"));
		assertThatThrownBy(() -> PolicyAdminService.checkAllowed("risk.member_penalty_rule", "SUM", Set.of("MAX")))
				.isInstanceOf(BusinessException.class).hasMessageContaining("MAX");
	}

	@Test
	void 대기_기록은_최대_지연보다_오래_남아야_한다() {
		List<Rule> rules = new PolicyProvider(null, 0).rules();
		// 점당 10000ms → 최대 지연 1000초. 대기 기록 1200초면 통과, 900초면 거절
		PolicyAdminService.checkRules(rules, Map.of("queue.entry_ttl_sec", "1200", "risk.penalty_ms_per_point", "10000"));
		assertThatThrownBy(() -> PolicyAdminService.checkRules(rules,
				Map.of("queue.entry_ttl_sec", "900", "risk.penalty_ms_per_point", "10000")))
				.isInstanceOf(BusinessException.class).hasMessageContaining("÷ 10");
	}

	@Test
	void 조건이_보는_키가_아직_없으면_건너뛴다() {
		Rule r = new Rule(Set.of("a", "b"), v -> v.num("a").compareTo(v.num("b")) > 0, "a > b");
		PolicyAdminService.checkRules(List.of(r), Map.of("a", "1")); // b 없음 → 통과
		assertThatThrownBy(() -> PolicyAdminService.checkRules(List.of(r), Map.of("a", "1", "b", "2")))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 같은_키를_두_모듈이_알려_주면_겹치는_구간만_받는다() {
		PolicyBounds a = () -> Map.of("idem.ttl_sec", new Range(60, 86400));
		PolicyBounds b = () -> Map.of("idem.ttl_sec", new Range(600, 100000));
		var svc = new PolicyAdminService(null, null, null, List.of(a, b));
		// 600~86400
		assertThat(svc).extracting("bounds").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
				.containsEntry("idem.ttl_sec", new Range(600, 86400));
	}
}
