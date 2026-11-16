package com.jejari.queue.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 개발·테스트용. 등록한 운행만 판매 중으로 본다. */
public class DevOnSaleRunProvider implements OnSaleRunProvider {
    private final Set<Long> onSale = ConcurrentHashMap.newKeySet();

    public void open(long runId) { onSale.add(runId); }

    public void close(long runId) { onSale.remove(runId); }

    public void clear() { onSale.clear(); }

    @Override
    public Optional<RunSale> find(long runId) {
        return Optional.of(new RunSale(runId, onSale.contains(runId), (Instant) null));
    }

    @Override
    public List<Long> onSaleRunIds() {
        return List.copyOf(onSale);
    }
}
