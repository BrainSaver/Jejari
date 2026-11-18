package com.jejari.synthetic;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.hmac.Pseudonymizer;
import com.jejari.common.pii.Encrypted;
import com.jejari.common.pii.PiiCipher;
import com.jejari.common.pii.PiiField;
import com.jejari.member.MemberAuthEnabled;
import com.jejari.risk.SubjectType;
import com.jejari.risk.batch.RiskDirtyQueue;
import com.jejari.risk.batch.RiskSubject;
import com.mongodb.client.model.Filters;

/**
 * DATA-02 가상 회원·예매·행동 데이터 생성 (E·D). 시나리오 12(DATA-03, 조영준)와 <b>같은 이메일 라벨 규칙</b>
 * ({@code lt{tag}.{종류}.g{묶음}.{번호}@loadtest.example.com})을 써서 {@code loadtest/tools/eval_risk.sh}로 그대로 평가된다.
 *
 * <pre>
 * 종류     정답(eval_risk)   만드는 것
 * normal   NORMAL           사람 속도 행동 로그 + 사람다운 실수(가끔 연타, 인기 좌석 409 1~2번). 일부는 가족과 기기 공유(시간차).
 *                           경계 근처 유형: family(누적 매수 많음)·replan(취소 비율 높음)·biz(출발 직전 취소) — {@link #history}
 * fast     MACRO            20~60ms 간격 요청 50번                         → FAST_CLICK
 * retry    MACRO            같은 좌석 선점 실패(409) 8번, 0.3~0.6초 간격      → SAME_SEAT_RETRY
 * multi    RESELLER         3명이 한 기기·한 카드로 몇 초 안에 각자 선점       → MULTI_ACCOUNT_SYNC (기기), LINKED_ACCOUNTS (카드)
 * cancel   CANCEL_ABUSER    결제하고 1~3분 뒤 취소 3번                       → CANCEL_AFTER_PAY
 * </pre>
 * 결제 번호는 앱과 같이 {@code payment_id = reservation_id}.
 *
 * <ul>
 *   <li><b>실제 가입과 같은 형식</b>: CI는 {@link Pseudonymizer#mockCiHash}, 전화번호는 {@link PiiCipher}로 암호화, 카드·기기는 HMAC.
 *       그래서 위험 점수·관리자 화면·대기열이 실제 회원과 똑같이 읽는다. 비밀번호는 만들지 않는다 (로그인할 일이 없고,
 *       Argon2id가 1명에 0.1초라 1,000명이면 느림).</li>
 *   <li><b>과거 이력은 태그 전용 "출발한 운행"</b>에 쌓는다 (판매 중인 운행·테스트용 9001·9002의 좌석 재고를 건드리지 않음).</li>
 *   <li>행동 로그는 최근 몇 시간 안 (위험 점수 배치의 행동 집계 기간 1일 안), 구매·취소는 최근 30일 안.</li>
 *   <li>마지막에 CI·결제 수단·기기를 {@code risk:dirty}에 표시 → 앱의 1분 배치가 점수·케이스를 만든다.</li>
 *   <li>{@link #cleanup}으로 태그 데이터를 통째로 지운다. 가짜 이름·번호만 쓴다 (실제 개인정보 없음).</li>
 * </ul>
 */
@Service
@MemberAuthEnabled
public class SyntheticDataService {
	private static final Logger log = LoggerFactory.getLogger(SyntheticDataService.class);

	public static final String DOMAIN = "@loadtest.example.com";
	static final Pattern TAG = Pattern.compile("^[a-z0-9]{1,8}$");
	static final int SYNTHETIC_RUN_BASE = 880_000;
	static final String FROM = "3900023";
	static final String TO = "3900114";
	static final long FARE = 59_800;
	static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private static final String[] LAST = {"김", "이", "박", "최", "정", "강", "조", "윤", "장", "임", "한", "오", "서", "신", "권"};
	private static final String[] FIRST = {"민준", "서연", "도윤", "하은", "시우", "지민", "주원", "서윤", "하준", "지우",
			"예준", "수아", "은우", "채원", "건우", "다은"};

