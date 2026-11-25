package com.jejari.admin.traffic;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import static com.jejari.admin.traffic.TrafficHealth.fmt;
import static com.jejari.admin.traffic.TrafficHealth.health;
import static com.jejari.admin.traffic.TrafficHealth.lags;
import static com.jejari.admin.traffic.TrafficHealth.stalled;
import static com.jejari.admin.traffic.TrafficHealth.worst;
import static com.jejari.admin.traffic.TrafficSeries.*;

import com.jejari.admin.traffic.PrometheusClient.Series;
import com.jejari.admin.traffic.TrafficView.Chart;
import com.jejari.admin.traffic.TrafficView.Endpoint;
import com.jejari.admin.traffic.TrafficView.Health;
import com.jejari.admin.traffic.TrafficView.Line;
import com.jejari.admin.traffic.TrafficView.Now;
import com.jejari.admin.traffic.TrafficView.Pipeline;
import com.jejari.admin.traffic.TrafficView.Reason;

/**
 * A-10 트래픽 관제. Prometheus(5초 수집)에서 고정 쿼리 몇 개를 동시에 읽어 화면 하나 분량으로 묶는다.
 *
 * <ul>
 *   <li><b>트래픽</b>: 초당 요청을 업무 영역(대기열·좌석·예약결제·로그인·관리자·기타)별로. URI 템플릿으로 나눈다</li>
 *   <li><b>응답 시간</b>: p50·p95·p99 (히스토그램 버킷, application.yml {@code percentiles-histogram})</li>
 *   <li><b>실패·거절</b>: 전체 요청 대비 5xx · 429(요청 제한) · 409(좌석 경합 등) · 기타 4xx 비율</li>
 *   <li><b>업무 처리량</b>: 예약 성공·결제 성공·409 건/초</li>
 *   <li><b>대기열</b>: 줄 선 사람·입장한 사람 ({@link QueueMetrics})</li>
 *   <li><b>자원</b>: CPU · 힙 · DB 커넥션 풀 · Tomcat 스레드 · Redis 메모리 (%). 앱이 여러 대면 가장 바쁜 한 대</li>
 * </ul>
 *
 * <p>상태(정상·주의·위험)는 {@link TrafficHealth}의 고정 문턱으로 판정하고, 시계열 셈은 {@link TrafficSeries}가 한다. 급증(평소의 3배 이상)은 장애가 아니라 "알림"이다 —
 * 명절 예매 시작에는 당연히 몰린다. 그때 함께 봐야 할 것은 응답 시간과 오류율이다.
 *
 * <p>같은 기간 요청은 {@link #CACHE_MS} 동안 결과를 재사용한다 (관리자 여러 명이 10초마다 불러도 Prometheus 부하가 늘지 않게).
 */
@Service
public class TrafficService {

	static final long CACHE_MS = 4_000;
	static final String APP = "job=\"jejari-app\"";
	/** 세는 요청: actuator(Prometheus 수집)와 이 화면 자신의 폴링은 뺀다 (흐름도 2초 폴링이 관리자 한 명당 초당 0.5건) */
	static final String HTTP = APP + ",uri!~\"/actuator.*|/api/v1/admin/traffic.*\"";
	static final String RESERVE_URI = "/api/v1/reservations";
	static final int QUEUE_CAPACITY = 50_000;
	static final String PAY_URI = "/api/v1/reservations/{reservationId}/payments";

	/** 기간 → (길이 초, 점 간격 초). 점은 120~150개 */
	static final Map<String, int[]> RANGES = Map.of(
			"30m", new int[] { 1800, 15 },
			"1h", new int[] { 3600, 30 },
			"6h", new int[] { 21600, 180 },
			"24h", new int[] { 86400, 600 });

	/** 업무 영역 (화면 범례 순서 = 색 순서. 바꾸면 화면 색도 바뀐다) */
	static final List<String> GROUPS = List.of("대기열", "좌석 조회", "예약·결제", "로그인·내 정보", "관리자", "기타");

