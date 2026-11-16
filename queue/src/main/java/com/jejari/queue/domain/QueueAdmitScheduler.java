package com.jejari.queue.domain;

import com.jejari.queue.port.OnSaleRunProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 판매 중인 운행마다 1초에 한 번 입장 처리를 한다. 한 운행이 실패해도 다른 운행은 계속한다. */
public class QueueAdmitScheduler {
    private static final Logger log = LoggerFactory.getLogger(QueueAdmitScheduler.class);

    private final OnSaleRunProvider runs;
    private final QueueAdmitter admitter;

    public QueueAdmitScheduler(OnSaleRunProvider runs, QueueAdmitter admitter) {
        this.runs = runs;
        this.admitter = admitter;
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        for (long runId : runs.onSaleRunIds()) {
            try {
                admitter.admit(runId);
            } catch (RuntimeException e) {
                log.error("입장 처리 실패 runId={}", runId, e);
            }
        }
    }
}
