package com.jejari.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.jejari.common.error.BusinessException;
import com.jejari.event.EventEnvelope;
import com.jejari.event.EventTopics;
import com.jejari.event.EventType;
import com.jejari.event.inbox.InboxProcessor;
import com.jejari.notification.NotificationService.Page;
import com.jejari.seat.SeatHoldService;
import com.jejari.seat.SeatHoldService.HoldCommand;
import com.jejari.seat.SeatHoldService.SeatRequest;
import com.jejari.support.NoRunningApp;

import tools.jackson.databind.json.JsonMapper;

/**
 * EVT-06 알림을 실제 Oracle로 확인한다. Kafka 소비자는 띄우지 않고 그 안의 처리
 * ({@link InboxProcessor} + {@link NotificationEventHandler})를 직접 부른다. 스캐너·발송기도 직접 부른다.
 */
@Tag("infra")
@ExtendWith(NoRunningApp.class)
@SpringBootTest(properties = {
		"spring.data.redis.database=15",
		"jejari.queue.scheduler-enabled=false",
		"jejari.risk.cache-resync-enabled=false",
		"jejari.seat.expiry-scanner-enabled=false",
		"jejari.payment.recheck-scanner-enabled=false",
		"jejari.event.relay.enabled=false",
		"jejari.notification.consumer-enabled=false",
		"jejari.notification.reminder-enabled=false",
		"jejari.notification.dispatcher-enabled=false"})
class NotificationInfraTest {

	static final long RUN = 9001;
	static final String FROM = "3900023";
	static final String TO = "3900114";
	static final long MEMBER_BASE = 950000;
	static final AtomicLong NEXT_MEMBER = new AtomicLong(MEMBER_BASE + 1);

	@Autowired
	InboxProcessor inbox;
	@Autowired
	NotificationEventHandler handler;
	@Autowired
	NotificationWriter writer;
	@Autowired
	NotificationService service;
	@Autowired
	SeatHoldService hold;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	TransactionTemplate tx;
	@Autowired
	JsonMapper json;
	@Autowired
	Clock clock;

