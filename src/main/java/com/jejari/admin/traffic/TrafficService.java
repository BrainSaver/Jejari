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
 * <p>상태(정상·주의·위험)는 {@link #health}의 고정 문턱으로 판정한다. 급증(평소의 3배 이상)은 장애가 아니라 "알림"이다 —
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
					pFail5, pQueue, pOutbox, pOutboxFail, pOutboxAge, pLag, rSent, rDrop, rFail, rLag, pStored, pSkip5, pStored5, rStored, rSkip, pLagOk).get(PrometheusClient.TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
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
				scalar(pOutboxAge.join()), lags(pLag.join()), scalar(pLagOk.join()) == null ? null : scalar(pLagOk.join()) >= 1);

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
				upCount, upTotal);
		return new TrafficView(true, null, nowSec, range, step, health(now, pipeline), now, t, charts,
				endpoints(epCount.join(), epErr.join(), epErr4.join(), epP95.join()), restarts(starts.join(), start, end), pipeline);
	}

	private static String quantile(double q, String w) {
		return "histogram_quantile(" + q + ", sum by (le) (rate(http_server_requests_seconds_bucket{" + HTTP + "}" + w + "))) * 1000";
	}

	/**
	 * 고정 문턱. 큰 회사 관제의 흔한 기준을 이 서비스 크기에 맞춘 값이다 (사용자 응답 1초·오류 1%가 "주의"의 시작).
	 * 바꾸면 docs/ui/A-10.md 표도 같이.
	 */
	static Health health(Now n) {
		return health(n, null);
	}

	static Health health(Now n, Pipeline p) {
		List<Reason> rs = new ArrayList<>();
		if (n.instancesTotal() > 0 && n.instancesUp() < n.instancesTotal()) {
			rs.add(new Reason(n.instancesUp() == 0 ? "CRIT" : "WARN", "up",
					"앱 " + n.instancesTotal() + "대 중 " + (n.instancesTotal() - n.instancesUp()) + "대가 응답하지 않음"));
		} else if (n.instancesTotal() == 0) {
			rs.add(new Reason("CRIT", "up", "앱 지표가 수집되지 않음 (앱이 꺼졌거나 Prometheus 설정 확인)"));
		}
		level(rs, "error", n.errorPct(), 1, 5, v -> "5xx 서버 오류 " + fmt(v) + "%");
		// 4xx는 보통 이용자 탓이라 위험까지 올리지 않는다. 하지만 4건 중 1건 이상이면 로그인 고장·화면 배포 실수·봇 두드림일 때가 많다
		level(rs, "client", n.clientErrorPct(), 25, Double.MAX_VALUE, v -> "4xx 클라이언트 오류 " + fmt(v) + "% (409·429 제외 — 로그인·화면 배포·잘못된 호출 확인)");
		level(rs, "p95", n.p95Ms(), 1000, 3000, v -> "응답 시간 p95 " + Math.round(v) + "ms");
		level(rs, "db", n.dbPoolPct(), 80, 95, v -> "DB 커넥션 풀 " + Math.round(v) + "% 사용");
		if (n.dbPending() != null && n.dbPending() >= 1) {
			rs.add(new Reason("WARN", "dbPending", "DB 커넥션을 기다리는 요청 " + Math.round(n.dbPending()) + "개"));
		}
		level(rs, "tomcat", n.tomcatPct(), 80, 95, v -> "Tomcat 스레드 " + Math.round(v) + "% 사용");
		level(rs, "cpu", n.cpuPct(), 80, 95, v -> "CPU " + Math.round(v) + "%");
		level(rs, "heap", n.heapPct(), 85, 95, v -> "힙 메모리 " + Math.round(v) + "%");
		level(rs, "redis", n.redisMemPct(), 80, 90, v -> "Redis 메모리 " + Math.round(v) + "% (noeviction이라 다 차면 쓰기 실패)");
		if (p != null) {
			pipelineReasons(rs, p);
		}
		if (n.surge() != null && n.surge() >= 3 && n.rps() != null && n.rps() >= 5) {
			rs.add(new Reason("INFO", "surge", "트래픽 급증 — 평소의 " + fmt(n.surge()) + "배"));
		}
		if (n.rejectPct() != null && n.rejectPct() >= 5) {
			rs.add(new Reason("INFO", "reject", "요청 제한(429) 작동 중 — 전체의 " + fmt(n.rejectPct()) + "%"));
		}
		rs.sort(Comparator.comparingInt(x -> List.of("CRIT", "WARN", "INFO").indexOf(x.level())));
		String lv = rs.stream().anyMatch(x -> "CRIT".equals(x.level())) ? "CRIT"
				: rs.stream().anyMatch(x -> "WARN".equals(x.level())) ? "WARN" : "OK";
		return new Health(lv, rs);
	}

	/**
	 * 데이터 놓침·밀림 문턱. 버림·실패는 1건이라도 "주의" — 봇 탐지 재료가 빠졌다는 뜻이라. 소비 밀림은 그룹 성격별로 다르다:
	 * 예약 흐름(좌석 정리·알림·위험 신호·환불)은 바로바로 따라가야 해서 100·1000건, 대량 적재(행동 로그·재생)는 1만·10만 건.
	 * outbox는 릴레이가 1초 안팎으로 내보내므로 가장 오래 기다린 것이 30초면 주의, 2분이면 위험 (예약 이벤트가 멈춤)
	 */
	static void pipelineReasons(List<Reason> rs, Pipeline p) {
		if (p.dropped5m() != null && p.dropped5m() >= 1) {
			rs.add(new Reason(p.dropped5m() >= 1000 ? "CRIT" : "WARN", "behaviorDropped",
					"행동 로그 " + Math.round(p.dropped5m()) + "건 버림 (최근 5분, 보내기 큐 넘침) — 봇 탐지 재료 유실"));
		}
		if (p.failed5m() != null && p.failed5m() >= 1) {
			rs.add(new Reason(p.failed5m() >= 1000 ? "CRIT" : "WARN", "behaviorFailed",
					"행동 로그 Kafka 전송 실패 " + Math.round(p.failed5m()) + "건 (최근 5분)"));
		}
		if (stalled(p.behaviorPerSec(), p.storedPerSec())) {
			rs.add(new Reason("WARN", "behaviorStalled", "행동 로그 적재가 멈춤 — 받음 " + fmt(p.behaviorPerSec()) + "/초인데 MongoDB 저장 "
					+ fmt(p.storedPerSec()) + "/초 (MongoDB·적재 컨슈머 확인)"));
		}
		if (p.skipped5m() != null && p.skipped5m() >= 1) {
			double stored = p.stored5m() == null ? 0 : p.stored5m();
			boolean most = p.skipped5m() >= stored;      // 건너뛴 것이 저장한 것보다 많으면: 형식이 통째로 깨진 것 (화면 배포 실수 등)
			rs.add(new Reason(most ? "CRIT" : "WARN", "behaviorSkipped", "행동 로그 " + Math.round(p.skipped5m())
					+ "건 형식 오류로 적재 안 됨 (최근 5분" + (most ? ", 저장 " + Math.round(stored) + "건보다 많음" : "") + ") — 밀림에도 안 잡히는 유실"));
		}
		level(rs, "behaviorQueue", p.queuePct(), 50, 90, v -> "행동 로그 보내기 큐 " + Math.round(v) + "% 찼음 (다 차면 버림)");
		if (Boolean.FALSE.equals(p.lagMeasured())) {
			rs.add(new Reason("WARN", "lagUnknown", "Kafka 소비 밀림을 잴 수 없음 (Kafka 연결 실패 — 이벤트·행동 로그가 멈췄을 수 있음)"));
		}
		for (TrafficView.Lag l : p.lags()) {
			if ("WARN".equals(l.level()) || "CRIT".equals(l.level())) {
				rs.add(new Reason(l.level(), "lag:" + l.group(), "Kafka " + l.name() + " " + Math.round(l.lag()) + "건 밀림"));
			}
		}
		if (p.outboxFailed() != null && p.outboxFailed() >= 1) {
			rs.add(new Reason("WARN", "outboxFailed", "outbox 발행 실패 " + Math.round(p.outboxFailed()) + "건 (재시도 대기)"));
		}
		level(rs, "outboxAge", p.outboxOldestSec(), 30, 120,
				v -> "예약 이벤트 발행이 " + Math.round(v) + "초째 밀림 (outbox " + Math.round(p.outboxPending() == null ? 0 : p.outboxPending()) + "건)");
	}

	/**
	 * 받는 양에 비해 저장이 거의 없나 (최근 30초). MongoDB가 멈추면 밀림은 천천히 쌓여 1만 건 문턱까지 몇 분이 걸린다 — 그보다 먼저 알리려고.
	 * 실측: MongoDB 60초 정지에서 밀림 896건(정상 범위)인 동안 저장은 0/초였다
	 */
	static boolean stalled(Double sent, Double stored) {
		return sent != null && stored != null && sent >= 1 && stored < sent * 0.1;
	}

	static final int QUEUE_CAPACITY = 50_000;
	static final java.util.Set<String> BULK_GROUPS = java.util.Set.of("behavior-sink", "replay-sink");

	/** 소비 그룹별 밀림 + 판정. PipelineMetrics.GROUPS 순서대로 (값 없는 그룹도 칸은 있음) */
	static List<TrafficView.Lag> lags(List<Series> ss) {
		Map<String, Double> v = new HashMap<>();
		ss.forEach(x -> v.put(x.label("group"), x.v()[0]));
		List<TrafficView.Lag> out = new ArrayList<>();
		PipelineMetrics.GROUPS.forEach((g, name) -> {
			Double lag = v.get(g);
			double warn = BULK_GROUPS.contains(g) ? 10_000 : 100, crit = BULK_GROUPS.contains(g) ? 100_000 : 1000;
			String lv = lag == null ? null : lag >= crit ? "CRIT" : lag >= warn ? "WARN" : "OK";
			out.add(new TrafficView.Lag(g, name, lag, lv));
		});
		return out;
	}

	static String worst(List<TrafficView.Lag> ls) {
		String w = null;
		for (TrafficView.Lag l : ls) {
			if ("CRIT".equals(l.level())) {
				return "CRIT";
			}
			if ("WARN".equals(l.level())) {
				w = "WARN";
			} else if (w == null && "OK".equals(l.level())) {
				w = "OK";
			}
		}
		return w;
	}

	private static void level(List<Reason> rs, String metric, Double v, double warn, double crit,
			java.util.function.DoubleFunction<String> text) {
		if (v == null) {
			return;
		}
		if (v >= crit) {
			rs.add(new Reason("CRIT", metric, text.apply(v)));
		} else if (v >= warn) {
			rs.add(new Reason("WARN", metric, text.apply(v)));
		}
	}

	private static String fmt(double v) {
		return v >= 10 ? String.valueOf(Math.round(v)) : String.valueOf(Math.round(v * 10) / 10.0);
	}

	static List<Endpoint> endpoints(List<Series> count, List<Series> err, List<Series> err4, List<Series> p95) {
		Map<String, Double> e = new HashMap<>(), e4 = new HashMap<>(), lat = new HashMap<>();
		err.forEach(s -> e.put(s.label("method") + " " + s.label("uri"), s.v()[0]));
		err4.forEach(s -> e4.put(s.label("method") + " " + s.label("uri"), s.v()[0]));
		p95.forEach(s -> lat.put(s.label("method") + " " + s.label("uri"), s.v()[0]));
		double sum = count.stream().mapToDouble(s -> s.v()[0] == null ? 0 : s.v()[0]).sum();
		return count.stream().filter(s -> s.v()[0] != null && s.v()[0] > 0)
				.sorted(Comparator.comparingDouble((Series s) -> s.v()[0]).reversed()).limit(10).map(s -> {
					String key = s.label("method") + " " + s.label("uri");
					double rps = s.v()[0];
					Double er = e.get(key), er4 = e4.get(key);
					return new Endpoint(s.label("method"), s.label("uri"), group(s.label("uri")), rps, lat.get(key),
							er == null ? 0 : er / rps * 100, er4 == null ? 0 : er4 / rps * 100, sum > 0 ? rps / sum * 100 : 0);
				}).toList();
	}

	/** 상태별 순간 값 합: [전체, 5xx, 429, 409, 기타 4xx, 예약 성공, 결제 성공] */
	static double[] mix(List<Series> ss) {
		double[] m = new double[7];
		for (Series s : ss) {
			Double v = s.v()[0];
			if (v == null) {
				continue;
			}
			String st = s.label("status"), uri = s.label("uri");
			boolean okPost = st.startsWith("2") && "POST".equals(s.label("method"));
			m[0] += v;
			m[1] += st.startsWith("5") ? v : 0;
			m[2] += "429".equals(st) ? v : 0;
			m[3] += "409".equals(st) ? v : 0;
			m[4] += st.startsWith("4") && !"409".equals(st) && !"429".equals(st) ? v : 0;
			m[5] += okPost && RESERVE_URI.equals(uri) ? v : 0;
			m[6] += okPost && PAY_URI.equals(uri) ? v : 0;
		}
		return m;
	}

	private static Double ratio(double part, double total) {
		return total > 0 ? part / total * 100 : 0d;
	}

	/** 그래프 기간 안에 앱이 새로 시작한 시각들 (배포·재시작 표시). 앱마다 process_start_time_seconds 값이 바뀐 곳 */
	static List<Long> restarts(List<Series> starts, long start, long end) {
		java.util.TreeSet<Long> out = new java.util.TreeSet<>();
		for (Series s : starts) {
			for (Double v : s.v()) {
				if (v != null && v >= start && v <= end) {
					out.add(v.longValue());
				}
			}
		}
		return List.copyOf(out);
	}

	private static void blank(Double[] a, boolean[] present) {
		for (int i = 0; i < a.length; i++) {
			if (!present[i]) {
				a[i] = null;
			}
		}
	}

	private static int idx(long ts, long start, int step, int n) {
		long d = ts - start;
		if (d < 0 || d % step != 0) {
			return -1;
		}
		long i = d / step;
		return i < n ? (int) i : -1;
	}

	private static Double[] zeros(int n) {
		Double[] a = new Double[n];
		java.util.Arrays.fill(a, 0d);
		return a;
	}

	private static void add(Double[] a, int i, double v) {
		a[i] = (a[i] == null ? 0 : a[i]) + v;
	}

	private static Double[] pct(Double[] part, Double[] total) {
		Double[] out = new Double[total.length];
		for (int i = 0; i < out.length; i++) {
			out[i] = total[i] == null || part[i] == null ? null : total[i] <= 0 ? 0d : part[i] / total[i] * 100;
		}
		return out;
	}

	/** 결과 첫 시계열을 공통 시각에 맞춘다 (없으면 전부 null) */
	private static Double[] one(List<Series> ss, long start, int step, int n) {
		Double[] out = new Double[n];
		if (ss.isEmpty()) {
			return out;
		}
		Series s = ss.get(0);
		for (int k = 0; k < s.t().length; k++) {
			int i = idx(s.t()[k], start, step, n);
			if (i >= 0) {
				out[i] = s.v()[k];
			}
		}
		return out;
	}

	/** 마지막 점. 마지막 한두 점은 수집 직전이라 빌 수 있어 끝에서 3칸까지 본다 */
	private static Double last(Double[] a) {
		for (int i = a.length - 1; i >= Math.max(0, a.length - 3); i--) {
			if (a[i] != null) {
				return a[i];
			}
		}
		return null;
	}

	private static Double scalar(List<Series> ss) {
		return ss.isEmpty() ? null : ss.get(0).v()[0];
	}
}
