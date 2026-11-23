package com.jejari.event.dlq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DeadLettersTest {

	@SuppressWarnings("unchecked")
	final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
	final SimpleMeterRegistry meters = new SimpleMeterRegistry();
	final DeadLetters dl = new DeadLetters(kafka, meters, Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneId.of("UTC")));

	static String h(ProducerRecord<String, String> r, String k) {
		return new String(r.headers().lastHeader(k).value(), StandardCharsets.UTF_8);
	}

	@Test
	@SuppressWarnings("unchecked")
	void 원본_키_값을_그대로_DLT로_옮기고_이유와_출처를_헤더에() {
		when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
		var rec = new ConsumerRecord<>("jejari.reservation", 4, 524L, "r1", "{broken");
		dl.park("notification", rec, DeadLetters.RETRIES_EXHAUSTED, new IllegalStateException("db down"));

		ArgumentCaptor<ProducerRecord<String, String>> out = ArgumentCaptor.forClass(ProducerRecord.class);
		verify(kafka).send(out.capture());
		var r = out.getValue();
		assertThat(r.topic()).isEqualTo("jejari.reservation.DLT");
		assertThat(r.key()).isEqualTo("r1");
		assertThat(r.value()).isEqualTo("{broken");
		assertThat(h(r, DeadLetters.H_GROUP)).isEqualTo("notification");
		assertThat(h(r, DeadLetters.H_REASON)).isEqualTo("retries_exhausted");
		assertThat(h(r, DeadLetters.H_SOURCE)).isEqualTo("jejari.reservation-4@524");
		assertThat(h(r, DeadLetters.H_ERROR)).contains("db down");
		assertThat(meters.counter("jejari.dead.letter", "group", "notification", "reason", "retries_exhausted").count()).isEqualTo(1);
	}

	@Test
	@SuppressWarnings("unchecked")
	void 보관함에도_못_넣으면_던져서_오프셋을_커밋하지_않게() {
		when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka down")));
		var rec = new ConsumerRecord<>("jejari.behavior", 0, 1L, "k", "v");
		assertThatThrownBy(() -> dl.park("behavior-sink", rec, DeadLetters.MALFORMED, null)).isInstanceOf(IllegalStateException.class);
		// 옮기지 못했으면 세지 않음 (미리 만든 0 그대로)
		assertThat(meters.counter("jejari.dead.letter", "group", "behavior-sink", "reason", "malformed").count()).isZero();
	}

	@Test
	void 지표를_0으로_미리_만들어_첫_1건도_경보에_잡힌다() {
		// Prometheus increase()는 1에서 시작한 시계열의 첫 1건을 못 본다 → 앱이 뜰 때 0으로
		assertThat(meters.find("jejari.dead.letter").counters()).hasSize(7);
		assertThat(meters.counter("jejari.dead.letter", "group", "notification", "reason", "retries_exhausted").count()).isZero();
	}

	@Test
	void 다시_넣은_메시지는_그_그룹만_처리() {
		var rec = new ConsumerRecord<>("jejari.reservation", 0, 1L, "k", "v");
		assertThat(DeadLetters.notForMe(rec, "seat-release")).isFalse();   // 보통 메시지는 모두가 처리
		rec.headers().add(DeadLetters.H_REPLAY_FOR, "notification".getBytes(StandardCharsets.UTF_8));
		assertThat(DeadLetters.notForMe(rec, "notification")).isFalse();
		assertThat(DeadLetters.notForMe(rec, "seat-release")).isTrue();
		assertThat(DeadLetters.notForMe(rec, "risk-signal")).isTrue();
	}
}
