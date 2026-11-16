package com.jejari.queue.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** train_run 상태를 읽는 자리 (B 담당 열차 도메인). status = ON_SALE인 운행만 대기열을 연다. */
public interface OnSaleRunProvider {

    Optional<RunSale> find(long runId);

    List<Long> onSaleRunIds();

    /** saleCloseAt: train_run.sale_close_at, 없으면 null */
    record RunSale(long runId, boolean onSale, Instant saleCloseAt) {
    }
}
