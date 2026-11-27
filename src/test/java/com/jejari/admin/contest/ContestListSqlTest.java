package com.jejari.admin.contest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A-11 대회 목록 SQL 틀 (2026-11-27: formatted() 틀 안의 'bot%'가 JDK 24에서 예외 → 목록 500) */
class ContestListSqlTest {

	@Test
	void 목록_SQL은_와일드카드가_퍼센트_하나로_남고_자리표시가_다_채워진다() {
		String sql = ContestResultService.LIST_SQL;
		assertThat(sql).contains("LIKE 'bot%'", "LIKE 'human%'", "LIKE 'lt%'");
		assertThat(sql).doesNotContain("%%", "%s");
		assertThat(sql).contains("REGEXP_LIKE(REGEXP_SUBSTR(email");
	}
}
