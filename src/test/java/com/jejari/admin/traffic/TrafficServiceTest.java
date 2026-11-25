package com.jejari.admin.traffic;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/** 가짜 Prometheus(같은 프로세스 HTTP 서버)로 A-10 묶음·판정을 확인한다. 인프라 없이 돈다 */
class TrafficServiceTest {

	static final long NOW = 1_795_000_000L;   // 30의 배수가 아니어도 됨 (끝은 step으로 내림)
	HttpServer server;
	final List<String> queries = new ArrayList<>();
	volatile boolean down;
	volatile boolean recovered;
	volatile boolean pipelineTrouble;   // 행동 로그 버림 · 좌석 정리 밀림 · outbox 멈춤   // 최근 30초는 깨끗하고 그래프(1분 창)에는 아직 오류가 남은 상태

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", ex -> {
			String q = URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
			queries.add(q);
			String body = down ? "" : respond(q, ex.getRequestURI().getPath().endsWith("query_range"));
			byte[] b = body.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(down ? 503 : 200, b.length == 0 ? -1 : b.length);
			try (OutputStream o = ex.getResponseBody()) {
				o.write(b);
			}
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	/** 1h 범위(30초 간격)의 마지막 점 시각 */
	static long end() {
		return NOW / 30 * 30;
	}

	static String vec(String labels, double v) {
		return "{\"metric\":{" + labels + "},\"value\":[" + NOW + ",\"" + v + "\"]}";
	}

	static String mat(String labels, double v) {
		return "{\"metric\":{" + labels + "},\"values\":[[" + (end() - 30) + ",\"" + v + "\"],[" + end() + ",\"" + v + "\"]]}";
	}

	static String ok(String type, String... rs) {
		return "{\"status\":\"success\",\"data\":{\"resultType\":\"" + type + "\",\"result\":[" + String.join(",", rs) + "]}}";
	}

