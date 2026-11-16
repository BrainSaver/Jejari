package com.jejari.seat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jejari.common.error.BusinessException;

/**
 * 매수 카운터 purchase_counter (concurrency.md §10). 매수 제한의 <b>최종 판정</b>입니다. Redis(limit_incr.lua)는 1차일 뿐입니다.
 *
 * <p>한 트랜잭션에서 바꿀 카운터 행을 {@link Change}로 모은 뒤 {@link #apply}로 한 번에 갱신합니다.
 * (scope, scope_ref_id, ci_hash) 오름차순으로 갱신해서, 재선점처럼 새 예약의 증가와 이전 예약(다른 CI)의
 * 감소가 섞여도 두 트랜잭션이 서로 다른 순서로 행을 기다리는 데드락이 생기지 않습니다 (§10.3, §0.4-4).
 */
@Component
class PurchaseCounters {

	/** delta > 0이면 증가(한도 max 검사), delta < 0이면 감소 */
	record Change(LimitScope scope, long refId, String ciHash, int delta, int max) {
		static Change incr(LimitScope s, long ref, String ci, int n, int max) {
			return new Change(s, ref, ci, n, max);
		}

		static Change decr(LimitScope s, long ref, String ci, int n) {
			return new Change(s, ref, ci, -n, 0);
		}
	}

	private static final Comparator<Change> ORDER = Comparator
			.comparing((Change c) -> c.scope().name())
			.thenComparingLong(Change::refId)
			.thenComparing(Change::ciHash);

	private static final String INCR = """
			UPDATE purchase_counter SET seat_count = seat_count + ?, updated_at = SYSTIMESTAMP
			 WHERE scope = ? AND scope_ref_id = ? AND ci_hash = ? AND seat_count + ? <= ?""";
	private static final String EXISTS = "SELECT COUNT(*) FROM purchase_counter WHERE scope = ? AND scope_ref_id = ? AND ci_hash = ?";
	private static final String INSERT = "INSERT INTO purchase_counter (scope, scope_ref_id, ci_hash, seat_count) VALUES (?, ?, ?, ?)";
	private static final String DECR = """
			UPDATE purchase_counter SET seat_count = seat_count - ?, updated_at = SYSTIMESTAMP
			 WHERE scope = ? AND scope_ref_id = ? AND ci_hash = ?""";
	private static final String READ = "SELECT seat_count FROM purchase_counter WHERE scope = ? AND scope_ref_id = ? AND ci_hash = ?";

	private final JdbcTemplate jdbc;

	PurchaseCounters(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	void apply(List<Change> changes) {
		List<Change> sorted = new ArrayList<>(changes);
		sorted.sort(ORDER);
		for (Change c : sorted) {
			if (c.delta() > 0) {
				increment(c);
			} else if (c.delta() < 0) {
				jdbc.update(DECR, -c.delta(), c.scope().name(), c.refId(), c.ciHash());
			}
		}
	}

	/** 지금 값 (행이 없으면 0). Redis 키 복원(§10.4)과 자가 복구(§10.7)에 씀 */
	int current(LimitScope s, long refId, String ciHash) {
		List<Integer> v = jdbc.queryForList(READ, Integer.class, s.name(), refId, ciHash);
		return v.isEmpty() ? 0 : v.get(0);
	}

	/** §10.1: 조건부 UPDATE → 행이 없으면 INSERT → 동시에 누가 만들었으면(ORA-00001) UPDATE 한 번 더 */
	private void increment(Change c) {
		for (int attempt = 0; attempt < 2; attempt++) {
			if (jdbc.update(INCR, c.delta(), c.scope().name(), c.refId(), c.ciHash(), c.delta(), c.max()) == 1) {
				return;
			}
			Integer rows = jdbc.queryForObject(EXISTS, Integer.class, c.scope().name(), c.refId(), c.ciHash());
			if (rows != null && rows > 0) {
				throw new BusinessException(c.scope().error); // 행이 있는데 UPDATE가 0행 = 한도 초과
			}
			if (c.delta() > c.max()) {
				throw new BusinessException(c.scope().error);
			}
			try {
				jdbc.update(INSERT, c.scope().name(), c.refId(), c.ciHash(), c.delta());
				return;
			} catch (DuplicateKeyException raced) {
				// 같은 CI의 첫 예약 두 건이 동시에 행을 만들려 함 (T11). 이제 행이 있으니 UPDATE부터 다시
			}
		}
		throw new BusinessException(c.scope().error);
	}
}
