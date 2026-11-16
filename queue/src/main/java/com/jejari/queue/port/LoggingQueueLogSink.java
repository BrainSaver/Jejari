package com.jejari.queue.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 기본 구현: 로그에만 남긴다. Kafka 구현이 붙으면 대체된다. */
public class LoggingQueueLogSink implements QueueLogSink {
    private static final Logger log = LoggerFactory.getLogger("queue-log");

    @Override
    public void record(QueueLogEvent e) {
        log.info("{} member={} run={} ticket={} risk={} penaltyMs={} rank={}->{} left={}",
                e.type(), e.memberId(), e.runId(), e.ticketNo(), e.riskScore(), e.penaltyMs(),
                e.initialRank(), e.adjustedRank(), e.leftReason());
    }
}