	private final PrometheusClient prom;
	private final Clock clock;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();
	private volatile CachedFlow flowCache;

	private record CachedFlow(long at, TrafficFlow flow) {
	}

	private record Cached(long at, TrafficView view) {
	}

	public TrafficService(PrometheusClient prom, Clock clock) {
		this.prom = prom;
		this.clock = clock;
	}

	public static boolean validRange(String range) {
		return RANGES.containsKey(range);
	}

	public TrafficView view(String range) {
		long nowMs = clock.millis();
		Cached c = cache.get(range);
		if (c != null && nowMs - c.at < CACHE_MS) {
			return c.view;
		}
		TrafficView v = load(range, nowMs / 1000);
		cache.put(range, new Cached(nowMs, v));
		return v;
	}

	/** 실시간 흐름도 (최근 30초). 2초마다 불리므로 1.5초 동안 재사용 */
	public TrafficFlow flow() {
		long nowMs = clock.millis();
		CachedFlow c = flowCache;
		if (c != null && nowMs - c.at < 1_500) {
			return c.flow;
		}
		TrafficFlow f = loadFlow(nowMs / 1000);
		flowCache = new CachedFlow(nowMs, f);
		return f;
	}

	TrafficFlow loadFlow(long now) {
		String w = "[30s]";
		var count = prom.instant("sum by (uri, status) (rate(http_server_requests_seconds_count{" + HTTP + "}" + w + "))", now);
		var sum = prom.instant("sum by (uri) (rate(http_server_requests_seconds_sum{" + HTTP + "}" + w + "))", now);
		var redis = prom.instant("sum(rate(lettuce_seconds_count{" + APP + "}" + w + "))", now);
		var oracle = prom.instant("sum(rate(hikaricp_connections_usage_seconds_count{" + APP + "}" + w + "))", now);
		var mongo = prom.instant("sum(rate(mongodb_driver_commands_seconds_count{" + APP + "}" + w + "))", now);
		// Kafka 발행 = 팀 공용 프로듀서(예약 이벤트) + 행동 로그 전용 프로듀서. 행동 로그 쪽은 KafkaTemplate 관측에 안 잡혀 따로 더한다
		var kafkaOut = prom.instant("(sum(rate(spring_kafka_template_seconds_count{" + APP + "}" + w + ")) or vector(0)) + (sum(rate(jejari_behavior_sent_total{" + APP + "}" + w + ")) or vector(0))", now);
		var lagNow = prom.instant("max by (group) (jejari_kafka_consumer_lag{" + APP + "})", now);
		var sentNow = prom.instant("sum(rate(jejari_behavior_sent_total{" + APP + "}" + w + "))", now);
		var storedNow = prom.instant("sum(rate(jejari_behavior_stored_total{" + APP + "}" + w + "))", now);
		var droppedNow = prom.instant("sum(rate(jejari_behavior_dropped_total{" + APP + "}" + w + "))", now);
		var kafkaIn = prom.instant("sum(rate(spring_kafka_listener_seconds_count{" + APP + "}" + w + "))", now);
		var waiting = prom.instant("max(jejari_queue_waiting{" + APP + "})", now);
		var active = prom.instant("max(jejari_queue_active{" + APP + "})", now);
		var up = prom.instant("up{" + APP + "}", now);
		try {
			CompletableFuture.allOf(count, sum, redis, oracle, mongo, kafkaOut, kafkaIn, waiting, active, up, lagNow, droppedNow, sentNow, storedNow)
					.get(PrometheusClient.TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			return TrafficFlow.unavailable(why(e), now);
		}
		Map<String, double[]> g = new LinkedHashMap<>();     // rps, 5xx, 429, 409, 응답 시간 합(초), 기타 4xx
		GROUPS.forEach(k -> g.put(k, new double[6]));
		for (Series s : count.join()) {
			Double v = s.v()[0];
			if (v == null) {
				continue;
			}
			double[] a = g.get(group(s.label("uri")));
			String st = s.label("status");
			a[0] += v;
			a[1] += st.startsWith("5") ? v : 0;
			a[2] += "429".equals(st) ? v : 0;
			a[3] += "409".equals(st) ? v : 0;
			a[5] += st.startsWith("4") && !"409".equals(st) && !"429".equals(st) ? v : 0;
		}
		for (Series s : sum.join()) {
			if (s.v()[0] != null) {
				g.get(group(s.label("uri")))[4] += s.v()[0];
			}
		}
		List<TrafficFlow.Group> groups = new ArrayList<>();
		g.forEach((k, a) -> groups.add(new TrafficFlow.Group(k, a[0], a[1], a[2], a[3], a[5], a[0] > 0 ? a[4] / a[0] * 1000 : null)));
		List<TrafficView.Lag> lags = lags(lagNow.join());
		Double dropped = scalar(droppedNow.join());
		String kLevel = worst(lags);
		long totalLag = Math.round(lags.stream().mapToDouble(x -> x.lag() == null ? 0 : x.lag()).sum());
		String kNote = totalLag > 0 ? "밀림 " + totalLag + "건" : null;
		if (dropped != null && dropped > 0) {
			kLevel = "CRIT".equals(kLevel) ? kLevel : "WARN";
			kNote = (kNote == null ? "" : kNote + " · ") + "행동 로그 버림 " + fmt(dropped) + "/초";
		}
		List<TrafficFlow.Store> stores = List.of(
				new TrafficFlow.Store("redis", "Redis", scalar(redis.join()), null, null),
				new TrafficFlow.Store("oracle", "Oracle", scalar(oracle.join()), null, null),
				stalled(scalar(sentNow.join()), scalar(storedNow.join()))
						? new TrafficFlow.Store("mongo", "MongoDB", scalar(mongo.join()), "WARN", "행동 로그 적재 멈춤")
						: new TrafficFlow.Store("mongo", "MongoDB", scalar(mongo.join()), null, null),
				new TrafficFlow.Store("kafka", "Kafka", scalar(kafkaOut.join()), kLevel, kNote));
		int[] u = upCount(up.join());
		return new TrafficFlow(true, null, now, groups, stores, scalar(kafkaIn.join()), scalar(waiting.join()),
				scalar(active.join()), u[0], u[1]);
	}

	private String why(Exception e) {
		Throwable cause = e instanceof java.util.concurrent.ExecutionException || e instanceof CompletionException ? e.getCause() : e;
		return cause instanceof PrometheusClient.Unavailable u ? u.getMessage() : "Prometheus 응답이 늦습니다 (" + prom.base() + ")";
	}

	private static int[] upCount(List<Series> ss) {
		int up = 0;
		for (Series s : ss) {
			if (s.v()[0] != null && s.v()[0] >= 1) {
				up++;
			}
		}
		return new int[] { up, ss.size() };
	}

	static String group(String uri) {
		if (uri.startsWith("/api/v1/runs/") && uri.contains("/queue")) {
			return "대기열";
		}
		if (uri.startsWith("/api/v1/runs/") && uri.contains("/seats")) {
			return "좌석 조회";
		}
		if (uri.startsWith("/api/v1/reservations") || uri.startsWith("/api/v1/waitlists")) {
			return "예약·결제";
		}
		if (uri.startsWith("/api/v1/auth") || uri.startsWith("/api/v1/me")) {
			return "로그인·내 정보";
		}
		if (uri.startsWith("/api/v1/admin")) {
			return "관리자";
		}
		return "기타";
	}

	TrafficView load(String range, long nowSec) {
		int[] r = RANGES.get(range);
		int step = r[1];
		long end = nowSec / step * step;
		long start = end - r[0];
		int n = (int) ((end - start) / step) + 1;
		long[] t = new long[n];
		for (int i = 0; i < n; i++) {
			t[i] = start + (long) i * step;
		}
		String w = "[" + Math.max(60, step) + "s]";

		var byStatus = prom.range("sum by (method, uri, status) (rate(http_server_requests_seconds_count{" + HTTP + "}" + w + "))", start, end, step);
		var p50 = prom.range(quantile(0.50, w), start, end, step);
		var p95 = prom.range(quantile(0.95, w), start, end, step);
		var p99 = prom.range(quantile(0.99, w), start, end, step);
		var waiting = prom.range("max(jejari_queue_waiting{" + APP + "})", start, end, step);
		var active = prom.range("max(jejari_queue_active{" + APP + "})", start, end, step);
		var cpu = prom.range("max(process_cpu_usage{" + APP + "}) * 100", start, end, step);
		var heap = prom.range("max(sum by (instance) (jvm_memory_used_bytes{" + APP + ",area=\"heap\"}) / sum by (instance) (jvm_memory_max_bytes{" + APP + ",area=\"heap\"} > 0)) * 100", start, end, step);
		var db = prom.range("max(sum by (instance) (hikaricp_connections_active{" + APP + "}) / sum by (instance) (hikaricp_connections_max{" + APP + "})) * 100", start, end, step);
		var tomcat = prom.range("max(sum by (instance) (tomcat_threads_busy_threads{" + APP + "}) / sum by (instance) (tomcat_threads_config_max_threads{" + APP + "})) * 100", start, end, step);
		var redis = prom.range("max(redis_memory_used_bytes) / max(redis_memory_max_bytes > 0) * 100", start, end, step);
		var up = prom.instant("up{" + APP + "}", nowSec);
		var pending = prom.instant("sum(hikaricp_connections_pending{" + APP + "})", nowSec);
		// 평소 = 최근 24시간 초당 요청의 중앙값 (지금 10분은 뺌). 1시간 평균은 몰림이 길어지면 몰림 자체가 "평소"가 된다
		var baseline = prom.instant("quantile_over_time(0.5, sum(rate(http_server_requests_seconds_count{" + HTTP + "}[5m]))[24h:5m] offset 10m)", nowSec);
		var yesterday = prom.range("sum(rate(http_server_requests_seconds_count{" + HTTP + "}" + w + " offset 1d))", start, end, step);
		var starts = prom.range("process_start_time_seconds{" + APP + "}", start, end, step);
		// "지금" 값과 상태 판정은 최근 30초로 따로 잰다 (흐름도와 같은 창). 그래프 마지막 점은 rate 창이 1분 이상이라
		// 장애가 끝난 뒤에도 1분 가까이 "위험"이 남는다 (Redis를 40초 끊었다 살린 실측에서 회복 30초 뒤에도 5xx 43%로 보임)
		var nowStatus = prom.instant("sum by (method, uri, status) (rate(http_server_requests_seconds_count{" + HTTP + "}[30s]))", nowSec);
		var now50 = prom.instant(quantile(0.50, "[30s]"), nowSec);
		var now95 = prom.instant(quantile(0.95, "[30s]"), nowSec);
		var now99 = prom.instant(quantile(0.99, "[30s]"), nowSec);
		// 데이터 놓침·밀림 (PipelineMetrics)
		var pSent = prom.instant("sum(rate(jejari_behavior_sent_total{" + APP + "}[30s]))", nowSec);
		var pDropRate = prom.instant("sum(rate(jejari_behavior_dropped_total{" + APP + "}[30s]))", nowSec);
		var pFailRate = prom.instant("sum(rate(jejari_behavior_failed_total{" + APP + "}[30s]))", nowSec);
		var pStored = prom.instant("sum(rate(jejari_behavior_stored_total{" + APP + "}[30s]))", nowSec);
		var pSkip5 = prom.instant("sum(increase(jejari_behavior_skipped_total{" + APP + "}[5m]))", nowSec);
		var pStored5 = prom.instant("sum(increase(jejari_behavior_stored_total{" + APP + "}[5m]))", nowSec);
		var rStored = prom.range("sum(rate(jejari_behavior_stored_total{" + APP + "}" + w + "))", start, end, step);
		var rSkip = prom.range("sum(rate(jejari_behavior_skipped_total{" + APP + "}" + w + "))", start, end, step);
		var pDrop5 = prom.instant("sum(increase(jejari_behavior_dropped_total{" + APP + "}[5m]))", nowSec);
		var pFail5 = prom.instant("sum(increase(jejari_behavior_failed_total{" + APP + "}[5m]))", nowSec);
		var pQueue = prom.instant("max(jejari_behavior_queue_size{" + APP + "}) / " + QUEUE_CAPACITY + " * 100", nowSec);
		var pOutbox = prom.instant("max(jejari_outbox_pending{" + APP + "})", nowSec);
		var pOutboxFail = prom.instant("max(jejari_outbox_failed{" + APP + "})", nowSec);
		var pOutboxAge = prom.instant("max(jejari_outbox_oldest_seconds{" + APP + "})", nowSec);
		// 로그인 대기실 (member/login/LoginGate): 몰리면 줄을 서고, 가득 차면 "N초 뒤 다시"(429)로 돌려보낸다
		var lQueue = prom.instant("max(jejari_login_queue_size{" + APP + "})", nowSec);
		var lQueuePct = prom.instant("max(jejari_login_queue_size{" + APP + "} / jejari_login_queue_capacity{" + APP + "}) * 100", nowSec);
		var lBusy5 = prom.instant("sum(increase(jejari_login_busy_total{" + APP + "}[5m]))", nowSec);
		// 격리 보관함 (event/dlq/DeadLetters): 소비자가 포기·건너뛴 메시지
		var dlq5 = prom.instant("sum(increase(jejari_dead_letter_total{" + APP + "}[5m]))", nowSec);
		var dlq1h = prom.instant("sum(increase(jejari_dead_letter_total{" + APP + "}[1h]))", nowSec);
		var dlqBy = prom.instant("sum by (group, reason) (increase(jejari_dead_letter_total{" + APP + "}[1h])) > 0", nowSec);
		var pLagOk = prom.instant("min(jejari_kafka_lag_measured{" + APP + "})", nowSec);
		var pLag = prom.instant("max by (group) (jejari_kafka_consumer_lag{" + APP + "})", nowSec);
		var rSent = prom.range("sum(rate(jejari_behavior_sent_total{" + APP + "}" + w + "))", start, end, step);
		var rDrop = prom.range("sum(rate(jejari_behavior_dropped_total{" + APP + "}" + w + "))", start, end, step);
		var rFail = prom.range("sum(rate(jejari_behavior_failed_total{" + APP + "}" + w + "))", start, end, step);
		var rLag = prom.range("max by (group) (jejari_kafka_consumer_lag{" + APP + "})", start, end, step);
		var epCount = prom.instant("sum by (method, uri) (rate(http_server_requests_seconds_count{" + HTTP + "}[1m]))", nowSec);
		var epErr = prom.instant("sum by (method, uri) (rate(http_server_requests_seconds_count{" + HTTP + ",status=~\"5..\"}[1m]))", nowSec);
		var epErr4 = prom.instant("sum by (method, uri) (rate(http_server_requests_seconds_count{" + HTTP + ",status=~\"4..\",status!~\"409|429\"}[1m]))", nowSec);
		var epP95 = prom.instant("histogram_quantile(0.95, sum by (le, method, uri) (rate(http_server_requests_seconds_bucket{" + HTTP + "}[1m]))) * 1000", nowSec);
		try {
			CompletableFuture.allOf(byStatus, p50, p95, p99, waiting, active, cpu, heap, db, tomcat, redis, up, pending, baseline,
					yesterday, starts, nowStatus, now50, now95, now99, epCount, epErr, epErr4, epP95, pSent, pDropRate, pFailRate, pDrop5,
					pFail5, pQueue, pOutbox, pOutboxFail, pOutboxAge, pLag, rSent, rDrop, rFail, rLag, pStored, pSkip5, pStored5, rStored, rSkip, pLagOk, lQueue, lQueuePct, lBusy5, dlq5, dlq1h, dlqBy).get(PrometheusClient.TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			return TrafficView.unavailable(why(e), nowSec, range);
		}

		// 요청: 영역별·상태별·업무별로 한 번에 나눈다
		Map<String, Double[]> groups = new LinkedHashMap<>();
		GROUPS.forEach(g -> groups.put(g, zeros(n)));
		Double[] total = zeros(n), e5 = zeros(n), e429 = zeros(n), e409 = zeros(n), e4 = zeros(n), reserve = zeros(n), pay = zeros(n);
		boolean anyHttp = false;
		boolean[] present = new boolean[n];     // 그 시각에 앱 지표가 하나라도 있나 (없으면 앱이 꺼져 있던 것 → 0이 아니라 빈칸)
		for (Series s : byStatus.join()) {
			anyHttp = true;
			String uri = s.label("uri"), status = s.label("status"), method = s.label("method");
			Double[] g = groups.get(group(uri));
			boolean ok = status.startsWith("2");
			boolean post = "POST".equals(method);
			for (int k = 0; k < s.t().length; k++) {
				int i = idx(s.t()[k], start, step, n);
				Double v = s.v()[k];
				if (i < 0 || v == null) {
					continue;
				}
				present[i] = true;
				add(g, i, v);
				add(total, i, v);
				if (status.startsWith("5")) {
					add(e5, i, v);
				} else if ("429".equals(status)) {
					add(e429, i, v);
				} else if ("409".equals(status)) {
					add(e409, i, v);
				} else if (status.startsWith("4")) {
					add(e4, i, v);
				}
				if (ok && post && RESERVE_URI.equals(uri)) {
					add(reserve, i, v);
				}
				if (ok && post && PAY_URI.equals(uri)) {
					add(pay, i, v);
				}
			}
		}
		for (Double[] a : List.of(total, e5, e429, e409, e4, reserve, pay)) {
			blank(a, present);
		}
		groups.values().forEach(a -> blank(a, present));
		Double[] pct5 = pct(e5, total), pct429 = pct(e429, total), pct409 = pct(e409, total), pct4 = pct(e4, total);

		List<Chart> charts = new ArrayList<>();
		charts.add(new Chart("total", "전체 초당 요청 — 어제 같은 시각과 비교", "rps", List.of(new Line("지금", total),
				new Line("어제 같은 시각", one(yesterday.join(), start, step, n)))));
		List<Line> traffic = new ArrayList<>();
		groups.forEach((k, v) -> traffic.add(new Line(k, v)));
		charts.add(new Chart("traffic", "영역별 초당 요청", "rps", traffic));
		Double[] l50 = one(p50.join(), start, step, n), l95 = one(p95.join(), start, step, n), l99 = one(p99.join(), start, step, n);
		charts.add(new Chart("latency", "응답 시간", "ms", List.of(new Line("p50", l50), new Line("p95", l95), new Line("p99", l99))));
		charts.add(new Chart("errors", "실패·거절 비율 (전체 요청 대비)", "pct", List.of(new Line("5xx 서버 오류", pct5),
				new Line("429 요청 제한", pct429), new Line("409 충돌", pct409), new Line("기타 4xx", pct4))));
		charts.add(new Chart("business", "업무 처리량", "rps", List.of(new Line("예약 성공", reserve), new Line("결제 성공", pay),
				new Line("409 충돌", e409))));
		Double[] qw = one(waiting.join(), start, step, n), qa = one(active.join(), start, step, n);
		charts.add(new Chart("queue", "대기열", "people", List.of(new Line("줄 선 사람", qw), new Line("입장한 사람", qa))));
		Double[] rc = one(cpu.join(), start, step, n), rh = one(heap.join(), start, step, n), rd = one(db.join(), start, step, n),
				rt = one(tomcat.join(), start, step, n), rr = one(redis.join(), start, step, n);
		charts.add(new Chart("resources", "자원 사용률 (가장 바쁜 앱 기준)", "pct", List.of(new Line("CPU", rc), new Line("힙 메모리", rh),
				new Line("DB 커넥션", rd), new Line("Tomcat 스레드", rt), new Line("Redis 메모리", rr))));

		charts.add(new Chart("behavior", "행동 로그 (봇 탐지 재료) — 수집 · 버림 · 실패", "rps", List.of(
				new Line("Kafka가 받음", one(rSent.join(), start, step, n)), new Line("MongoDB 적재", one(rStored.join(), start, step, n)),
				new Line("버림 (큐 넘침)", one(rDrop.join(), start, step, n)), new Line("전송 실패", one(rFail.join(), start, step, n)),
				new Line("형식 오류 건너뜀", one(rSkip.join(), start, step, n)))));
		List<Line> lagLines = new ArrayList<>();
		Map<String, Series> byGroup = new HashMap<>();
		rLag.join().forEach(x -> byGroup.put(x.label("group"), x));
		PipelineMetrics.GROUPS.forEach((g, name) -> lagLines.add(new Line(name,
				byGroup.containsKey(g) ? one(List.of(byGroup.get(g)), start, step, n) : new Double[n])));
		charts.add(new Chart("lag", "Kafka 소비 밀림 (건)", "count", lagLines));
		Pipeline pipeline = new Pipeline(scalar(pSent.join()), scalar(pStored.join()), scalar(pDropRate.join()), scalar(pFailRate.join()),
				scalar(pDrop5.join()), scalar(pFail5.join()), scalar(pSkip5.join()), scalar(pStored5.join()), scalar(pQueue.join()), scalar(pOutbox.join()), scalar(pOutboxFail.join()),
				scalar(pOutboxAge.join()), lags(pLag.join()), scalar(pLagOk.join()) == null ? null : scalar(pLagOk.join()) >= 1,
				scalar(dlq5.join()), scalar(dlq1h.join()), dlqBy.join().stream()
						.map(x -> new TrafficView.DeadLetterCount(x.label("group"), x.label("reason"), x.v()[0] == null ? 0 : x.v()[0]))
						.sorted(Comparator.comparingDouble(TrafficView.DeadLetterCount::count).reversed()).toList());

		int[] u = upCount(up.join());
		int upCount = u[0], upTotal = u[1];
		double[] m = mix(nowStatus.join());     // 최근 30초: 전체, 5xx, 429, 409, 기타 4xx, 예약 성공, 결제 성공
		boolean has = !nowStatus.join().isEmpty();
		Double rps = has ? m[0] : null;
		Double base = scalar(baseline.join());
		Double surge = rps != null && base != null && base >= 0.5 ? rps / base : null;
		Double peak = null;
		for (Double v : total) {
			if (v != null && (peak == null || v > peak)) {
				peak = v;
			}
		}
		Now now = new Now(rps, base, surge, peak, scalar(now50.join()), scalar(now95.join()), scalar(now99.join()),
				has ? ratio(m[1], m[0]) : null, has ? ratio(m[2], m[0]) : null, has ? ratio(m[4], m[0]) : null, has ? m[3] : null,
				has ? m[5] : null, has ? m[6] : null, last(qw), last(qa), last(rc), last(rh), last(rd), scalar(pending.join()), last(rt), last(rr),
				scalar(lQueue.join()), scalar(lQueuePct.join()), scalar(lBusy5.join()),
				upCount, upTotal);
		return new TrafficView(true, null, nowSec, range, step, health(now, pipeline), now, t, charts,
				endpoints(epCount.join(), epErr.join(), epErr4.join(), epP95.join()), restarts(starts.join(), start, end), pipeline);
	}

	private static String quantile(double q, String w) {
		return "histogram_quantile(" + q + ", sum by (le) (rate(http_server_requests_seconds_bucket{" + HTTP + "}" + w + "))) * 1000";
	}

}