	private final JdbcTemplate jdbc;
	private final MongoTemplate mongo;
	private final TransactionTemplate tx;
	private final Pseudonymizer hmac;
	private final PiiCipher cipher;
	private final RiskDirtyQueue dirty;
	private final Clock clock;

	public SyntheticDataService(JdbcTemplate jdbc, MongoTemplate mongo, TransactionTemplate tx, Pseudonymizer hmac,
			PiiCipher cipher, RiskDirtyQueue dirty, Clock clock) {
		this.jdbc = jdbc;
		this.mongo = mongo;
		this.tx = tx;
		this.hmac = hmac;
		this.cipher = cipher;
		this.dirty = dirty;
		this.clock = clock;
	}

	/**
	 * @param members 전체 인원 (봇 비율: fast·retry·cancel 각 2%, multi 3%를 3명 묶음으로, 나머지 normal)
	 * @param seed    난수 씨앗 (같은 값이면 같은 모양의 데이터)
	 */
	public record Spec(String tag, int members, long seed) {
		public Spec {
			if (tag == null || !TAG.matcher(tag).matches()) {
				throw new IllegalArgumentException("태그는 영문 소문자·숫자 1~8자");
			}
			if (members < 10 || members > 20_000) {
				throw new IllegalArgumentException("인원은 10~20000");
			}
		}
	}

	public record Result(String tag, long runId, Map<String, Integer> counts, int reservations, int cancellations,
			int events) {
	}

	/**
	 * @param kind    이메일 라벨 종류 (normal·fast·retry·multi·cancel, eval_risk.sh가 읽음)
	 * @param profile 실제 행동 모양. normal은 plain·family·replan·biz로 나뉘고 나머지는 kind와 같음 (라벨은 그대로 NORMAL)
	 */
	private record Person(long id, String kind, String profile, int group, int no, String email, String ci, String device,
			String card) {
		Person withCi(String ci) {
			return new Person(id, kind, profile, group, no, email, ci, device, card);
		}

		Person withDevice(String device) {
			return new Person(id, kind, profile, group, no, email, ci, device, card);
		}
	}

	/** 과거 구매 한 건. cancelAfterMin이 null이면 취소 안 함 */
	private record Buy(int seats, Long cancelAfterMin, long beforeDepartureMin) {
	}

	// ------------------------------------------------------------ 생성

	public Result generate(Spec spec) {
		Random rnd = new Random(spec.seed());
		Instant now = clock.instant();
		if (count("SELECT COUNT(*) FROM member WHERE email LIKE ?", "lt" + spec.tag() + ".%") > 0) {
			throw new IllegalStateException("태그 " + spec.tag() + "의 데이터가 이미 있습니다. 다른 태그를 쓰거나 cleanup 하세요.");
		}
		long runId = createDepartedRun(spec.tag(), now);

		List<Person> planned = plan(spec, rnd);
		Map<Long, String> ciOf = insertMembers(planned, rnd, now);
		List<Person> people = planned.stream().map(p -> p.withCi(ciOf.get(p.id()))).toList();

		int[] resv = {0, 0};
		List<Document> events = new ArrayList<>();
		Instant base = now.minus(Duration.ofHours(3));
		for (Person p : people) {
			Instant t = base.plusSeconds(rnd.nextInt(2 * 3600));
			switch (p.kind()) {
				case "fast" -> events.addAll(clicks(p, t, 50, 20, 60, rnd));
				case "retry" -> events.addAll(retries(p, t, rnd));
				case "multi" -> { } // 묶음 단위로 아래에서
				default -> events.addAll(humanActivity(p, t, rnd));
			}
			int[] r = history(p, runId, now, rnd);
			resv[0] += r[0];
			resv[1] += r[1];
		}
		events.addAll(multiGroups(people, base, rnd));
		events.addAll(familySharing(people, base, rnd));
		if (!events.isEmpty()) {
			mongo.getCollection("behavior_log").insertMany(events);
		}

		markDirty(people);
		Map<String, Integer> counts = new java.util.LinkedHashMap<>();
		people.forEach(p -> counts.merge(p.kind(), 1, Integer::sum));
		log.info("가상 데이터 {}: 회원 {} {}, 예약 {}, 취소 {}, 행동 로그 {}", spec.tag(), people.size(), counts, resv[0],
				resv[1], events.size());
		return new Result(spec.tag(), runId, counts, resv[0], resv[1], events.size());
	}

