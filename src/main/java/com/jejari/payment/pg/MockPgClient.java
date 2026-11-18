package com.jejari.payment.pg;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 가짜 PG. 결과를 카드번호 끝자리로 정해서 테스트·시연에서 같은 상황을 언제든 다시 만들 수 있습니다 (기능설계서 11장 #1).
 *
 * <pre>
 * 끝자리 0  거절                       → 즉시 DECLINED, 조회해도 FAILED
 * 끝자리 9  응답 없음 (결과 모름)        → 즉시 UNKNOWN, 조회해도 계속 UNKNOWN (§6.2 "모름" 경로)
 * 끝자리 8  지연 후 승인                 → 즉시 UNKNOWN, 조회하면 APPROVED (스캐너가 확정하는 경로)
 * 나머지    승인                        → 즉시 APPROVED
 * </pre>
 *
 * 주문 상태는 메모리에만 있어 앱을 다시 켜면 사라집니다. 그러면 조회 결과가 NOT_FOUND가 되고, 스캐너는
 * 주문을 막은 뒤 실패로 정리합니다 (§6.2 "없음" 경로). 실제 PG에서 요청이 도착하지 않은 경우와 같습니다.
 */
public class MockPgClient implements PgClient {

	private static final Logger log = LoggerFactory.getLogger(MockPgClient.class);

	private enum State {
		APPROVED, FAILED, UNKNOWN, BLOCKED
	}

	private record Order(State state, String pgTxId, String reason) {
	}

	private final Map<Long, Order> orders = new ConcurrentHashMap<>();
	/** 승인 요청을 실제로 처리한 횟수 (같은 주문의 재요청은 세지 않음). 테스트에서 "PG에 한 번만 갔다"를 확인할 때 씀 */
	private final AtomicInteger processed = new AtomicInteger();

	@Override
	public ApproveResult approve(long orderId, long amount, String cardNumber) {
		Order order = orders.computeIfAbsent(orderId, id -> {
			processed.incrementAndGet();
			return decide(cardNumber);
		});
		log.info("[MockPG] 승인 요청 주문 {} 금액 {} → {}", orderId, amount, order.state());
		return switch (order.state()) {
			case APPROVED -> lastDigit(cardNumber) == '8'
					? new ApproveResult(Outcome.UNKNOWN, null, "TIMEOUT") // 지연: 앱은 응답을 못 받음
					: new ApproveResult(Outcome.APPROVED, order.pgTxId(), null);
			case FAILED, BLOCKED -> new ApproveResult(Outcome.DECLINED, null, order.reason());
			case UNKNOWN -> new ApproveResult(Outcome.UNKNOWN, null, "TIMEOUT");
		};
	}

	@Override
	public Inquiry inquire(long orderId) {
		Order order = orders.get(orderId);
		if (order == null) {
			return new Inquiry(Status.NOT_FOUND, null, null);
		}
		return switch (order.state()) {
			case APPROVED -> new Inquiry(Status.APPROVED, order.pgTxId(), null);
			case FAILED, BLOCKED -> new Inquiry(Status.FAILED, null, order.reason());
			case UNKNOWN -> new Inquiry(Status.UNKNOWN, null, null);
		};
	}

	@Override
	public boolean blockOrder(long orderId) {
		Order now = orders.putIfAbsent(orderId, new Order(State.BLOCKED, null, "PG_NOT_RECEIVED"));
		return now == null || now.state() == State.BLOCKED || now.state() == State.FAILED;
	}

	private final Map<String, RefundResult> refunds = new ConcurrentHashMap<>();

	/**
	 * 환불은 바로 성공한다. 승인 거래 ID가 없거나 금액이 음수면 실패.
	 * 같은 환불 키는 <b>성공만</b> 기억한다 (실제 PG처럼): 실패를 기억하면 환불 재시도(CNL-03)가 영원히 같은 실패를 받는다.
	 */
	@Override
	public RefundResult refund(long orderId, String pgTxId, long amount, String refundKey) {
		RefundResult r = refunds.get(refundKey);
		if (r == null) {
			r = pgTxId == null || amount < 0 ? new RefundResult(false, null, "NO_APPROVED_TX")
					: refunds.computeIfAbsent(refundKey, k -> new RefundResult(true, "mock-refund-" + UUID.randomUUID(), null));
		}
		log.info("[MockPG] 환불 요청 주문 {} 금액 {} → {}", orderId, amount, r.done() ? "DONE" : r.reason());
		return r;
	}

	/** 성공한 환불 수 (같은 환불 키 재요청은 세지 않음, 실패는 기억하지 않음) */
	public int refundCount() {
		return refunds.size();
	}

	/** 승인 요청을 실제로 처리한 횟수 */
	public int processedCount() {
		return processed.get();
	}

	private static Order decide(String cardNumber) {
		return switch (lastDigit(cardNumber)) {
			case '0' -> new Order(State.FAILED, null, "CARD_DECLINED");
			case '9' -> new Order(State.UNKNOWN, null, null);
			default -> new Order(State.APPROVED, "mock-" + UUID.randomUUID(), null); // 8도 PG 안에서는 승인
		};
	}

	private static char lastDigit(String cardNumber) {
		return cardNumber == null || cardNumber.isEmpty() ? ' ' : cardNumber.charAt(cardNumber.length() - 1);
	}
}