	String respond(String q, boolean range) {
		if (range) {
			if (q.contains("sum by (method, uri, status)")) {
				return ok("matrix",
						mat("\"method\":\"POST\",\"uri\":\"/api/v1/reservations\",\"status\":\"201\"", 8),
						mat("\"method\":\"POST\",\"uri\":\"/api/v1/reservations\",\"status\":\"409\"", 2),
						mat("\"method\":\"GET\",\"uri\":\"/api/v1/runs/{runId}/queue\",\"status\":\"200\"", 85),
						mat("\"method\":\"GET\",\"uri\":\"/api/v1/runs/{runId}/seats\",\"status\":\"500\"", 5),
						mat("\"method\":\"GET\",\"uri\":\"/**\",\"status\":\"404\"", 45));
			}
			if (q.contains("histogram_quantile(0.95")) {
				return ok("matrix", mat("", 1500));
			}
			if (q.contains("hikaricp_connections_active")) {
				return ok("matrix", mat("", 97));
			}
			if (q.contains("offset 1d")) {
				return ok("matrix", mat("", 20));
			}
			if (q.contains("process_start_time_seconds")) {     // 30분 전에 재시작, 그 전 값은 기간 밖
				return ok("matrix", "{\"metric\":{\"instance\":\"app-1\"},\"values\":[[" + (end() - 30) + ",\"" + (end() - 1800)
						+ "\"],[" + end() + ",\"" + (end() - 1800) + "\"]]}", "{\"metric\":{\"instance\":\"app-2\"},\"values\":[["
						+ end() + ",\"" + (end() - 90000) + "\"]]}");
			}
			return ok("matrix");
		}
		if (pipelineTrouble && q.contains("increase(jejari_behavior_dropped_total")) {
			return ok("vector", vec("", 12));
		}
		if (pipelineTrouble && q.contains("increase(jejari_behavior_skipped_total")) {
			return ok("vector", vec("", 900));
		}
		if (pipelineTrouble && q.contains("increase(jejari_behavior_stored_total")) {
			return ok("vector", vec("", 300));
		}
		if (pipelineTrouble && q.contains("jejari_login_queue_size{job=\"jejari-app\"} / ")) {
			return ok("vector", vec("", 75));
		}
		if (pipelineTrouble && q.contains("max(jejari_login_queue_size")) {
			return ok("vector", vec("", 150));
		}
		if (pipelineTrouble && q.contains("jejari_login_busy_total")) {
			return ok("vector", vec("", 40));
		}
		if (q.contains("jejari_kafka_lag_measured")) {
			return ok("vector", vec("", pipelineTrouble ? 0 : 1));
		}
		if (pipelineTrouble && q.contains("rate(jejari_behavior_dropped_total")) {
			return ok("vector", vec("", 0.4));
		}
		if (q.contains("max by (group) (jejari_kafka_consumer_lag")) {
			return ok("vector", vec("\"group\":\"behavior-sink\"", 50), vec("\"group\":\"seat-release\"", pipelineTrouble ? 250 : 0));
		}
		if (q.contains("jejari_outbox_oldest_seconds")) {
			return ok("vector", vec("", pipelineTrouble ? 150 : 0));
		}
		if (q.contains("jejari_outbox_pending")) {
			return ok("vector", vec("", pipelineTrouble ? 37 : 0));
		}
		if (q.contains("up{")) {
			return ok("vector", vec("\"instance\":\"app-1\"", 1), vec("\"instance\":\"app-2\"", 0));
		}
		if (q.contains("quantile_over_time")) {
			return ok("vector", vec("", 20));
		}
		if (q.contains("sum by (method, uri, status)") && recovered) {
			return ok("vector", vec("\"method\":\"GET\",\"uri\":\"/api/v1/runs/{runId}/queue\",\"status\":\"200\"", 120));
		}
		if (q.contains("sum by (method, uri, status)")) {    // "지금" 값 (최근 30초) — 그래프와 같은 비율로
			return ok("vector", vec("\"method\":\"POST\",\"uri\":\"/api/v1/reservations\",\"status\":\"201\"", 8),
					vec("\"method\":\"POST\",\"uri\":\"/api/v1/reservations\",\"status\":\"409\"", 2),
					vec("\"method\":\"GET\",\"uri\":\"/api/v1/runs/{runId}/queue\",\"status\":\"200\"", 85),
					vec("\"method\":\"GET\",\"uri\":\"/api/v1/runs/{runId}/seats\",\"status\":\"500\"", 5),
					vec("\"method\":\"GET\",\"uri\":\"/**\",\"status\":\"404\"", 45));
		}
		if (q.contains("histogram_quantile(0.95")) {
			return ok("vector", vec("", 1500));
		}
		if (q.contains("sum by (uri, status)")) {
			return ok("vector", vec("\"uri\":\"/api/v1/runs/{runId}/queue\",\"status\":\"200\"", 40),
					vec("\"uri\":\"/api/v1/runs/{runId}/queue\",\"status\":\"429\"", 10),
					vec("\"uri\":\"/api/v1/reservations\",\"status\":\"500\"", 1),
					vec("\"uri\":\"/**\",\"status\":\"404\"", 4));
		}
		if (q.contains("_sum{")) {
			return ok("vector", vec("\"uri\":\"/api/v1/runs/{runId}/queue\"", 1.0));
		}
		if (q.contains("lettuce")) {
			return ok("vector", vec("", 300));
		}
		if (q.startsWith("query=sum by (method, uri) (rate(http_server_requests_seconds_count{") && !q.contains("status=~")) {
			return ok("vector", vec("\"method\":\"GET\",\"uri\":\"/api/v1/runs/{runId}/queue\"", 90),
					vec("\"method\":\"POST\",\"uri\":\"/api/v1/reservations\"", 10));
		}
		return ok("vector");
	}

