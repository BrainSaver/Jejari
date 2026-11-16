package com.jejari.queue;

import com.jejari.queue.port.DevMemberIdentityPort;
import com.jejari.queue.port.DevOnSaleRunProvider;
import com.jejari.queue.port.QueueLogSink;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 테스트용 대용품: 시각을 직접 움직이는 시계, 로그 수집기 */
@TestConfiguration
public class TestSupport {

    public static class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-20T01:00:00Z"); // 10:00 KST

        public void advanceMs(long ms) { now = now.plusMillis(ms); }

        @Override public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    public static class RecordingSink implements QueueLogSink {
        public final List<QueueLogEvent> events = new CopyOnWriteArrayList<>();
        @Override public void record(QueueLogEvent e) { events.add(e); }
    }

    @Bean @Primary
    MutableClock testClock() { return new MutableClock(); }

    @Bean @Primary
    RecordingSink recordingSink() { return new RecordingSink(); }
}