	/** 인원 배분. multi는 3명 묶음 (같은 기기) */
	private List<Person> plan(Spec spec, Random rnd) {
		int n = spec.members();
		int fast = Math.max(1, n * 2 / 100);
		int retry = Math.max(1, n * 2 / 100);
		int cancel = Math.max(1, n * 2 / 100);
		int multiGroups = Math.max(1, n * 3 / 100 / 3);
		int normal = n - fast - retry - cancel - multiGroups * 3;
		List<Person> out = new ArrayList<>();
		addKind(out, spec.tag(), "normal", normal);
		addKind(out, spec.tag(), "fast", fast);
		addKind(out, spec.tag(), "retry", retry);
		addKind(out, spec.tag(), "cancel", cancel);
		for (int g = 0; g < multiGroups; g++) {
			// 되팔이 묶음은 기기도 카드도 하나를 같이 쓴다 (명의만 빌린 계정이라 결제는 한 사람 카드)
			String shared = hmac.device("syn-" + spec.tag() + "-multi-" + g);
			String sharedCard = null;
			for (int i = 0; i < 3; i++) {
				long id = nextMemberId();
				if (sharedCard == null) {
					sharedCard = hmac.paymentMethod(fakeCard(id));
				}
				out.add(new Person(id, "multi", "multi", g, i, email(spec.tag(), "multi", g, i), null, shared, sharedCard));
			}
		}
		return out;
	}

	private void addKind(List<Person> out, String tag, String kind, int count) {
		for (int i = 0; i < count; i++) {
			long id = nextMemberId();
			out.add(new Person(id, kind, "normal".equals(kind) ? normalProfile(i) : kind, 0, i, email(tag, kind, 0, i), null,
					hmac.device("syn-dev-" + tag + "-" + id), hmac.paymentMethod(fakeCard(id))));
		}
	}

	/**
	 * 정상 회원 중 경계 근처 유형 (조영준 계획 3장 #3). 50명마다 family 3 · replan 2 · biz 2 = 6% · 4% · 4%.
	 * 비율은 [가정] (근거 통계를 찾으면 바꿈). 순번으로 정해 인원이 적어도 유형마다 최소 몇 명은 생긴다.
	 */
	static String normalProfile(int i) {
		return switch (i % 50) {
			case 10, 11, 12 -> "family";
			case 20, 21 -> "replan";
			case 30, 31 -> "biz";
			default -> "plain";
		};
	}

	/** 가짜 카드번호 16자리. 9로 시작해 실제 카드사 번호(BIN)와 겹치지 않게, 회원 ID로 정해 회원마다 다름 */
	static String fakeCard(long memberId) {
		return String.format("9%015d", memberId);
	}

	static String email(String tag, String kind, int group, int no) {
		return "lt" + tag + "." + kind + ".g" + group + "." + no + DOMAIN;
	}

	private long nextMemberId() {
		return jdbc.queryForObject("SELECT member_seq.NEXTVAL FROM dual", Long.class);
	}