	TrafficService service() {
		Clock clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneId.of("Asia/Seoul"));
		return new TrafficService(new PrometheusClient("http://127.0.0.1:" + server.getAddress().getPort(),
				JsonMapper.builder().build()), clock);
	}

	@Test
	void 영역별로_묶고_비율과_상태를_낸다() {
		TrafficView v = service().view("1h");

		assertThat(v.available()).isTrue();
		assertThat(v.t()).hasSize(121);
		var total = v.charts().get(0);
		assertThat(total.key()).isEqualTo("total");
		assertThat(total.lines().get(0).v()[120]).isEqualTo(145.0);
		assertThat(total.lines().get(1).v()[120]).isEqualTo(20.0);          // 어제 같은 시각
		var traffic = v.charts().get(1);
		assertThat(traffic.lines()).extracting(TrafficView.Line::name).containsExactlyElementsOf(TrafficService.GROUPS);
		assertThat(traffic.lines().get(0).v()[120]).isEqualTo(85.0);      // 대기열
		assertThat(traffic.lines().get(2).v()[120]).isEqualTo(10.0);      // 예약·결제 (201 + 409)
		assertThat(traffic.lines().get(0).v()[0]).isNull();                // 앱 지표가 없던 시각은 0이 아니라 빈칸
		assertThat(v.restarts()).containsExactly(end() - 1800);             // 기간 밖(하루 전) 시작은 빼고

		var now = v.now();
		assertThat(now.rps()).isEqualTo(145.0);
		assertThat(now.errorPct()).isEqualTo(5.0 / 145 * 100);
		assertThat(now.clientErrorPct()).isEqualTo(45.0 / 145 * 100);
		assertThat(now.conflictPerSec()).isEqualTo(2.0);
		assertThat(now.reservePerSec()).isEqualTo(8.0);
		assertThat(now.surge()).isEqualTo(7.25);                           // 145 / 평소 20
		assertThat(now.instancesUp()).isEqualTo(1);
		assertThat(now.instancesTotal()).isEqualTo(2);

		// 5xx 5% · DB 97% → 위험. p95 1.5초·앱 1대 응답 없음 → 주의. 급증은 알림
		assertThat(v.health().level()).isEqualTo("CRIT");
		assertThat(v.health().reasons()).extracting(TrafficView.Reason::metric)
				.contains("error", "db", "p95", "up", "surge", "client");
		assertThat(v.health().reasons().get(0).level()).isEqualTo("CRIT");

		assertThat(v.endpoints()).hasSize(2);
		assertThat(v.endpoints().get(0).group()).isEqualTo("대기열");
		assertThat(v.endpoints().get(0).share()).isEqualTo(90.0);
	}

	@Test
	void 흐름도는_영역별_초당값과_저장소_작업을_낸다() {
		TrafficFlow f = service().flow();

		assertThat(f.available()).isTrue();
		var queue = f.groups().get(0);
		assertThat(queue.name()).isEqualTo("대기열");
		assertThat(queue.rps()).isEqualTo(50.0);
		assertThat(queue.rejected()).isEqualTo(10.0);
		assertThat(queue.avgMs()).isEqualTo(20.0);                          // 1초 / 50건
		assertThat(f.groups().get(2).err5()).isEqualTo(1.0);
		assertThat(f.groups().get(5).err4()).isEqualTo(4.0);
		assertThat(f.stores().get(0).ops()).isEqualTo(300.0);
		assertThat(f.stores().get(1).ops()).isNull();                       // 수집 안 된 지표는 null
	}

	@Test
	void 쿼리는_서버에_고정되고_화면값이_섞이지_않는다() {
		service().view("30m");
		assertThat(TrafficService.validRange("1h")).isTrue();
		assertThat(TrafficService.validRange("sum(up)")).isFalse();
		assertThat(queries).allMatch(q -> q.startsWith("query="));
		// 이 화면 자신의 폴링은 요청 수에서 뺀다
		assertThat(queries).filteredOn(q -> q.contains("http_server_requests")).allMatch(q -> q.contains("/api/v1/admin/traffic.*"));
	}

	@Test
	void 프로메테우스가_죽으면_사용불가로_알린다() {
		down = true;
		TrafficView v = service().view("1h");
		assertThat(v.available()).isFalse();
		assertThat(v.reason()).contains("503");
		assertThat(service().flow().available()).isFalse();
	}

	@Test
	void 연결이_안되면_주소를_알려준다() {
		server.stop(0);
		TrafficView v = service().view("1h");
		assertThat(v.available()).isFalse();
		assertThat(v.reason()).contains("연결하지 못했습니다");
	}

	@Test
	void 장애가_끝나면_30초_안에_정상으로_돌아온다() {
		recovered = true;
		TrafficView v = service().view("1h");
		// 그래프에는 5xx가 남아 있어도 (1분 창) 지금 값·상태는 최근 30초로 본다
		assertThat(v.charts().get(3).lines().get(0).v()[120]).isGreaterThan(0);
		assertThat(v.now().errorPct()).isZero();
		assertThat(v.health().reasons()).extracting(TrafficView.Reason::metric).doesNotContain("error", "client");
	}

	@Test
	void 데이터를_놓치거나_밀리면_이유와_함께_알린다() {
		pipelineTrouble = true;
		TrafficView v = service().view("1h");
		var p = v.pipeline();
		assertThat(p.dropped5m()).isEqualTo(12.0);
		assertThat(p.outboxPending()).isEqualTo(37.0);
		assertThat(p.lags()).extracting(TrafficView.Lag::group).startsWith("behavior-sink", "replay-sink", "seat-release");
		assertThat(p.lags().get(0).level()).isEqualTo("OK");        // 대량 적재 50건은 정상
		assertThat(p.lags().get(1).lag()).isNull();                 // 재생 기록: 값 없음
		assertThat(p.lags().get(2).level()).isEqualTo("WARN");      // 좌석 정리 250건 (예약 흐름은 100건부터 주의)
		assertThat(v.health().reasons()).extracting(TrafficView.Reason::metric)
				.contains("behaviorDropped", "behaviorSkipped", "lagUnknown", "lag:seat-release", "outboxAge", "loginQueue", "loginBusy");
		assertThat(v.now().loginQueue()).isEqualTo(150.0);
		assertThat(v.health().reasons()).filteredOn(r -> r.metric().startsWith("login"))
				.allMatch(r -> r.level().equals("INFO"));   // 대기실은 설계대로 도는 것 — 장애가 아니라 알림
		// 건너뛴 것(900)이 저장한 것(300)보다 많으면 형식이 통째로 깨진 것 → 위험
		assertThat(v.health().reasons()).filteredOn(r -> r.metric().equals("behaviorSkipped")).first()
				.extracting(TrafficView.Reason::level).isEqualTo("CRIT");
		assertThat(v.health().reasons()).filteredOn(r -> r.metric().equals("outboxAge")).first()
				.extracting(TrafficView.Reason::level).isEqualTo("CRIT");   // 2분 넘게 멈춤
		assertThat(v.charts()).extracting(TrafficView.Chart::key).contains("behavior", "lag");

		TrafficFlow f = service().flow();
		var kafka = f.stores().get(3);
		assertThat(kafka.level()).isEqualTo("WARN");
		assertThat(kafka.note()).contains("밀림 300건").contains("행동 로그 버림");
	}

	@Test
	void 받는데_저장이_멈추면_적재_멈춤() {
		assertThat(TrafficHealth.stalled(20.0, 0.0)).isTrue();      // MongoDB 정지 실측: 받음 20/초, 저장 0/초
		assertThat(TrafficHealth.stalled(20.0, 19.0)).isFalse();
		assertThat(TrafficHealth.stalled(0.5, 0.0)).isFalse();      // 거의 안 들어올 때는 판단하지 않음
		assertThat(TrafficHealth.stalled(20.0, null)).isFalse();    // 지표 없음
	}

	@Test
	void 평소에는_데이터_항목이_상태를_흔들지_않는다() {
		TrafficView v = service().view("1h");
		assertThat(v.health().reasons()).extracting(TrafficView.Reason::metric)
				.noneMatch(m -> m.startsWith("behavior") || m.startsWith("lag:") || m.startsWith("outbox"));
	}

	@Test
	void 영역_분류() {
		assertThat(TrafficService.group("/api/v1/runs/{runId}/queue/status")).isEqualTo("대기열");
		assertThat(TrafficService.group("/api/v1/reservations/{reservationId}/payments")).isEqualTo("예약·결제");
		assertThat(TrafficService.group("/api/v1/waitlists")).isEqualTo("예약·결제");
		assertThat(TrafficService.group("/api/v1/me/reservations")).isEqualTo("로그인·내 정보");
		assertThat(TrafficService.group("/**")).isEqualTo("기타");
	}
}
