package com.jejari.train;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jejari.queue.port.OnSaleRunProvider;

/**
 * 대기열(A 김성원)이 쓰는 "판매 중 운행" 정보를 Oracle train_run에서 읽는다 (팀 확인 요청 4번).
 * 진입은 판매 중인 운행만 받고, 입장 스케줄러는 {@link #onSaleRunIds()}의 운행을 1초마다 처리한다.
 *
 * <p>개발 모드(jejari.dev-mode=true)에서는 만들지 않는다. 그때는 Oracle 없이도 도는 대용품
 * {@code DevOnSaleRunProvider}가 지정한 운행만 판매 중으로 본다. 둘이 같이 있으면 어느 쪽을 쓸지 모호해진다.
 */
@Component
@ConditionalOnProperty(name = "jejari.dev-mode", havingValue = "false", matchIfMissing = true)
public class JdbcOnSaleRunProvider implements OnSaleRunProvider {

	private final TrainRunReader runs;
	private final Clock clock;

	public JdbcOnSaleRunProvider(TrainRunReader runs, Clock clock) {
		this.runs = runs;
		this.clock = clock;
	}

	@Override
	public Optional<RunSale> find(long runId) {
		LocalDateTime now = LocalDateTime.now(clock);
		return runs.find(runId).map(r -> new RunSale(r.runId(), r.onSale(now),
				r.saleCloseAt() == null ? null : r.saleCloseAt().atZone(clock.getZone()).toInstant()));
	}

	@Override
	public List<Long> onSaleRunIds() {
		return runs.onSaleRunIds();
	}
}
