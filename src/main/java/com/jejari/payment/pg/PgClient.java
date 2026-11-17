package com.jejari.payment.pg;

/**
 * PG(결제대행사) 연동 경계. 1차는 {@link MockPgClient}, 여유가 있으면 실제 PG 테스트 모드로 바꿉니다 (기능설계서 11장 #1).
 *
 * <p>주문 번호(orderId)는 payment_id입니다. PG는 같은 주문 번호의 승인 요청을 한 번만 처리한다고 가정합니다
 * (실제 PG도 주문 번호 중복을 막음). 그래서 승인 요청을 다시 보내도 돈이 두 번 빠지지 않습니다.
 *
 * <p>모든 메서드는 <b>DB 트랜잭션 밖에서</b> 부릅니다 (concurrency.md §0.6).
 */
public interface PgClient {

	/** 승인 요청에 대한 즉시 응답 */
	enum Outcome {
		/** 승인됨 → §5.1 */
		APPROVED,
		/** 거절됨 → §5.2 */
		DECLINED,
		/** 응답 없음·시간 초과. 돈이 빠졌는지 모름 → §6.2 스캐너가 나중에 조회 */
		UNKNOWN
	}

	record ApproveResult(Outcome outcome, String pgTxId, String reason) {
	}

	/** 조회 결과 (§6.2 표) */
	enum Status {
		APPROVED,
		FAILED,
		/** PG가 그 주문을 받은 적 없음 */
		NOT_FOUND,
		/** 모름·PG 장애 */
		UNKNOWN
	}

	record Inquiry(Status status, String pgTxId, String reason) {
	}

	ApproveResult approve(long orderId, long amount, String cardNumber);

	Inquiry inquire(long orderId);

	/**
	 * 받은 적 없는 주문의 승인을 막습니다 (§6.2 "없음"). 나중에 늦게 도착한 승인 요청도 거절되게 합니다.
	 *
	 * @return 막힌 것이 확인되면 true. 이미 승인됐거나 확인할 수 없으면 false
	 */
	boolean blockOrder(long orderId);
}
