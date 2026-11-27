package com.jejari.payment;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * PG 동시 승인 요청 상한 (벌크헤드). PG가 느려지면 결제 요청이 응답을 기다리며 Tomcat 요청 스레드(기본 200)를 하나씩 붙잡는다.
 * 상한이 없으면 결제가 몰릴 때 스레드가 전부 결제에 묶여 <b>결제와 상관없는 조회·대기열·선점까지 같이 멈춘다</b>.
 * 상한을 두면 넘친 결제는 바로 "잠시 후 다시"(503 PAYMENT_BUSY)를 받고, 나머지 스레드는 다른 요청을 계속 처리한다.
 *
 * <p>자리는 <b>결제 행을 만들기 전에</b> 잡는다 ({@link PaymentService#start}). 그래서 자리가 없어 거절된 결제는 DB에 아무것도
 * 남기지 않고 예약은 HOLD 그대로다 — 같은 멱등 키로 다시 보내면 처음처럼 처리된다. PG 호출이 끝나면(승인·거절·시간 초과) 자리를 돌려준다.
 *
 * <p>거절은 503 {@code PAYMENT_BUSY} + {@code Retry-After}(기본 3초)이고 예상된 거절이라 ERROR 로그를 남기지 않는다
 * (몰릴 때 스택트레이스 수천 개가 그 자체로 부하).
 *
 * <p>{@code jejari.payment.pg.max-concurrent} 0 이하면 끔 (기본). Redis 장애 때 대기열·선점을 빠르게 거절하는 것과 같은 생각이다:
 * 기다리게 하는 것보다 빨리 "다시"라고 답하는 쪽이 전체를 살린다.
 *
 * <p>지표: {@code jejari_pg_bulkhead_rejected_total}(자리가 없어 거절), {@code jejari_pg_bulkhead_in_use}(지금 쓰는 자리).
 */
public class PgBulkhead {

	private final Semaphore slots;
	private final int limit;
	private final long waitMs;
	private final long retryAfterSec;
	private final Counter rejected;

	/**
	 * @param retryAfterSec 거절할 때 {@code Retry-After}로 알려 줄 초. 거절된 사람이 바로 다시 누르면 그 재시도가 또 부하가 된다
	 *                      (2026-11-27 로컬 측정: 상한 40, 구매자 300명에 503이 1,451번)
	 */
	public PgBulkhead(int maxConcurrent, long waitMs, long retryAfterSec, MeterRegistry meters) {
		this.limit = Math.max(0, maxConcurrent);
		this.slots = limit > 0 ? new Semaphore(limit, true) : null;
		this.waitMs = Math.max(0, waitMs);
		this.retryAfterSec = Math.max(1, retryAfterSec);
		this.rejected = Counter.builder("jejari_pg_bulkhead_rejected_total")
				.description("PG 동시 승인 상한에 걸려 바로 거절한 결제").register(meters);
		Gauge.builder("jejari_pg_bulkhead_in_use", this, b -> b.slots == null ? 0 : b.limit - b.slots.availablePermits())
				.description("PG 동시 승인 자리 중 지금 쓰는 수").register(meters);
	}

	/** 끔이면 항상 true. 켜져 있으면 waitMs까지 기다려 자리를 잡고, 못 잡으면 false */
	public boolean tryAcquire() {
		if (slots == null) {
			return true;
		}
		try {
			if (slots.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) {
				return true;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		rejected.increment();
		return false;
	}

	/** {@link #tryAcquire()}가 true였을 때만 부른다 */
	public void release() {
		if (slots != null) {
			slots.release();
		}
	}

	public int limit() {
		return limit;
	}

	public long retryAfterSec() {
		return retryAfterSec;
	}
}
