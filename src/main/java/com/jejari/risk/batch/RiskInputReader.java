package com.jejari.risk.batch;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jejari.behavior.BehaviorType;
import com.jejari.risk.PreviousScore;
import com.jejari.risk.SignalCollector;
import com.jejari.risk.SubjectType;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;

/**
 * 엔진 입력 읽기. 대상 하나의 이벤트를 {@link SignalCollector}에 흘려 넣는다.
 *
 * <table>
 * <tr><th>신호</th><th>CI</th><th>PAYMENT</th><th>DEVICE</th><th>원천</th></tr>
 * <tr><td>클릭 간격·같은 좌석 실패·다계정 동시 선점</td><td>O</td><td>-</td><td>O</td><td>MongoDB behavior_log</td></tr>
 * <tr><td>구매·사용자 취소</td><td>O</td><td>O</td><td>-</td><td>Oracle reservation·payment·cancellation</td></tr>
 * <tr><td>연결 계정 수·계정 나이</td><td>O</td><td>O</td><td>O</td><td>Oracle member (+ payment_method·member_device)</td></tr>
 * </table>
 *
 * <ul>
 *   <li>행동 로그에는 결제 수단 해시가 없어 PAYMENT는 행동 신호를 보지 않는다.</li>
 *   <li>기기는 가족이 함께 쓸 수 있어 구매·취소를 기기 단위로 묶지 않는다 (오탐 방지). 기기는 클릭·선점 패턴과 계정 수만 본다.</li>
 *   <li>CI의 구매는 reservation.ci_hash(인덱스 없음) 대신 같은 CI 회원의 member_id로 찾는다
 *       (UNIQUE(member_id, idempotency_key) 인덱스를 탄다. 쓰기 폭주 테이블에 인덱스를 새로 걸지 않기 위해).</li>
 *   <li>탈퇴한 계정도 연결 계정으로 센다. 탈퇴 후 재가입으로 계정 수를 줄이는 회피를 막는다.</li>
 * </ul>
 */
@Component
public class RiskInputReader {
	private static final Logger log = LoggerFactory.getLogger(RiskInputReader.class);

	/** 대상 하나당 읽는 행동 로그 상한. 넘으면 앞(오래된) 쪽부터 이만큼만 센다. 집계는 개수 기반이라 이만큼이면 모든 규칙의 높은 단계를 넘는다 */
	static final int MAX_BEHAVIOR_EVENTS = 200_000;

	private final JdbcTemplate jdbc;
	private final MongoTemplate mongo;
	private volatile boolean deviceIndexReady;

	public RiskInputReader(JdbcTemplate jdbc, MongoTemplate mongo) {
		this.jdbc = jdbc;
		this.mongo = mongo;
	}