	/** 회원·카드·기기 연결. 실제 가입과 같은 해시·암호화 */
	private Map<Long, String> insertMembers(List<Person> people, Random rnd, Instant now) {
		Map<Long, String> ciOf = new java.util.HashMap<>();
		List<Object[]> members = new ArrayList<>();
		List<Object[]> cards = new ArrayList<>();
		List<Object[]> devices = new ArrayList<>();
		Set<String> seenDevice = new LinkedHashSet<>();
		Map<String, String> last4Of = new java.util.HashMap<>(); // 같이 쓰는 카드는 끝 4자리도 같다
		for (Person p : people) {
			String name = LAST[rnd.nextInt(LAST.length)] + FIRST[rnd.nextInt(FIRST.length)];
			String phone = "010" + String.format("%08d", Math.floorMod(p.id() * 7919L + 12345L, 100_000_000L));
			Encrypted enc = cipher.encrypt(phone, PiiField.MEMBER_PHONE, p.id());
			int ageDays = "normal".equals(p.kind()) ? 30 + rnd.nextInt(700) : 8 + rnd.nextInt(60); // 신규 계정 가산은 피함
			String ci = hmac.mockCiHash(name, phone);
			ciOf.put(p.id(), ci);
			members.add(new Object[] {p.id(), p.email(), ci, name, enc.cipherText(),
					hmac.phone(phone), enc.keyId(), Timestamp.from(now.minus(ageDays, ChronoUnit.DAYS))});
			String last4 = String.format("%04d", rnd.nextInt(10_000));
			cards.add(new Object[] {p.id(), p.card(), last4Of.computeIfAbsent(p.card(), k -> last4)});
			if (seenDevice.add(p.id() + "|" + p.device())) {
				devices.add(new Object[] {p.device(), p.id(), Timestamp.from(now.minus(ageDays, ChronoUnit.DAYS)),
						Timestamp.from(now)});
			}
		}
		tx.executeWithoutResult(st -> {
			jdbc.batchUpdate("""
					INSERT INTO member (member_id, email, ci_hash, name, phone_enc, phone_hash, enc_key_id, status, created_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)""", members);
			for (Object[] c : cards) {
				insertCard((Long) c[0], (String) c[1], (String) c[2]);
			}
			jdbc.batchUpdate("""
					INSERT INTO member_device (device_hash, member_id, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?)""",
					devices);
		});
		return ciOf;
	}

	/**
	 * 결제 수단 한 행. v19 전 행이 있는 DB에서는 payment_method_seq 번호가 옛 행과 겹칠 수 있어 다음 번호로 넘어간다
	 * (길동현 님 PaymentStartTx와 같은 방식, Oracle은 실패한 문장만 되돌림).
	 */
	private void insertCard(long memberId, String hash, String last4) {
		for (int i = 0; i < 10_000; i++) {
			long id = jdbc.queryForObject("SELECT payment_method_seq.NEXTVAL FROM dual", Long.class);
			try {
				jdbc.update("INSERT INTO payment_method (method_id, member_id, method_hash, method_type, card_last4) VALUES (?, ?, ?, 'CARD', ?)",
						id, memberId, hash, last4);
				return;
			} catch (org.springframework.dao.DuplicateKeyException e) {
				// 옛 행과 겹침 → 다음 번호
			}
		}
		throw new IllegalStateException("결제 수단 번호를 받지 못함");
	}

	/** 태그 전용 출발한 운행 (열차 1, 과거 날짜). 과거 구매·취소는 여기에 */
	private long createDepartedRun(String tag, Instant now) {
		long runId = jdbc.queryForObject(
				"SELECT NVL(MAX(run_id), ?) + 1 FROM train_run WHERE run_id BETWEEN ? AND ?", Long.class,
				SYNTHETIC_RUN_BASE, SYNTHETIC_RUN_BASE, SYNTHETIC_RUN_BASE + 9_999);
		LocalDate today = LocalDate.ofInstant(now, ZONE);
		for (int back = 2; back < 400; back++) { // (열차, 날짜) 유일 → 비어 있는 과거 날짜를 찾음
			LocalDate d = today.minusDays(back);
			if (count("SELECT COUNT(*) FROM train_run WHERE train_id = 1 AND run_date = ?", java.sql.Date.valueOf(d)) == 0) {
				jdbc.update("""
						INSERT INTO train_run (run_id, train_id, run_date, sale_open_at, sale_close_at, status)
						VALUES (?, 1, ?, ?, ?, 'DEPARTED')""", runId, java.sql.Date.valueOf(d),
						Timestamp.from(now.minus(Duration.ofDays(60))), Timestamp.from(now.minus(Duration.ofDays(back))));
				log.info("가상 데이터 {}: 출발한 운행 {} ({})", tag, runId, d);
				return runId;
			}
		}
		throw new IllegalStateException("가상 운행 날짜를 찾지 못함");
	}

