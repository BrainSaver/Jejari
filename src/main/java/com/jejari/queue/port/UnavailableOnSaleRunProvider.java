package com.jejari.queue.port;

import java.util.List;
import java.util.Optional;

/**
 * 운행 정보 연동(train_run 조회)이 아직 없을 때의 기본값. 어떤 운행도 판매 중이 아닌 것으로 봅니다
 * (진입은 RUN_NOT_ON_SALE, 입장 스케줄러는 할 일 없음). 실제 구현(OnSaleRunProvider 빈)이 생기면 자동으로 그쪽을 씁니다.
 */
public class UnavailableOnSaleRunProvider implements OnSaleRunProvider {

	@Override
	public Optional<RunSale> find(long runId) {
		return Optional.empty();
	}

	@Override
	public List<Long> onSaleRunIds() {
		return List.of();
	}
}
