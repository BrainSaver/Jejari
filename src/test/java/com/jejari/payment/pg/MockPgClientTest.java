package com.jejari.payment.pg;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.jejari.payment.pg.PgClient.Outcome;
import com.jejari.payment.pg.PgClient.Status;

class MockPgClientTest {

	final MockPgClient pg = new MockPgClient();

	@Test
	void 끝자리_규칙() {
		assertThat(pg.approve(1, 1000, "4111111111111111").outcome()).isEqualTo(Outcome.APPROVED);
		assertThat(pg.approve(2, 1000, "4111111111111110").outcome()).isEqualTo(Outcome.DECLINED);
		assertThat(pg.approve(3, 1000, "4111111111111119").outcome()).isEqualTo(Outcome.UNKNOWN);
		assertThat(pg.approve(4, 1000, "4111111111111118").outcome()).isEqualTo(Outcome.UNKNOWN);

		assertThat(pg.inquire(1).status()).isEqualTo(Status.APPROVED);
		assertThat(pg.inquire(2).status()).isEqualTo(Status.FAILED);
		assertThat(pg.inquire(3).status()).isEqualTo(Status.UNKNOWN); // 9: 계속 모름
		assertThat(pg.inquire(4).status()).isEqualTo(Status.APPROVED); // 8: 늦게 승인
		assertThat(pg.inquire(5).status()).isEqualTo(Status.NOT_FOUND);
	}

	@Test
	void 같은_주문을_다시_보내도_한_번만_처리하고_같은_승인번호() {
		var a = pg.approve(7, 1000, "4111111111111111");
		var b = pg.approve(7, 1000, "4111111111111111");
		assertThat(b.pgTxId()).isEqualTo(a.pgTxId());
		assertThat(pg.processedCount()).isEqualTo(1);
	}

	@Test
	void 받은_적_없는_주문을_막으면_늦게_온_승인도_거절() {
		assertThat(pg.blockOrder(9)).isTrue();
		assertThat(pg.approve(9, 1000, "4111111111111111").outcome()).isEqualTo(Outcome.DECLINED);
		assertThat(pg.inquire(9).status()).isEqualTo(Status.FAILED);
	}

	@Test
	void 이미_승인된_주문은_막지_못한다() {
		pg.approve(10, 1000, "4111111111111111");
		assertThat(pg.blockOrder(10)).isFalse();
	}
}
