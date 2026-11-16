package com.jejari.member.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jejari.queue.port.MemberIdentityPort;

/**
 * 대기열(A)의 {@link MemberIdentityPort} 구현. 대기열 진입 1건마다 불린다.
 *
 * <p>회원 상태·CI 해시·결제 수단 해시·기기 해시를 Oracle에서 <b>한 번의 왕복</b>으로 읽는다 (UNION ALL, 모두 인덱스 조회).
 *
 * <ul>
 *   <li><b>캐시 없음</b>: 정지(BANNED)·제한(RESTRICTED) 조치가 다음 진입부터 바로 먹혀야 한다 (ADM-06 "조치 즉시 반영").
 *       조회 시간은 통합 테스트에서 측정해 기능 문서에 남긴다.</li>
 *   <li><b>없는 회원·탈퇴 회원</b>: 빈 값 → 대기열이 AUTH_REQUIRED. 탈퇴 직후 남은 액세스 토큰(최대 15분)으로 줄 서는 것을 막는다.</li>
 *   <li><b>해시가 비정상적으로 많음</b>: 대기열이 해시마다 Redis 키를 읽으므로 수천 개면 진입이 무거워진다. 그렇다고 "최근 N개만"
 *       쓰면 깨끗한 기기를 잔뜩 등록해 위험한 기기를 밀어내는 우회가 된다. 그래서 자르지 않고 상한을 넘으면 진입을 막고 오류를 남긴다.</li>
 *   <li><b>형식이 깨진 데이터</b>: 조용히 건너뛰면 위험 점수가 빠져 통과 쪽으로 기운다. 막고 오류를 남긴다 (fail-closed).</li>
 * </ul>
 * 예외는 대기열에서 500(INTERNAL_ERROR)이 되고, 응답에는 내부 정보가 나가지 않는다.
 *
 * <p>개발 모드(jejari.dev-mode=true)에서는 꺼지고 A의 DevMemberIdentityPort가 쓰인다.
 */
@Component
@ConditionalOnProperty(name = "jejari.dev-mode", havingValue = "false", matchIfMissing = true)
public class OracleMemberIdentityPort implements MemberIdentityPort {
	private static final Logger log = LoggerFactory.getLogger(OracleMemberIdentityPort.class);

	/** 결제 수단·기기 각각의 상한. 정상 사용자는 한 자릿수다 */
	static final int MAX_LINKED = 100;

	private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
	private static final Set<String> STATUSES = Set.of("ACTIVE", "RESTRICTED", "BANNED");

	/**
	 * kind: M(회원) / P(결제 수단) / D(기기). 결제 수단은 method_hash가 없는 행(해시 전 등록 중)을 뺀다.
	 * 정렬은 결과를 결정적으로 만들기 위해서다 (같은 회원이면 항상 같은 순서).
	 */
	private static final String SQL = """
			SELECT 'M' kind, status, ci_hash h FROM member WHERE member_id = ? AND withdrawn_at IS NULL
			UNION ALL
			SELECT 'P', NULL, method_hash FROM payment_method WHERE member_id = ? AND method_hash IS NOT NULL
			UNION ALL
			SELECT 'D', NULL, device_hash FROM member_device WHERE member_id = ?
			ORDER BY 1, 3""";

	private final JdbcTemplate jdbc;

	public OracleMemberIdentityPort(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<MemberIdentity> find(long memberId) {
		String[] member = null;
		List<String> payments = new ArrayList<>();
		List<String> devices = new ArrayList<>();
		for (Row r : jdbc.query(SQL, (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3)),
				memberId, memberId, memberId)) {
			switch (r.kind) {
				case "M" -> member = new String[] {r.status, r.hash};
				case "P" -> payments.add(r.hash);
				case "D" -> devices.add(r.hash);
				default -> throw new IllegalStateException("알 수 없는 행 종류");
			}
		}
		if (member == null) {
			return Optional.empty(); // 없는 회원 또는 탈퇴 회원. 결제·기기 행이 남아 있어도 쓰지 않는다
		}
		String status = member[0];
		String ciHash = member[1];
		if (!STATUSES.contains(status)) {
			throw corrupt(memberId, "status");
		}
		check(memberId, "ci_hash", List.of(ciHash == null ? "" : ciHash));
		check(memberId, "payment_method", payments);
		check(memberId, "member_device", devices);
		return Optional.of(new MemberIdentity(memberId, status, ciHash, List.copyOf(payments), List.copyOf(devices)));
	}

	private static void check(long memberId, String what, List<String> hashes) {
		if (hashes.size() > MAX_LINKED) {
			log.error("회원 {}의 {} 해시가 {}개로 상한 {}을 넘어 대기열 진입을 막음 (관리자 확인 필요)", memberId, what,
					hashes.size(), MAX_LINKED);
			throw new IllegalStateException("연결된 식별 해시가 너무 많습니다");
		}
		for (String h : hashes) {
			if (h == null || !HASH.matcher(h).matches()) {
				throw corrupt(memberId, what);
			}
		}
	}

	private static IllegalStateException corrupt(long memberId, String what) {
		// 값은 남기지 않는다 (해시라도 로그에 쌓이지 않게)
		log.error("회원 {}의 {} 값 형식이 잘못돼 대기열 진입을 막음 (데이터 확인 필요)", memberId, what);
		return new IllegalStateException("회원 식별 정보 형식 오류");
	}

	private record Row(String kind, String status, String hash) {
	}
}