	/** 과거 구매·취소. @return {예약 수, 취소 수} */
	/**
	 * 지난 20일 구매. 경계 근처 정상 유형(family·replan·biz)은 규칙 문턱이 아니라 "그런 사람이 실제로 어떻게 사나"로 정했다
	 * (조영준 계획 3장). 범위는 [가정].
	 * <pre>
	 * plain   0~2건, 1~2매. 10%는 한참 뒤(2~35시간) 1건 취소
	 * family  3~6건, 3~4매 (명절·주말 가족 이동)                            → 누적 매수가 많음 (BULK_PURCHASE 근처)
	 * replan  4~7건, 1~2매, 건마다 절반 확률로 1시간~2일 뒤 취소 후 다시 예매 → 취소 비율 높음 (HIGH_CANCEL_RATIO 근처)
	 * biz     4~8건, 1매, 건마다 40% 확률로 출발 10분~10시간 전 취소(일정 변경) → 출발 직전 취소 (LAST_MINUTE_CANCEL 근처)
	 * cancel  3건, 결제 1~3분 뒤 모두 취소 (봇)
	 * 그 밖   0~1건
	 * </pre>
	 * @return {예약 수, 취소 수}
	 */
	private int[] history(Person p, long runId, Instant now, Random rnd) {
		List<Buy> buys = new ArrayList<>();
		switch (p.profile()) {
			case "cancel" -> {
				for (int i = 0; i < 3; i++) buys.add(new Buy(1 + rnd.nextInt(2), 1L + rnd.nextInt(3), 2000 + rnd.nextInt(20_000)));
			}
			case "plain" -> {
				int n = rnd.nextInt(3);
				boolean lateCancel = n > 0 && rnd.nextInt(10) == 0;
				for (int i = 0; i < n; i++) {
					buys.add(new Buy(1 + rnd.nextInt(2), lateCancel && i == 0 ? 120L + rnd.nextInt(2000) : null,
							2000 + rnd.nextInt(20_000)));
				}
			}
			case "family" -> {
				int n = 3 + rnd.nextInt(4);
				for (int i = 0; i < n; i++) buys.add(new Buy(3 + rnd.nextInt(2), null, 0));
			}
			case "replan" -> {
				int n = 4 + rnd.nextInt(4);
				for (int i = 0; i < n; i++) {
					buys.add(new Buy(1 + rnd.nextInt(2), rnd.nextBoolean() ? 60L + rnd.nextInt(2880) : null,
							1440 + rnd.nextInt(5760)));
				}
			}
			case "biz" -> {
				int n = 4 + rnd.nextInt(5);
				for (int i = 0; i < n; i++) {
					buys.add(new Buy(1, rnd.nextInt(10) < 4 ? 60L + rnd.nextInt(1440) : null, 10 + rnd.nextInt(590)));
				}
			}
			default -> {
				if (rnd.nextBoolean()) buys.add(new Buy(1 + rnd.nextInt(2), null, 0));
			}
		}
		int cancels = 0;
		for (Buy b : buys) {
			Instant confirmed = now.minus(Duration.ofHours(6 + rnd.nextInt(24 * 20)));
			reservation(p, runId, confirmed, b, now);
			if (b.cancelAfterMin() != null) cancels++;
		}
		return new int[] {buys.size(), cancels};
	}

