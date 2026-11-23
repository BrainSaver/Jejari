package com.jejari.behavior;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * 행동 로그를 Kafka로 보낸다. <b>요청 스레드는 절대 기다리지 않는다.</b>
 *
 * <pre>
 * 요청 → 메모리 큐(5만) → 보내기 스레드 → Kafka
 *                │ 큐가 꽉 참        │ 전송 실패 → 5초 동안 Kafka를 부르지 않음(회로 열림)
 *                └──────────────→ 디스크 버퍼({@link SpillBuffer}) ←┘
 *                                      └ 5초마다: 회로가 닫혔고 큐가 한가하면 오래된 파일부터 다시 큐에 넣음
 * 앱 종료: 큐를 최대 8초 비우고, 남은 것·보내는 중 실패한 것 → 디스크 → 다음 기동 때 보냄
 * </pre>
 *
 * <ul>
 *   <li>행동 로그 전용 프로듀서. 팀 공용 프로듀서(acks=all·멱등, 예약 이벤트용)와 설정을 섞지 않는다:
 *       메타데이터 대기 {@code max.block.ms}=2초, 전송 제한 10초, 묶어 보내기(linger 20ms)·lz4 압축.</li>
 *   <li>중복 전송(재시도·다시 보내기)은 적재 컨슈머가 event_id 유니크 인덱스로 걸러 내므로 acks=1로 충분하다.</li>
 *   <li><b>왜 디스크인가</b> (2026-11-20 실측): Kafka가 멈춘 채로 앱을 재시작(배포)하자 응답한 요청 42,743건 중
 *       29,919건(70%)의 로그가 사라졌다. 메모리 큐는 종료 때 5초만 기다리고 버렸고, Kafka가 멈추면 메시지마다
 *       2초씩 붙잡혀 큐가 빠지지 않았다. 디스크 버퍼가 꺼져 있으면(spill-dir 빈 값) 예전처럼 큐 뒤에 다시 넣어
 *       {@value #MAX_RETRY}번까지 재시도하고 그래도 안 되면 버린다.</li>
 *   <li>버림({@code dropped})·실패({@code failed})는 이제 <b>디스크에도 못 적은 진짜 유실</b>만 센다 (경보 그대로).</li>
 *   <li>남는 유실: 앱이 강제 종료(kill -9·정전)되면 그 순간 메모리 큐에 있던 것. 디스크에 적힌 것은 남는다.</li>
 * </ul>
 */
@Component
@ConditionalOnExpression("${jejari.behavior.enabled:true} and !'${jejari.hmac.key:}'.isBlank()")
public class BehaviorPublisher implements DisposableBean {
	private static final Logger log = LoggerFactory.getLogger(BehaviorPublisher.class);

	static final int QUEUE_CAPACITY = 50_000;
	static final int MAX_RETRY = 3;
	/** 전송이 실패하면 이 시간 동안 Kafka를 부르지 않고 디스크에 적는다 (멈춘 Kafka에 메시지마다 2초씩 붙잡히지 않게) */
	static final long CIRCUIT_OPEN_MS = 5_000;
	/** 종료 때 큐를 비우며 기다리는 최대 시간. 서버 TimeoutStopSec(40초)·단계별 종료 대기(10초)와 합쳐도 넘지 않게 */
	static final long DRAIN_MS = 8_000;
	private static final long WARN_INTERVAL_MS = 10_000;

	/** 큐에 넣는 일 하나. 종료 때 남은 일에서 로그를 꺼내 디스크에 적으려고 람다 대신 레코드 */
	private record Send(BehaviorPublisher p, BehaviorEvent event, int attempt) implements Runnable {
		@Override
		public void run() {
			p.send(event, attempt);
		}
	}

	private final KafkaTemplate<String, String> kafka;
	private final DefaultKafkaProducerFactory<String, String> producerFactory;
	private final JsonMapper json;
	private final SpillBuffer spill;
	private final ThreadPoolExecutor sender;
	private final ScheduledExecutorService replayer;
	private final AtomicLong dropped = new AtomicLong();
	private final AtomicLong failed = new AtomicLong();
	private final AtomicLong sent = new AtomicLong();
	private final AtomicLong retried = new AtomicLong();
	private final AtomicLong spilled = new AtomicLong();
	private final AtomicLong replayed = new AtomicLong();
	private final AtomicLong lastWarnAt = new AtomicLong();
	private volatile long circuitOpenUntil;
	private volatile boolean closing;

	/** 디스크 버퍼 없이 (단위 시험) */
	public BehaviorPublisher(String bootstrap, JsonMapper json) {
		this(bootstrap, json, "", 0);
	}

	@Autowired
	public BehaviorPublisher(@Value("${spring.kafka.bootstrap-servers}") String bootstrap, JsonMapper json,
			@Value("${jejari.behavior.spill-dir:${user.home}/.jejari/behavior-spill/${server.port:8080}}") String spillDir,
			@Value("${jejari.behavior.spill-max-mb:512}") long spillMaxMb) {
		Map<String, Object> props = new HashMap<>();
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		props.put(ProducerConfig.ACKS_CONFIG, "1");
		props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
		props.put(ProducerConfig.LINGER_MS_CONFIG, 20);
		props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
		props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
		props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
		props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
		props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 16L * 1024 * 1024);
		this.producerFactory = new DefaultKafkaProducerFactory<>(props);
		// 종료 때 못 보낸 묶음은 2초 뒤 포기 → 콜백이 실패로 불려 디스크에 적힌다 (기본 30초면 서버의 강제 종료에 걸림)
		this.producerFactory.setPhysicalCloseTimeout(2);
		this.kafka = new KafkaTemplate<>(producerFactory);
		this.json = json;
		this.spill = new SpillBuffer(spillDir == null || spillDir.isBlank() ? null : Path.of(spillDir),
				spillMaxMb * 1024 * 1024);
		this.sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
				r -> daemon(r, "behavior-log-send"),
				(r, ex) -> overflow((Send) r));
		this.replayer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "behavior-log-replay"));
		if (spill.enabled()) {
			if (spill.pendingBytes() > 0) {
				log.info("행동 로그 디스크 버퍼에 지난번 남은 {}KB → Kafka가 받으면 다시 보냄", spill.pendingBytes() / 1024);
			}
			replayer.scheduleWithFixedDelay(this::replay, 3, 5, TimeUnit.SECONDS);
		}
	}

	private static Thread daemon(Runnable r, String name) {
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		return t;
	}

	/** 바로 돌아온다 */
	public void publish(BehaviorEvent e) {
		enqueue(e, 0);
	}

	private void enqueue(BehaviorEvent e, int attempt) {
		try {
			sender.execute(new Send(this, e, attempt));
		} catch (RejectedExecutionException ex) {
			// 거절 처리기(overflow)가 이미 처리함. 여기 오는 것은 처리기 없이 거절된 경우뿐
		}
	}

	/** 큐가 꽉 찼거나 종료 중이라 못 넣음 → 디스크 (요청 스레드에서 돌지만 한 줄 쓰기라 마이크로초) */
	private void overflow(Send s) {
		if (!toDisk(s.event())) {
			warn(dropped, closing ? "행동 로그를 버림 (종료 중, 디스크 버퍼 없음·가득)" : "행동 로그 보내기 큐가 가득 차 버림 (디스크 버퍼 없음·가득)");
		}
	}

	private void send(BehaviorEvent e, int attempt) {
		if (spill.enabled() && System.currentTimeMillis() < circuitOpenUntil) {
			spillOrFail(e, null);
			return;
		}
		try {
			kafka.send(BehaviorEvent.TOPIC, e.key(), json.writeValueAsString(e)).whenComplete((r, ex) -> {
				if (ex != null) {
					onFail(e, attempt, ex);
				} else {
					sent.incrementAndGet();
				}
			});
		} catch (RuntimeException ex) {
			// 메타데이터를 못 받거나(Kafka 다운, 2초) 버퍼가 가득
			onFail(e, attempt, ex);
		}
	}

	private void onFail(BehaviorEvent e, int attempt, Throwable ex) {
		if (spill.enabled()) {
			circuitOpenUntil = System.currentTimeMillis() + CIRCUIT_OPEN_MS;
			spillOrFail(e, ex);
			return;
		}
		// 디스크 버퍼가 꺼져 있으면: 큐 뒤에 다시 넣는다 (앞의 로그들이 빠지는 동안 자연히 시간이 흐름).
		// 횟수를 넘었거나 큐가 많이 찼으면 실패로 센다
		if (!closing && attempt < MAX_RETRY && sender.getQueue().remainingCapacity() > QUEUE_CAPACITY / 5) {
			try {
				sender.execute(new Send(this, e, attempt + 1));
				retried.incrementAndGet();
				return;
			} catch (RejectedExecutionException rejected) {
				// 종료 중이거나 그 사이 가득 참 → 실패로
			}
		}
		warn(failed, "행동 로그 Kafka 전송 실패: " + ex.getClass().getSimpleName());
	}

	private void spillOrFail(BehaviorEvent e, Throwable ex) {
		if (!toDisk(e)) {
			warn(failed, "행동 로그 Kafka 전송 실패, 디스크 버퍼에도 못 적음"
					+ (ex == null ? "" : ": " + ex.getClass().getSimpleName()));
		}
	}

	private boolean toDisk(BehaviorEvent e) {
		if (!spill.enabled()) {
			return false;
		}
		try {
			if (spill.append(json.writeValueAsString(e))) {
				spilled.incrementAndGet();
				return true;
			}
		} catch (RuntimeException ex) {
			log.error("행동 로그를 디스크용으로 바꾸지 못함: {}", ex.toString());
		}
		return false;
	}

	/**
	 * 디스크에 적어 둔 로그를 다시 큐에 넣는다 (5초마다). 회로가 열려 있거나(Kafka가 아직 안 받음) 큐가 바쁘면 다음에.
	 * 파일 하나를 다 넣으면 지운다. 넣는 도중 멈추면 그 파일은 남아 다음에 처음부터 다시 — 중복은 적재 쪽이 거른다.
	 */
	void replay() {
		try {
			for (Path f : spill.ready()) {
				if (!canReplay()) {
					return;
				}
				int n = 0;
				try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
					String line;
					while ((line = r.readLine()) != null) {
						while (sender.getQueue().size() > QUEUE_CAPACITY / 2) {
							if (!canReplay()) {
								return;
							}
							Thread.sleep(50);
						}
						if (!canReplay()) {
							return;
						}
						if (line.isBlank()) {
							continue;
						}
						BehaviorEvent e;
						try {
							e = json.readValue(line, BehaviorEvent.class);
						} catch (RuntimeException bad) {
							warn(dropped, "디스크 버퍼의 행동 로그 한 줄을 읽지 못해 버림 (파일 " + f.getFileName() + ")");
							continue;
						}
						enqueue(e, 0);
						replayed.incrementAndGet();
						n++;
					}
				}
				spill.delete(f);
				log.info("행동 로그 디스크 버퍼 {}건을 다시 보냄 ({})", n, f.getFileName());
			}
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		} catch (IOException | RuntimeException ex) {
			log.warn("행동 로그 디스크 버퍼 다시 보내기 실패 (다음에 다시): {}", ex.toString());
		}
	}

	private boolean canReplay() {
		return !closing && System.currentTimeMillis() >= circuitOpenUntil
				&& sender.getQueue().size() <= QUEUE_CAPACITY / 2;
	}

	private void warn(AtomicLong counter, String msg) {
		counter.incrementAndGet();
		long now = System.currentTimeMillis();
		long last = lastWarnAt.get();
		if (now - last >= WARN_INTERVAL_MS && lastWarnAt.compareAndSet(last, now)) {
			log.warn("{} (누적 버림 {}건, 실패 {}건)", msg, dropped.get(), failed.get());
		}
	}

	/** 실패 뒤 다시 보낸 횟수 (디스크 버퍼가 꺼져 있을 때만, A-10 지표) */
	public long retriedCount() {
		return retried.get();
	}

	/** 큐가 넘쳐 버린 건수 (디스크에도 못 적은 것) */
	public long droppedCount() {
		return dropped.get();
	}

	/** Kafka 전송에 실패하고 디스크에도 못 적은 건수 */
	public long failedCount() {
		return failed.get();
	}

	/** Kafka가 받았다고 답한 건수 (A-10 트래픽 관제 지표, admin/traffic/PipelineMetrics) */
	public long sentCount() {
		return sent.get();
	}

	/** 디스크 버퍼에 적은 건수 (유실 아님: 나중에 다시 보냄) */
	public long spilledCount() {
		return spilled.get();
	}

	/** 디스크 버퍼에서 다시 큐에 넣은 건수 */
	public long replayedCount() {
		return replayed.get();
	}

	/** 디스크 버퍼에 남아 있는 바이트 */
	public long spillPendingBytes() {
		return spill.pendingBytes();
	}

	/** 보내기 큐에 쌓여 있는 건수 (최대 {@value #QUEUE_CAPACITY}) */
	public int queueSize() {
		return sender.getQueue().size();
	}

	/**
	 * 종료: 새로 들어오는 로그는 거절 처리기가 디스크로 → 큐를 최대 {@value #DRAIN_MS}ms 비움 (Kafka가 안 받는 중이면 기다리지 않음)
	 * → 남은 일은 디스크로 → 프로듀서를 2초 안에 닫음 (못 보낸 묶음은 실패 콜백으로 디스크로) → 파일 닫기.
	 * 최악의 경우 합계: 단계별 종료 대기 10초 × 2(Kafka 소비자·웹) + 8 + 3 + 2 = 33초 &lt; 서버 TimeoutStopSec 40초.
	 */
	@Override
	public void destroy() throws InterruptedException {
		closing = true;
		replayer.shutdownNow();
		sender.shutdown();
		boolean kafkaDown = System.currentTimeMillis() < circuitOpenUntil;
		boolean drained = sender.awaitTermination(kafkaDown ? 0 : DRAIN_MS, TimeUnit.MILLISECONDS);
		int left = 0;
		if (!drained) {
			List<Runnable> rest = sender.shutdownNow();
			for (Runnable r : rest) {
				if (r instanceof Send s) {
					if (toDisk(s.event())) {
						left++;
					} else {
						dropped.incrementAndGet();
					}
				}
			}
			sender.awaitTermination(3, TimeUnit.SECONDS);
		}
		// flush() 대신 닫기: 보내는 중인 묶음을 2초까지 마저 보내고, 못 보낸 것은 실패 콜백 → 디스크.
		// flush()는 Kafka가 멈춰 있으면 전송 제한(10초)까지 붙잡아 서버의 강제 종료(40초)에 걸릴 수 있다
		producerFactory.destroy();
		spill.close();
		if (left > 0 || spilled.get() > 0) {
			log.warn("행동 로그: 종료 때 큐에 남은 {}건을 디스크에 적음 (지금까지 디스크로 {}건, 버림 {}건, 실패 {}건). 다음 기동 때 다시 보냄",
					left, spilled.get(), dropped.get(), failed.get());
		}
	}
}
