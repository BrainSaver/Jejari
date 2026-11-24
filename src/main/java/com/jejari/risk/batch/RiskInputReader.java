package com.jejari.risk.batch;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
import com.jejari.risk.MouseMotion;
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
 * <tr><td>기계적 마우스 이동 (LINEAR_MOUSE)</td><td>O</td><td>-</td><td>O</td><td>MongoDB replay_session·replay_event (화면 녹화)</td></tr>
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
						"meta.seatIds", "meta.auto", "session_id", "elapsed_ms"))
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

	/** 대상 하나당 보는 녹화 세션 수 상한 (최근 것부터). 세션 하나에서도 문턱을 넘으므로 이만큼이면 충분하다 */
	static final int MAX_MOUSE_SESSIONS = 50;
	/** 녹화 세션 하나에서 읽는 궤적·클릭 이벤트 상한 (replay.max_events_per_session보다 작게 잡지 않아도 되도록 넉넉히) */
	static final int MAX_MOUSE_EVENTS = 5_000;

	/**
	 * from 이후 화면 녹화의 마우스 궤적 (CI·DEVICE) → {@link MouseMotion}. 세션마다 따로 분석해 더한다
	 * (다른 세션의 점끼리 이어 붙이면 없는 순간이동이 생긴다). 녹화가 꺼져 있거나 없으면 0.
	 * 세션은 인덱스 ix_ci_started·ix_device_started(대상 해시 + started_at)로 찾는다.
	 */
	public void mouse(SignalCollector c, RiskSubject s, Instant from) {
		String field = switch (s.type()) {
			case CI -> "ci_hash";
			case DEVICE -> "device_hash";
			case PAYMENT -> null;
		};
		if (field == null) {
			return;
		}
		Date since = Date.from(from);
		List<String> sessions = new ArrayList<>();
		for (Document d : mongo.getCollection("replay_session")
				.find(Filters.and(Filters.eq(field, s.hash()), Filters.gte("ended_at", since)))
				.projection(Projections.include("_id")).sort(Sorts.descending("started_at")).limit(MAX_MOUSE_SESSIONS)) {
			if (d.get("_id") instanceof String sid) {
				sessions.add(sid);
			}
		}
		for (String sid : sessions) {
			List<List<int[]>> paths = new ArrayList<>();
			List<MouseMotion.Click> clicks = new ArrayList<>();
			for (Document e : mongo.getCollection("replay_event")
					.find(Filters.and(Filters.eq("session_id", sid), Filters.in("type", "MOUSE_PATH", "CLICK"),
							Filters.gte("ts", since)))
					.projection(Projections.include("type", "x", "y", "path", "pointer"))
					.sort(Sorts.ascending("seq")).limit(MAX_MOUSE_EVENTS)) {
				readMouse(e, paths, clicks);
			}
			if (!paths.isEmpty()) { // 마우스 클릭이 없어도 직선·등속은 센다 (클릭을 터치라고 적는 봇, 2026-11-24)
				c.mouse(MouseMotion.analyze(paths, clicks));
			}
		}
	}

	/** 선점 뒤 이만큼 지나야 "녹화 없음"으로 본다. 녹화기는 5초(replay.batch_sec)마다 보내고 Kafka 적재가 조금 늦을 수 있다 */
	static final java.time.Duration HEADLESS_GRACE = java.time.Duration.ofSeconds(15);
	/** 녹화 장애 판단: 선점 시각 앞뒤 이 범위에 다른 세션의 녹화가 하나라도 있어야 센다 */
	static final java.time.Duration HEALTH_WINDOW = java.time.Duration.ofMinutes(10);

	/**
	 * 화면 없이 선점 (HEADLESS_HOLD): {@link SignalCollector#holdSessions}의 세션마다 화면 녹화가 있는지 본다.
	 * 사람은 화면으로만 좌석을 고르므로 그 로그인 세션에 녹화가 반드시 있다 (녹화기는 회원 화면 어디서나 돈다).
	 * <ul>
	 *   <li>선점한 지 {@link #HEADLESS_GRACE}가 안 된 세션은 아직 녹화가 도착하지 않았을 수 있어 세지 않는다</li>
	 *   <li><b>녹화 장애 보호</b>: 같은 시간대(±{@link #HEALTH_WINDOW})에 <b>다른</b> 세션의 녹화가 하나도 없으면
	 *       녹화가 꺼졌거나 고장 난 것 → 아무것도 세지 않는다 (Kafka 장애 때 전원 오탐 방지)</li>
	 * </ul>
	 */
	public void headless(SignalCollector c, Instant now) {
		var holds = c.holdSessions();
		if (holds.isEmpty()) {
			return;
		}
		Instant cutoff = now.minus(HEADLESS_GRACE);
		List<String> due = holds.entrySet().stream().filter(e -> e.getValue().isBefore(cutoff)).map(e -> e.getKey())
				.toList();
		if (due.isEmpty()) {
			return;
		}
		Instant first = due.stream().map(holds::get).min(Instant::compareTo).orElseThrow();
		Instant last = due.stream().map(holds::get).max(Instant::compareTo).orElseThrow();
		Document other = mongo.getCollection("replay_session").find(Filters.and(
				Filters.nin("_id", new ArrayList<>(holds.keySet())),
				Filters.gte("ended_at", Date.from(first.minus(HEALTH_WINDOW))),
				Filters.lte("started_at", Date.from(last.plus(HEALTH_WINDOW)))))
				.projection(Projections.include("_id")).limit(1).first();
		if (other == null) {
			return; // 녹화 장애로 보고 판단하지 않음
		}
		long headless = 0;
		for (String sid : due) {
			if (mongo.getCollection("replay_event").find(Filters.eq("session_id", sid))
					.projection(Projections.include("_id")).limit(1).first() == null || handsOff(sid)) {
				headless++;
			}
		}
		c.headlessHolds(headless);
	}

	/** 녹화는 있지만 사람 손이 움직인 흔적이 없는 세션 ({@link MouseMotion#handsOff}) */
	boolean handsOff(String sid) {
		List<List<int[]>> paths = new ArrayList<>();
		List<MouseMotion.Click> mouseClicks = new ArrayList<>();
		int clicks = 0;
		for (Document e : mongo.getCollection("replay_event")
				.find(Filters.and(Filters.eq("session_id", sid), Filters.in("type", "MOUSE_PATH", "CLICK")))
				.projection(Projections.include("type", "x", "y", "path", "pointer"))
				.sort(Sorts.ascending("seq")).limit(MAX_MOUSE_EVENTS)) {
			if ("CLICK".equals(e.getString("type"))) {
				clicks++;
			}
			readMouse(e, paths, mouseClicks);
		}
		return MouseMotion.handsOff(paths, mouseClicks, clicks);
	}

	/** 녹화 이벤트 하나 → 궤적 묶음 또는 마우스 클릭 자리. 형식이 이상한 값은 건너뛴다 */
	static void readMouse(Document e, List<List<int[]>> paths, List<MouseMotion.Click> clicks) {
		if ("CLICK".equals(e.getString("type"))) {
			// 마우스 클릭만 (휴대폰 탭·키보드·입력 장치를 모르는 예전 녹화는 빼서 오탐을 막는다)
			if ("m".equals(e.getString("pointer")) && e.get("x") instanceof Number x && e.get("y") instanceof Number y) {
				clicks.add(new MouseMotion.Click(x.intValue(), y.intValue()));
			}
			return;
		}
		if (e.get("path") instanceof List<?> raw) {
			List<int[]> path = new ArrayList<>(raw.size());
			for (Object p : raw) {
				if (p instanceof List<?> xs && xs.size() >= 3 && xs.get(0) instanceof Number t
						&& xs.get(1) instanceof Number x && xs.get(2) instanceof Number y) {
					path.add(new int[] {t.intValue(), x.intValue(), y.intValue()});
				}
			}
			if (!path.isEmpty()) {
				paths.add(path);
			}
		}
	}

	/** 행동 로그 문서 하나 → 신호. 적재 컨슈머가 형식을 검사한 문서지만 필드가 비어 있어도 멈추지 않게 읽는다 */
	static void apply(SignalCollector c, Document d) {
		Object interval = d.get("interval_ms");
		if (interval instanceof Number num) {
			c.interval(num.longValue());
		}
		Date actedAt = d.getDate("ts");
		if (actedAt != null && d.get("member_id") instanceof Number actor) {
			c.actor(actor.longValue(), actedAt.toInstant()); // 기기 대상의 계정 전환 간격 (MULTI_ACCOUNT_SYNC)
		}
		String type = d.getString("event_type");
		if (BehaviorType.VIEW_TRAIN.name().equals(type)) {
			// 화면이 사람 조작 없이 스스로 부른 조회(meta.auto)는 사람이 직접 찾은 것이 아니다
			if (!(d.get("meta") instanceof Document md && Boolean.TRUE.equals(md.get("auto")))) {
				c.search();
			}
			return;
		}
		if (BehaviorType.QUEUE_ENTER.name().equals(type)) {
			c.queueEnter();
			return;
		}
		Date at = d.getDate("ts");
		String session = d.get("session_id") instanceof String v ? v : null;
		// 응답을 다 보낸 시각 = 받은 시각 + 처리 시간. 서버가 느려도 "응답을 받고 다음 요청까지"를 잰다 (없으면 받은 시각)
		long elapsed = d.get("elapsed_ms") instanceof Number n ? Math.max(0, n.longValue()) : 0;
		if (BehaviorType.VIEW_SEAT.name().equals(type)) {
			if (at != null && !(d.get("meta") instanceof Document md && Boolean.TRUE.equals(md.get("auto")))) {
				c.seatView(session, at.toInstant().plusMillis(elapsed));
			}
			return;
		}
		if (BehaviorType.PAY.name().equals(type)) {
			if (at != null) {
				c.payStep(session, at.toInstant());
			}
			return;
		}
		if (!BehaviorType.SEAT_HOLD.name().equals(type)) {
			return;
		}
		Date ts = d.getDate("ts");
		if (ts != null && d.get("session_id") instanceof String sid) {
			c.holdSession(sid, ts.toInstant());
			Object seats = d.get("meta") instanceof Document md && md.get("seatIds") instanceof List<?> l ? l : null;
			c.holdStep(sid, ts.toInstant(), ts.toInstant().plusMillis(elapsed), seats);
		}
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
