package com.jejari.event.dlq;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 격리 보관함 (Dead Letter Queue). 소비자가 <b>포기하거나 건너뛰는</b> 메시지를 버리지 않고 {@code <원래 토픽>.DLT}에 그대로 옮겨 둔다.
 *
 * <p>왜: 소비자는 메시지 하나 때문에 뒤의 메시지가 모두 멈추지 않게 깨진 메시지·처리에 계속 실패한 메시지를 건너뛴다. 그런데 예전에는
 * 건너뛴 것이 로그 한 줄로만 남아, 무엇을 놓쳤는지 알 수도 다시 처리할 수도 없었다 (Kafka 보관 7일이 지나면 원본도 사라짐).
 * 지금은
 * <ul>
 *   <li>원본 키·값 그대로 + 헤더 {@code dlt-group}(누가 포기했나)·{@code dlt-reason}({@code malformed} 형식 오류 /
 *       {@code retries_exhausted} 다시 해도 실패 / {@code handler_failed} 처리 실패)·{@code dlt-error}·{@code dlt-source}(토픽-파티션@오프셋)·{@code dlt-at}</li>
 *   <li>DLT 토픽은 30일 보관 ({@link DeadLetterConfig}). 원인을 고친 뒤 {@code tools/dlq.sh replay}로 원래 토픽에 다시 넣으면
 *       <b>그 소비 그룹만</b> 다시 처리한다 (헤더 {@code dlt-replay-for}, 다른 그룹은 {@link #notForMe}로 건너뜀).
 *       소비자는 멱등(inbox·event_id 유니크)이라 두 번 처리돼도 결과가 같다</li>
 *   <li>지표 {@code jejari_dead_letter_total{group, reason}} → A-10 "데이터 놓침·밀림"</li>
 * </ul>
 * 옮기기에 실패하면 예외를 던진다 → 오프셋을 커밋하지 않아 원래 소비자가 다시 받는다 (보관함마저 안 되면 버리지 않고 멈춤).
 */
@Component
public class DeadLetters {

	private static final Logger log = LoggerFactory.getLogger(DeadLetters.class);
	public static final String SUFFIX = ".DLT";
	public static final String H_GROUP = "dlt-group";
	public static final String H_REASON = "dlt-reason";
	public static final String H_ERROR = "dlt-error";
	public static final String H_SOURCE = "dlt-source";
	public static final String H_AT = "dlt-at";
	/** tools/dlq.sh replay가 붙이는 헤더: 이 그룹만 처리하라 */
	public static final String H_REPLAY_FOR = "dlt-replay-for";

	public static final String MALFORMED = "malformed";
	public static final String RETRIES_EXHAUSTED = "retries_exhausted";
	public static final String HANDLER_FAILED = "handler_failed";

	private final KafkaTemplate<String, String> kafka;
	private final MeterRegistry meters;
	private final Clock clock;

	public DeadLetters(KafkaTemplate<String, String> kafka, MeterRegistry meters, Clock clock) {
		this.kafka = kafka;
		this.meters = meters;
		this.clock = clock;
	}

	/** 형식이 깨진 메시지 (다시 해도 안 됨). 소비자의 parse가 던지면 consume이 잡아 {@link #park}로 */
	public static class Malformed extends RuntimeException {
		public Malformed(String message) {
			super(message, null, false, false);
		}
	}

	/** 메시지를 보관함으로 옮긴다. 성공해야 돌아온다 (5초) */
	public void park(String group, ConsumerRecord<?, ?> rec, String reason, Throwable cause) {
		String value = rec.value() == null ? null : rec.value().toString();
		String key = rec.key() == null ? null : rec.key().toString();
		ProducerRecord<String, String> out = new ProducerRecord<>(rec.topic().endsWith(SUFFIX) ? rec.topic() : rec.topic() + SUFFIX,
				key, value);
		out.headers().add(H_GROUP, bytes(group));
		out.headers().add(H_REASON, bytes(reason));
		out.headers().add(H_SOURCE, bytes(rec.topic() + "-" + rec.partition() + "@" + rec.offset()));
		out.headers().add(H_AT, bytes(clock.instant().toString()));
		if (cause != null) {
			String msg = cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
			out.headers().add(H_ERROR, bytes(msg.length() > 500 ? msg.substring(0, 500) : msg));
		}
		try {
			kafka.send(out).get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			// 보관함에도 못 넣으면 버리지 않는다: 던져서 오프셋을 커밋하지 않게 (원래 소비자의 재시도로)
			throw new IllegalStateException("격리 보관함(" + out.topic() + ")에 넣지 못함", e);
		}
		Counter.builder("jejari.dead.letter").tag("group", group).tag("reason", reason)
				.description("소비자가 포기·건너뛰어 격리 보관함(DLT)에 옮긴 메시지").register(meters).increment();
		log.warn("격리 보관: {} {} ({}-{}@{}){}", group, reason, rec.topic(), rec.partition(), rec.offset(),
				cause == null ? "" : " — " + cause.getClass().getSimpleName());
	}

	/** 다시 넣기(replay)로 들어온 메시지가 다른 그룹 몫이면 true → 건너뛴다 */
	public static boolean notForMe(ConsumerRecord<?, ?> rec, String group) {
		Header h = rec.headers() == null ? null : rec.headers().lastHeader(H_REPLAY_FOR);
		return h != null && !group.equals(new String(h.value(), StandardCharsets.UTF_8));
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}
}