	private void reservation(Person p, long runId, Instant confirmed, Buy b, Instant now) {
		int seats = b.seats();
		long amount = FARE * seats;
		boolean canceled = b.cancelAfterMin() != null;
		// 취소 시각이 미래로 가지 않게 (구매는 최소 6시간 전)
		long minutesAfter = canceled ? Math.min(b.cancelAfterMin(), Duration.between(confirmed, now).toMinutes() - 1) : 0;
		tx.executeWithoutResult(st -> {
			long r = jdbc.queryForObject("SELECT reservation_seq.NEXTVAL FROM dual", Long.class);
			Instant canceledAt = confirmed.plus(Duration.ofMinutes(minutesAfter));
			jdbc.update("""
					INSERT INTO reservation (reservation_id, member_id, ci_hash, run_id, from_station_id, to_station_id,
					  seat_count, total_amount, status, channel, source, held_at, pay_deadline_at, confirmed_at, canceled_at,
					  idempotency_key, version)
					SELECT ?, member_id, ci_hash, ?, ?, ?, ?, ?, ?, 'WEB', 'NORMAL', ?, ?, ?, ?, ?, 0 FROM member WHERE member_id = ?""",
					r, runId, FROM, TO, seats, amount, canceled ? "CANCELED" : "CONFIRMED",
					Timestamp.from(confirmed.minusSeconds(120)), Timestamp.from(confirmed.plusSeconds(480)),
					Timestamp.from(confirmed), canceled ? Timestamp.from(canceledAt) : null,
					"syn-" + UUID.randomUUID(), p.id());
			long methodId = jdbc.queryForObject("SELECT method_id FROM payment_method WHERE member_id = ? FETCH FIRST 1 ROWS ONLY",
					Long.class, p.id());
			// payment_id = reservation_id: 앱(PaymentStartTx)과 같은 규칙. payment_seq를 쓰면 앱이 결제한 적 있는 DB에서
			// 앱이 쓴 번호(=예약 번호)와 겹쳐 ORA-00001 PK_PAYMENT로 멈춘다 (조영준 님 점검, 2026-11-17)
			jdbc.update("""
					INSERT INTO payment (payment_id, reservation_id, method_id, amount, status, pg_tx_id, idempotency_key,
					  requested_at, approved_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
					r, r, methodId, amount, canceled ? "REFUNDED" : "APPROVED", "syn-" + UUID.randomUUID(),
					"syn-" + UUID.randomUUID(), Timestamp.from(confirmed.minusSeconds(30)), Timestamp.from(confirmed));
			if (canceled) {
				long fee = minutesAfter < 60 ? 0 : amount / 10;
				jdbc.update("""
						INSERT INTO cancellation (cancel_id, reservation_id, cancel_type, canceled_at, minutes_after_purchase,
						  minutes_before_departure, refund_amount, cancel_fee, refund_status, resold_yn)
						VALUES (cancellation_seq.NEXTVAL, ?, 'USER', ?, ?, ?, ?, ?, 'DONE', 'N')""",
						r, Timestamp.from(canceledAt), minutesAfter, b.beforeDepartureMin(), amount - fee, fee);
			}
		});
	}

	// ------------------------------------------------------------ 행동 로그

	private List<Document> clicks(Person p, Instant start, int n, int minMs, int maxMs, Random rnd) {
		List<Document> out = new ArrayList<>();
		Instant t = start;
		for (int i = 0; i < n; i++) {
			int gap = minMs + rnd.nextInt(maxMs - minMs + 1);
			out.add(event(p, i == 0 ? "VIEW_TRAIN" : "VIEW_SEAT", t, i == 0 ? null : gap, 200, null));
			t = t.plusMillis(gap);
		}
		return out;
	}

	/**
	 * 정상 회원 행동 (조영준 계획 3장 #2). 사람 속도(0.8~6초) 클릭 8~22번에 사람다운 실수를 섞는다. 범위는 [가정].
	 * <ul>
	 *   <li>30%는 더블클릭·새로고침 연타: 200ms 미만 간격 1~4번 (60~190ms)</li>
	 *   <li>15%는 인기 좌석(20석)을 남과 동시에 눌러 선점 실패(409) 1~2번(1~4초 간격) → 다른 좌석으로 성공</li>
	 * </ul>
	 */
	private List<Document> humanActivity(Person p, Instant start, Random rnd) {
		int n = 8 + rnd.nextInt(15);
		Set<Integer> quickAt = new java.util.HashSet<>();
		if (rnd.nextInt(100) < 30) {
			int quick = 1 + rnd.nextInt(4);
			while (quickAt.size() < quick) quickAt.add(1 + rnd.nextInt(n - 1));
		}
		List<Document> out = new ArrayList<>();
		Instant t = start;
		for (int i = 0; i < n; i++) {
			int gap = quickAt.contains(i) ? 60 + rnd.nextInt(131) : 800 + rnd.nextInt(5201);
			t = t.plusMillis(i == 0 ? 0 : gap);
			out.add(event(p, i == 0 ? "VIEW_TRAIN" : "VIEW_SEAT", t, i == 0 ? null : gap, 200, null));
		}
		if (rnd.nextInt(100) < 15) {
			long popular = 400_000 + rnd.nextInt(20);
			int fails = 1 + rnd.nextInt(2);
			for (int i = 0; i < fails; i++) {
				int gap = 1000 + rnd.nextInt(3001);
				t = t.plusMillis(gap);
				out.add(event(p, "SEAT_HOLD", t, gap, 409, List.of(popular)));
			}
			int gap = 2000 + rnd.nextInt(4001);
			t = t.plusMillis(gap);
			out.add(event(p, "SEAT_HOLD", t, gap, 201, List.of(401_000L + rnd.nextInt(1000))));
		}
		return out;
	}

	private List<Document> retries(Person p, Instant start, Random rnd) {
		List<Document> out = new ArrayList<>();
		long seat = 100_000 + rnd.nextInt(500);
		Instant t = start;
		for (int i = 0; i < 8; i++) {
			int gap = 300 + rnd.nextInt(301);
			out.add(event(p, "SEAT_HOLD", t, i == 0 ? null : gap, 409, List.of(seat)));
			t = t.plusMillis(gap);
		}
		return out;
	}

	/** 묶음마다 같은 기기로 몇 초 안에 각자 선점 */
	private List<Document> multiGroups(List<Person> people, Instant base, Random rnd) {
		List<Document> out = new ArrayList<>();
		Map<Integer, List<Person>> groups = new java.util.TreeMap<>();
		people.stream().filter(p -> "multi".equals(p.kind())).forEach(p -> groups.computeIfAbsent(p.group(),
				k -> new ArrayList<>()).add(p));
		for (List<Person> g : groups.values()) {
			Instant t = base.plusSeconds(rnd.nextInt(2 * 3600));
			for (Person p : g) {
				out.add(event(p, "VIEW_SEAT", t, null, 200, null));
				out.add(event(p, "SEAT_HOLD", t.plusSeconds(2), 2000, 201, List.of(200_000L + rnd.nextInt(500))));
				t = t.plusSeconds(3 + rnd.nextInt(5));
			}
		}
		return out;
	}

	/** 정상 회원 5%는 가족과 기기를 같이 쓴다. 선점은 몇 시간 차이 (다계정 동시 선점이 아님 → 걸리면 오탐) */
	private List<Document> familySharing(List<Person> people, Instant base, Random rnd) {
		List<Person> normals = people.stream().filter(p -> "normal".equals(p.kind())).toList();
		List<Document> out = new ArrayList<>();
		List<Object[]> links = new ArrayList<>();
		for (int i = 0; i + 1 < normals.size(); i += 40) { // 40명 중 2명 = 5%
			Person a = normals.get(i);
			Person b = normals.get(i + 1);
			Person sharedB = b.withDevice(a.device());
			out.add(event(a, "SEAT_HOLD", base.plusSeconds(rnd.nextInt(600)), 3000, 201, List.of(300_000L + i)));
			out.add(event(sharedB, "SEAT_HOLD", base.plusSeconds(4 * 3600 + rnd.nextInt(600)), 3000, 201,
					List.of(300_001L + i)));
			links.add(new Object[] {a.device(), b.id(), Timestamp.from(base), Timestamp.from(base)});
		}
		if (!links.isEmpty()) {
			jdbc.batchUpdate("INSERT INTO member_device (device_hash, member_id, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?)",
					links);
		}
		return out;
	}

	private Document event(Person p, String type, Instant ts, Integer intervalMs, int status, List<Long> seats) {
		Document meta = new Document();
		if (seats != null) {
			meta.append("seatIds", seats).append("seatCount", (long) seats.size());
		}
		return new Document("event_id", UUID.randomUUID().toString()).append("member_id", p.id()).append("ci_hash", p.ci())
				.append("device_hash", p.device()).append("ip_hash", null).append("session_id", null)
				.append("event_type", type).append("run_id", 9001L).append("ts", Date.from(ts))
				.append("interval_ms", intervalMs).append("user_agent", "synthetic").append("channel", "WEB")
				.append("response_status", status).append("meta", meta)
				.append("expire_at", Date.from(ts.plus(180, ChronoUnit.DAYS)));
	}

	private void markDirty(List<Person> people) {
		List<RiskSubject> subjects = new ArrayList<>();
		for (Person p : people) {
			subjects.add(new RiskSubject(SubjectType.CI, p.ci()));
			subjects.add(new RiskSubject(SubjectType.PAYMENT, p.card()));
			subjects.add(new RiskSubject(SubjectType.DEVICE, p.device()));
		}
		dirty.markAll(subjects);
	}

	// ------------------------------------------------------------ 지우기

	/** 태그 데이터를 모두 지운다 (회원·카드·기기·예약·결제·취소·라벨·점수·케이스·행동 로그·가상 운행) */
	public int cleanup(String tag) {
		if (tag == null || !TAG.matcher(tag).matches()) {
			throw new IllegalArgumentException("태그는 영문 소문자·숫자 1~8자");
		}
		List<Long> ids = jdbc.queryForList("SELECT member_id FROM member WHERE email LIKE ?", Long.class, "lt" + tag + ".%");
		if (ids.isEmpty()) {
			return 0;
		}
		Set<String> subjects = new LinkedHashSet<>();
		List<Long> runs = new ArrayList<>();
		for (long id : ids) {
			subjects.addAll(jdbc.queryForList("SELECT ci_hash FROM member WHERE member_id = ?", String.class, id));
			subjects.addAll(jdbc.queryForList("SELECT method_hash FROM payment_method WHERE member_id = ?", String.class, id));
			subjects.addAll(jdbc.queryForList("SELECT device_hash FROM member_device WHERE member_id = ?", String.class, id));
			runs.addAll(jdbc.queryForList("SELECT DISTINCT run_id FROM reservation WHERE member_id = ? AND run_id >= ?",
					Long.class, id, SYNTHETIC_RUN_BASE));
		}
		tx.executeWithoutResult(st -> {
			for (String h : subjects) {
				jdbc.update("DELETE FROM case_marker WHERE case_id IN (SELECT case_id FROM suspicious_case WHERE subject_hash = ?)", h);
				jdbc.update("DELETE FROM suspicious_case WHERE subject_hash = ?", h);
				jdbc.update("DELETE FROM risk_score_history WHERE subject_hash = ?", h);
				jdbc.update("DELETE FROM risk_score WHERE subject_hash = ?", h);
				jdbc.update("DELETE FROM admin_action WHERE subject_hash = ?", h);
			}
			for (long id : ids) {
				String mine = "SELECT reservation_id FROM reservation WHERE member_id = " + id;
				for (String t : List.of("cancellation", "payment", "reservation_history", "saga_instance", "reservation_seat",
						"seat_inventory")) {
					jdbc.update("DELETE FROM " + t + " WHERE reservation_id IN (" + mine + ")");
				}
				for (String t : List.of("reservation", "waitlist", "payment_method", "member_device", "synthetic_label",
						"auth_session", "identity_verification", "member_credential")) {
					jdbc.update("DELETE FROM " + t + " WHERE member_id = ?", id);
				}
				jdbc.update("DELETE FROM member WHERE member_id = ?", id);
			}
			for (long run : new LinkedHashSet<>(runs)) {
				if (count("SELECT COUNT(*) FROM reservation WHERE run_id = ?", run) == 0) {
					jdbc.update("DELETE FROM train_run WHERE run_id = ?", run);
				}
			}
		});
		mongo.getCollection("behavior_log").deleteMany(Filters.in("member_id", new ArrayList<>(ids)));
		log.info("가상 데이터 {} 삭제: 회원 {}명", tag, ids.size());
		return ids.size();
	}

	private long count(String sql, Object... args) {
		Long n = jdbc.queryForObject(sql, Long.class, args);
		return n == null ? 0 : n;
	}
}