	/** risk_score 현재 행. 없으면 null */
	public StoredScore stored(RiskSubject s) {
		record Row(BigDecimal finalScore, BigDecimal ruleScore, String reasons, Instant calculatedAt,
				PreviousScore.ReviewStatus status) {
		}
		List<Row> rows = jdbc.query("""
				SELECT final_score, rule_score, reasons, calculated_at, review_status
				  FROM risk_score WHERE subject_type = ? AND subject_hash = ?""",
				(rs, i) -> new Row(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3),
						rs.getTimestamp(4).toInstant(), PreviousScore.ReviewStatus.valueOf(rs.getString(5))),
				s.type().name(), s.hash());
		if (rows.isEmpty()) {
			return null;
		}
		Row r = rows.get(0);
		Instant clearedAt = r.status() == PreviousScore.ReviewStatus.CLEARED ? clearedAt(s, r.calculatedAt()) : null;
		return new StoredScore(new PreviousScore(r.finalScore(), r.calculatedAt(), r.status(), clearedAt),
				r.ruleScore(), r.reasons());
	}

	/**
	 * 관리자가 CLEAR한 시각 (admin_action). 기록이 없으면(데이터 이상) 마지막 계산 시각을 쓴다:
	 * 그 이전 증거를 다시 세지 않는 쪽이 관리자 판정을 지키는 쪽이다.
	 */
	private Instant clearedAt(RiskSubject s, Instant fallback) {
		Timestamp t = jdbc.queryForObject("""
				SELECT MAX(acted_at) FROM admin_action
				 WHERE subject_type = ? AND subject_hash = ? AND action_type = 'CLEAR'""",
				Timestamp.class, s.type().name(), s.hash());
		if (t == null) {
			log.warn("CLEARED인 위험 점수에 CLEAR 조치 기록이 없어 마지막 계산 시각부터 집계 ({})", s.type());
			return fallback;
		}
		return t.toInstant();
	}

	/** 이 대상에 연결된 회원을 찾는 하위 쿼리 (자리표시자 1개 = 해시) */
	public static String membersOf(SubjectType t) {
		return switch (t) {
			case CI -> "SELECT member_id FROM member WHERE ci_hash = ?";
			case PAYMENT -> "SELECT member_id FROM payment_method WHERE method_hash = ?";
			case DEVICE -> "SELECT member_id FROM member_device WHERE device_hash = ?";
		};
	}

	/** 연결 계정 수와 가장 오래된 계정의 가입 시각 */
	public void accounts(SignalCollector c, RiskSubject s, Instant now) {
		jdbc.query("SELECT COUNT(*), MIN(created_at) FROM member WHERE member_id IN (" + membersOf(s.type()) + ")",
				rs -> {
					c.linkedAccounts(rs.getLong(1));
					Timestamp oldest = rs.getTimestamp(2);
					if (oldest != null) {
						c.oldestAccountCreatedAt(oldest.toInstant(), now);
					}
				}, s.hash());
	}

	/** 이 대상의 결제가 확정된 예약 ID를 찾는 하위 쿼리. DEVICE는 null (구매를 기기로 묶지 않음) */
	static String reservationsOf(SubjectType t) {
		return switch (t) {
			case CI -> "SELECT reservation_id FROM reservation WHERE member_id IN (" + membersOf(t) + ")";
			// 결제 수단은 회원이 아니라 그 카드로 결제한 예약 (환불된 결제 포함: 구매는 있었다)
			case PAYMENT -> """
					SELECT reservation_id FROM payment
					 WHERE status IN ('APPROVED', 'REFUNDED')
					   AND method_id IN (SELECT method_id FROM payment_method WHERE method_hash = ?)""";
			case DEVICE -> null;
		};
	}

	/** from 이후 확정된 구매와 사용자 취소(USER) */
	public void transactions(SignalCollector c, RiskSubject s, Instant from) {
		String resv = reservationsOf(s.type());
		if (resv == null) {
			return;
		}
		Timestamp since = Timestamp.from(from);
		jdbc.query("SELECT seat_count FROM reservation WHERE confirmed_at >= ? AND reservation_id IN (" + resv + ")",
				rs -> {
					int seats = rs.getInt(1);
					if (seats > 0) {
						c.purchase(seats);
					}
				}, since, s.hash());

		// 관리자 취소(ADMIN)·늦은 승인 환불(PAY_FAILED)은 본인 행동이 아니라 넣지 않는다 (CNL-02)
		long[] missing = {0};
		jdbc.query("""
				SELECT minutes_after_purchase, minutes_before_departure FROM cancellation
				 WHERE cancel_type = 'USER' AND canceled_at >= ? AND reservation_id IN (""" + resv + ")",
				rs -> {
					long after = rs.getLong(1);
					boolean afterNull = rs.wasNull();
					long before = rs.getLong(2);
					boolean beforeNull = rs.wasNull();
					if (afterNull || beforeNull) {
						missing[0]++; // 값이 비면 의심 쪽(0분)으로 센다. 기록 버그가 "신호 없음"으로 숨지 않게
					}
					c.userCancel(Math.max(0, afterNull ? 0 : after), beforeNull ? 0 : before);
				}, since, s.hash());
		if (missing[0] > 0) {
			log.warn("사용자 취소 {}건에 구매 후·출발 전 분 값이 없어 0분으로 셈 ({})", missing[0], s.type());
		}
	}

	/** from 이후 행동 로그 (CI·DEVICE). 시각 오름차순 */
	public void behavior(SignalCollector c, RiskSubject s, Instant from) {
		String field = switch (s.type()) {
			case CI -> "ci_hash";
			case DEVICE -> "device_hash";
			case PAYMENT -> null;
		};
		if (field == null) {
			return;
		}
		MongoCollection<Document> coll = mongo.getCollection("behavior_log");
		if (s.type() == SubjectType.DEVICE) {
			ensureDeviceIndex(coll);
		}
		Bson filter = Filters.and(Filters.eq(field, s.hash()), Filters.gte("ts", Date.from(from)));
		int n = 0;
		try (MongoCursor<Document> cur = coll.find(filter)
				.projection(Projections.include("ts", "interval_ms", "event_type", "member_id", "response_status",
						"meta.seatIds"))
				.sort(Sorts.ascending("ts")).limit(MAX_BEHAVIOR_EVENTS).batchSize(5000).iterator()) {
			while (cur.hasNext()) {
				apply(c, cur.next());
				n++;
			}
		}
		if (n >= MAX_BEHAVIOR_EVENTS) {
			log.warn("위험 점수 대상 하나의 행동 로그가 {}건을 넘어 앞부분만 셈 ({})", MAX_BEHAVIOR_EVENTS, s.type());
		}
	}

	/** 행동 로그 문서 하나 → 신호. 적재 컨슈머가 형식을 검사한 문서지만 필드가 비어 있어도 멈추지 않게 읽는다 */
	static void apply(SignalCollector c, Document d) {
		Object interval = d.get("interval_ms");
		if (interval instanceof Number num) {
			c.interval(num.longValue());
		}
		if (!BehaviorType.SEAT_HOLD.name().equals(d.getString("event_type"))) {
			return;
		}
		Date ts = d.getDate("ts");
		Object member = d.get("member_id");
		if (ts != null && member instanceof Number m) {
			c.seatHold(ts.toInstant(), m.longValue());
		}
		Object status = d.get("response_status");
		if (status instanceof Number st && st.intValue() == 409) { // 이미 선점된 좌석
			Object meta = d.get("meta");
			if (meta instanceof Document md && md.get("seatIds") instanceof List<?> seats) {
				for (Object seat : seats) {
					if (seat instanceof Number id) {
						c.seatHoldFailure(id.longValue());
					}
				}
			}
		}
	}

	/**
	 * 기기 해시로 찾는 인덱스 ix_device_ts (ERD v19). mongo-init.js는 볼륨을 처음 만들 때만 돌아 기존 볼륨에는 없을 수 있어
	 * 여기서도 보장한다. 이미 있으면 createIndex는 아무 일도 안 한다.
	 * 실패해도 조회는 된다 (느릴 뿐) → 다음 배치에서 다시 시도.
	 */
	private void ensureDeviceIndex(MongoCollection<Document> coll) {
		if (deviceIndexReady) {
			return;
		}
		try {
			coll.createIndex(Indexes.compoundIndex(Indexes.ascending("device_hash"), Indexes.ascending("ts")),
					new IndexOptions().name("ix_device_ts"));
			deviceIndexReady = true;
		} catch (RuntimeException e) {
			log.warn("behavior_log ix_device_ts 인덱스를 만들지 못함: {}", e.toString());
		}
	}

	/** risk_score 현재 행 + 쓰기 판단에 필요한 값 */
	public record StoredScore(PreviousScore previous, BigDecimal ruleScore, String reasons) {
	}
}
