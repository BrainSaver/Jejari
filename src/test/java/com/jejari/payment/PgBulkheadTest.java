package com.jejari.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** PG 동시 승인 상한: 자리가 차면 바로 거절, 돌려주면 다시 받음, 0이면 끔 */
class PgBulkheadTest {

	@Test
	void 꺼져_있으면_항상_통과() {
		var meters = new SimpleMeterRegistry();
		var b = new PgBulkhead(0, 0, 3, meters);
		for (int i = 0; i < 1000; i++) {
			assertThat(b.tryAcquire()).isTrue();
		}
		assertThat(meters.get("jejari_pg_bulkhead_rejected_total").counter().count()).isZero();
	}

	@Test
	void 자리가_차면_거절하고_돌려주면_다시_받는다() {
		var meters = new SimpleMeterRegistry();
		var b = new PgBulkhead(2, 0, 3, meters);
		assertThat(b.tryAcquire()).isTrue();
		assertThat(b.tryAcquire()).isTrue();
		assertThat(meters.get("jejari_pg_bulkhead_in_use").gauge().value()).isEqualTo(2.0);
		assertThat(b.tryAcquire()).isFalse();
		assertThat(meters.get("jejari_pg_bulkhead_rejected_total").counter().count()).isEqualTo(1.0);
		b.release();
		assertThat(b.tryAcquire()).isTrue();
	}

	@Test
	void 기다리는_시간_안에_자리가_나면_받는다() throws Exception {
		var b = new PgBulkhead(1, 500, 3, new SimpleMeterRegistry());
		assertThat(b.tryAcquire()).isTrue();
		Thread t = new Thread(() -> {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			b.release();
		});
		t.start();
		assertThat(b.tryAcquire()).isTrue();
		t.join();
	}
}