	PayReminderScanner reminder;
	NotificationDispatcher dispatcher;
	List<Long> seatIds;
	int seatCursor;
	final List<String> eventIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		cleanDb();
		redis.execute((RedisCallback<Object>) c -> {
			c.serverCommands().flushDb();
			return null;
		});
		NotificationPolicy policy = new NotificationPolicy(jdbc);
		reminder = new PayReminderScanner(jdbc, policy, clock);
		dispatcher = new NotificationDispatcher(jdbc, policy);
		// 이 운행에서 아무도 안 쓰는 좌석만 (로컬·공용 DB에 시연·부하 테스트 예약이 남아 있어도 겹치지 않게)
		seatIds = jdbc.queryForList("""
				SELECT seat_id FROM seat WHERE train_id = 1
				   AND seat_id NOT IN (SELECT seat_id FROM seat_inventory WHERE run_id = ?)
				 ORDER BY seat_id""", Long.class, RUN);
		seatCursor = 0;
	}

	@AfterEach
	void tearDown() {
		cleanDb();
	}

	// ------------------------------------------------------------ 이벤트 → 알림

	@Test
	void 만료_이벤트는_EXPIRED_알림_한건_같은_이벤트가_두번_와도_한건() {
		long m = newMember();
		long r = holdSeat(m);
		EventEnvelope e = event(EventType.RESERVATION_EXPIRED, r);

		boolean first = inbox.process(EventTopics.GROUP_NOTIFICATION, e, handler::handle);
		boolean again = inbox.process(EventTopics.GROUP_NOTIFICATION, e, handler::handle);

		assertThat(first).isTrue();
		assertThat(again).isFalse();
		assertThat(count("SELECT COUNT(*) FROM notification WHERE member_id = ? AND type = 'EXPIRED' AND ref_id = ?", m, r))
				.isEqualTo(1);
	}

	@Test
	void 환불_완료_이벤트는_REFUNDED_알림() {
		long m = newMember();
		long r = holdSeat(m);

		inbox.process(EventTopics.GROUP_NOTIFICATION, event(EventType.REFUND_COMPLETED, r), handler::handle);

		assertThat(count("SELECT COUNT(*) FROM notification WHERE member_id = ? AND type = 'REFUNDED' AND ref_id = ?", m, r))
				.isEqualTo(1);
	}

	@Test
	void 예매_완료_이벤트는_CONFIRMED_알림이고_알림함에_열차_시각_구간_좌석이_붙는다() {
		long m = newMember();
		long other = newMember();
		long r = holdSeat(m);
		EventEnvelope e = event(EventType.RESERVATION_CONFIRMED, r);
		tx.executeWithoutResult(st -> handler.handle(e));
		assertThat(count("SELECT COUNT(*) FROM notification WHERE member_id = ? AND type = 'CONFIRMED' AND ref_id = ?", m, r))
				.isOne();
		dispatcher.dispatchOnce();

		NotificationService.Item item = service.list(m, null, 20).items().get(0);
		assertThat(item.type()).isEqualTo("CONFIRMED");
		NotificationService.Trip trip = item.trip();
		assertThat(trip).isNotNull();
		assertThat(trip.trainNo()).isNotBlank();
		assertThat(trip.runDate()).isNotNull();
		assertThat(trip.fromName()).isNotBlank();
		assertThat(trip.toName()).isNotBlank();
		assertThat(trip.seats()).matches("[0-9]+호차 .+");
		// 남의 예약 번호를 가리키는 알림이 있어도 그 표 내용은 붙지 않는다
		tx.executeWithoutResult(st -> writer.create(other, NotificationType.CONFIRMED, "RESERVATION", r));
		dispatcher.dispatchOnce();
		assertThat(service.list(other, null, 20).items().get(0).trip()).isNull();
	}

	@Test
	void DB_제약에_CONFIRMED가_아직_없으면_예매_완료_알림만_건너뛰고_멈추지_않는다() {
		long m = newMember();
		long r = holdSeat(m);
		String withConfirmed = "type IN ('WAITLIST_OFFER', 'PAY_REMINDER', 'EXPIRED', 'WAITLIST_SKIPPED', 'REFUNDED', 'CONFIRMED')";
		String old = "type IN ('WAITLIST_OFFER', 'PAY_REMINDER', 'EXPIRED', 'WAITLIST_SKIPPED', 'REFUNDED')";
		jdbc.execute("ALTER TABLE notification DROP CONSTRAINT ck_notification_type");
		jdbc.execute("ALTER TABLE notification ADD CONSTRAINT ck_notification_type CHECK (" + old + ")"); // ERD 패치 전 DB
		try {
			NotificationEventHandler fresh = new NotificationEventHandler(jdbc, writer); // 확인 결과를 기억하지 않은 새것
			tx.executeWithoutResult(st -> fresh.handle(event(EventType.RESERVATION_CONFIRMED, r))); // 예외 없이 끝남
			tx.executeWithoutResult(st -> fresh.handle(event(EventType.RESERVATION_EXPIRED, r))); // 다른 알림은 그대로
			assertThat(count("SELECT COUNT(*) FROM notification WHERE member_id = ? AND type = 'CONFIRMED'", m)).isZero();
			assertThat(count("SELECT COUNT(*) FROM notification WHERE member_id = ? AND type = 'EXPIRED'", m)).isOne();
		} finally {
			jdbc.execute("ALTER TABLE notification DROP CONSTRAINT ck_notification_type");
			jdbc.execute("ALTER TABLE notification ADD CONSTRAINT ck_notification_type CHECK (" + withConfirmed + ")");
		}
	}

	@Test
	void 알림을_만들지_않는_이벤트는_도장도_찍지_않는다() {
		assertThat(handler.handles(EventType.RESERVATION_EXPIRED.name())).isTrue();
		assertThat(handler.handles(EventType.REFUND_COMPLETED.name())).isTrue();
		assertThat(handler.handles(EventType.RESERVATION_CONFIRMED.name())).isTrue(); // 예매 완료 (CONFIRMED)
		// 제안·건너뜀 알림은 강동균의 제안 트랜잭션이 이미 남긴다. 선점·결제 시작은 알림 종류에 없다
		assertThat(handler.handles(EventType.WAITLIST_OFFERED.name())).isFalse();
		assertThat(handler.handles(EventType.SEAT_HELD.name())).isFalse();
		assertThat(handler.handles(null)).isFalse();
	}

	@Test
	void 처리_중_실패하면_알림과_도장이_같이_롤백된다() {
		long m = newMember();
		long r = holdSeat(m);
		EventEnvelope e = event(EventType.RESERVATION_EXPIRED, r);

		assertThatThrownBy(() -> inbox.process(EventTopics.GROUP_NOTIFICATION, e, ev -> {
			handler.handle(ev);
			throw new IllegalStateException("일부러");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(count("SELECT COUNT(*) FROM notification WHERE member_id = ?", m)).isZero();
		assertThat(count("SELECT COUNT(*) FROM processed_event WHERE consumer_group = ? AND event_id = ?",
				EventTopics.GROUP_NOTIFICATION, e.eventId())).isZero();
		// 다시 오면 이번엔 처리된다
		assertThat(inbox.process(EventTopics.GROUP_NOTIFICATION, e, handler::handle)).isTrue();
		assertThat(count("SELECT COUNT(*) FROM notification WHERE member_id = ?", m)).isEqualTo(1);
	}

	@Test
	void Kafka_메시지_형식을_읽고_깨진_메시지는_건너뛴다() {
		NotificationConsumer consumer = new NotificationConsumer(json, inbox, handler);
		EventEnvelope e = event(EventType.RESERVATION_EXPIRED, 1);

		assertThat(consumer.parse(e.toJson(json)).eventId()).isEqualTo(e.eventId());
		assertThat(consumer.parse("{not json")).isNull();
		assertThat(consumer.parse(null)).isNull();
		assertThat(consumer.parse("{\"eventType\":\"RESERVATION_EXPIRED\"}")).isNull();   // eventId 없음
	}

	// ------------------------------------------------------------ 결제 기한 임박

	@Test
	void 기한이_임박한_HOLD_예약에만_PAY_REMINDER를_한_번_만든다() {
		// 매수 한도에 걸리지 않게 예약마다 다른 회원
		long soon = holdSeat(newMember());
		long later = holdSeat(newMember());
		long paying = holdSeat(newMember());
		LocalDateTime now = LocalDateTime.now(clock);
		jdbc.update("UPDATE reservation SET pay_deadline_at = ? WHERE reservation_id IN (?, ?)", now.plusSeconds(60),
				soon, paying);
		jdbc.update("UPDATE reservation SET status = 'PAYMENT_PENDING' WHERE reservation_id = ?", paying);
		// later는 기본 마감(10분 뒤)이라 아직 아님

		reminder.scanOnce();
		reminder.scanOnce();   // 두 번 돌아도 예약당 1건

		assertThat(reminders(soon)).isEqualTo(1);
		assertThat(reminders(later)).isZero();
		assertThat(reminders(paying)).isZero();
	}

	@Test
	void 기한이_이미_지난_예약에는_보내지_않는다() {
		long m = newMember();
		long r = holdSeat(m);
		jdbc.update("UPDATE reservation SET pay_deadline_at = ? WHERE reservation_id = ?",
				LocalDateTime.now(clock).minusSeconds(1), r);

		reminder.scanOnce();

		assertThat(reminders(r)).isZero();
	}

	// ------------------------------------------------------------ 발송 · 알림함

	@Test
	void 발송된_알림만_알림함에_보이고_읽음_표시는_내_것만() {
		long m = newMember();
		long other = newMember();
		long r = holdSeat(m);
		tx.executeWithoutResult(st -> {
			writer.create(m, NotificationType.PAY_REMINDER, "RESERVATION", r);
			writer.create(m, NotificationType.EXPIRED, "RESERVATION", r);
		});
		// 강동균의 제안 트랜잭션과 같은 모양의 행 (PUSH, sent_at 없음)도 같은 발송기로 나간다
		jdbc.update("""
				INSERT INTO notification (notification_id, member_id, type, ref_type, ref_id, channel)
				VALUES (notification_seq.NEXTVAL, ?, 'WAITLIST_OFFER', 'RESERVATION', ?, 'PUSH')""", other, r);

		assertThat(service.list(m, null, 20).items()).isEmpty();   // 발송 전에는 안 보임

		dispatcher.dispatchOnce();

		assertThat(count("SELECT COUNT(*) FROM notification WHERE member_id IN (?, ?) AND sent_at IS NULL", m, other))
				.isZero();
		Page page = service.list(m, null, 20);
		assertThat(page.items()).extracting(NotificationService.Item::type).containsExactly("EXPIRED", "PAY_REMINDER");
		assertThat(page.unreadCount()).isEqualTo(2);

		long id = page.items().get(0).notificationId();
		service.markRead(m, id);
		LocalDateTime readAt = jdbc.queryForObject("SELECT read_at FROM notification WHERE notification_id = ?",
				LocalDateTime.class, id);
		service.markRead(m, id);   // 다시 눌러도 성공, 처음 시각 유지
		assertThat(jdbc.queryForObject("SELECT read_at FROM notification WHERE notification_id = ?", LocalDateTime.class,
				id)).isEqualTo(readAt);
		assertThat(service.list(m, null, 20).unreadCount()).isEqualTo(1);

		// 남의 알림은 없는 것과 같다
		assertThatThrownBy(() -> service.markRead(other, id)).isInstanceOf(BusinessException.class)
				.extracting(ex -> ((BusinessException) ex).getErrorCode())
				.isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
		assertThat(service.list(other, null, 20).items()).extracting(NotificationService.Item::type)
				.containsExactly("WAITLIST_OFFER");
	}

	@Test
	void 알림함은_최신순으로_나눠_본다() {
		long m = newMember();
		long r = holdSeat(m);
		tx.executeWithoutResult(st -> {
			for (int i = 0; i < 3; i++) {
				writer.create(m, NotificationType.EXPIRED, "RESERVATION", r);
			}
		});
		dispatcher.dispatchOnce();

		Page p1 = service.list(m, null, 2);
		assertThat(p1.items()).hasSize(2);
		assertThat(p1.nextBefore()).isNotNull();
		Page p2 = service.list(m, p1.nextBefore(), 2);
		assertThat(p2.items()).hasSize(1);
		assertThat(p2.nextBefore()).isNull();
		assertThat(p2.items().get(0).notificationId()).isLessThan(p1.items().get(1).notificationId());
	}

	// ------------------------------------------------------------ 준비

	private EventEnvelope event(EventType type, long reservationId) {
		String id = UUID.randomUUID().toString();
		eventIds.add(id);
		return new EventEnvelope(id, type.name(), "RESERVATION", Long.toString(reservationId), 1,
				LocalDateTime.now(clock), json.createObjectNode());
	}

	private long holdSeat(long memberId) {
		long seat = seatIds.get(seatIds.size() / 3 + seatCursor++);
		return hold.hold(memberId, new HoldCommand(RUN, FROM, TO, List.of(new SeatRequest(seat, "ADULT")), "WEB",
				UUID.randomUUID().toString())).reservationId();
	}

	private long newMember() {
		long id = NEXT_MEMBER.getAndIncrement();
		jdbc.update("INSERT INTO member (member_id, email, ci_hash, name, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
				id, "noti" + id + "@infra-test.jejari.dev", "noti-ci-" + id, "테스트");
		return id;
	}

	private long reminders(long reservationId) {
		return count("SELECT COUNT(*) FROM notification WHERE type = 'PAY_REMINDER' AND ref_id = ?", reservationId);
	}

	private long count(String sql, Object... args) {
		Long v = jdbc.queryForObject(sql, Long.class, args);
		return v == null ? 0 : v;
	}

	private void cleanDb() {
		long lo = MEMBER_BASE + 1;
		long hi = MEMBER_BASE + 9999;
		String mine = "SELECT reservation_id FROM reservation WHERE member_id BETWEEN " + lo + " AND " + hi;
		for (String id : eventIds) {
			jdbc.update("DELETE FROM processed_event WHERE consumer_group = ? AND event_id = ?",
					EventTopics.GROUP_NOTIFICATION, id);
		}
		eventIds.clear();
		jdbc.update("DELETE FROM notification WHERE member_id BETWEEN ? AND ?", lo, hi);
		jdbc.update("DELETE FROM outbox_event WHERE aggregate_type = 'RESERVATION' AND aggregate_id IN ("
				+ "SELECT TO_CHAR(reservation_id) FROM reservation WHERE member_id BETWEEN ? AND ?)", lo, hi);
		jdbc.update("DELETE FROM reservation_history WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM saga_instance WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation_seat WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_inventory WHERE reservation_id IN (" + mine + ")");
		jdbc.update("DELETE FROM reservation WHERE member_id BETWEEN ? AND ?", lo, hi);
		jdbc.update("DELETE FROM purchase_counter WHERE ci_hash LIKE 'noti-ci-%'");
		jdbc.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?", lo, hi);
	}
}
