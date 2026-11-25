package com.jejari.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * T23 (concurrency.md §0.3): "네이티브 UPDATE 뒤 JPA로 같은 예약을 고치면 낙관적 락이 충돌을 감지한다".
 *
 * <p>지금 코드에는 reservation·app_policy의 JPA 엔티티가 없다 (모두 JdbcTemplate 네이티브 SQL) — 그래서 JPA 쪽 충돌이
 * 생길 자리 자체가 없다. 남는 위험은 나중에 엔티티(@Version)를 붙였을 때 <b>버전을 올리지 않은 네이티브 UPDATE</b>가 있으면
 * 충돌을 못 잡는 것이다. 이 테스트는 그 전제를 지킨다: version 컬럼이 있는 테이블의 모든 네이티브 UPDATE가
 * {@code version = version + 1}과 {@code updated_at = SYSTIMESTAMP}를 넣는다. 인프라 없이 돈다.
 */
class NativeUpdateRuleTest {

	/** version 컬럼이 있는 테이블 (§0.3) */
	private static final List<String> VERSIONED = List.of("reservation", "app_policy");

	@Test
	void T23_버전_컬럼이_있는_테이블의_네이티브_UPDATE는_모두_version과_updated_at을_올린다() throws IOException {
		List<String> found = new ArrayList<>();
		List<String> broken = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
			for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				String src = Files.readString(f, StandardCharsets.UTF_8);
				for (String table : VERSIONED) {
					// "UPDATE reservation" 뒤 WHERE까지 (reservation_seat 같은 다른 테이블은 빼고, 문자열 이어 붙이기도 포함)
					Matcher m = Pattern.compile("UPDATE\\s+" + table + "(?![_a-z])(.{0,800}?)\\bWHERE\\b", Pattern.DOTALL)
							.matcher(src);
					while (m.find()) {
						String where = f + ":" + (src.substring(0, m.start()).split("\n", -1).length);
						if (src.substring(Math.max(0, m.start() - 40), m.start()).contains("{@code")) {
							continue; // 주석 안의 설명
						}
						found.add(where);
						String set = m.group(1);
						if (!set.matches("(?s).*version\\s*=\\s*version\\s*\\+\\s*1.*") || !set.contains("updated_at")) {
							broken.add(where + "  " + set.replaceAll("\\s+", " ").trim());
						}
					}
				}
			}
		}
		assertThat(found).as("검사할 UPDATE를 하나도 못 찾으면 정규식이 깨진 것").hasSizeGreaterThanOrEqualTo(5);
		assertThat(broken).as("version = version + 1 또는 updated_at이 빠진 네이티브 UPDATE (§0.3)").isEmpty();
	}
}
