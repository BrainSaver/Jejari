package com.jejari.payment.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.jejari.auth.AuthPrincipal;
import com.jejari.behavior.BehaviorLog;
import com.jejari.behavior.BehaviorType;
import com.jejari.common.error.BusinessException;
import com.jejari.payment.PaymentService;
import com.jejari.payment.PaymentService.PaymentResult;

/**
 * PAY-01 결제 시작 API (기능설계서 7장: POST /reservations/{id}/payments, 회원 + 멱등).
 * 응답은 Mock PG 결과까지 반영한 상태입니다: 승인이면 CONFIRMED, 거절이면 EXPIRED, 응답 없음이면 PAYMENT_PENDING.
 */
@RestController
public class PaymentController {

	/** 1차: 결제 수단 등록 화면 없이 결제할 때 가상 카드번호를 받음 (AUTH-07 1차 범위). 저장은 해시·끝 4자리만 */
	public record PayRequest(String cardNumber) {
	}

	private final PaymentService payments;

	public PaymentController(PaymentService payments) {
		this.payments = payments;
	}

	@PostMapping("/api/v1/reservations/{reservationId}/payments")
	public ResponseEntity<PaymentResult> start(@AuthenticationPrincipal AuthPrincipal me, @PathVariable long reservationId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = false) PayRequest body) {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("reservationId", reservationId);
		try {
			PaymentResult r = payments.start(me.memberId(), reservationId, idempotencyKey,
					body == null ? null : body.cardNumber());
			meta.put("paymentStatus", r.paymentStatus());
			BehaviorLog.mark(BehaviorType.PAY, null, meta);
			// 새로 시작했으면 201, 같은 멱등 키 재요청이면 200
			return ResponseEntity.status(r.replay() ? HttpStatus.OK : HttpStatus.CREATED).body(r);
		} catch (BusinessException e) {
			meta.put("errorCode", e.getErrorCode().code());
			BehaviorLog.mark(BehaviorType.PAY, null, meta);
			throw e;
		}
	}
}
