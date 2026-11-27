package com.jejari.payment.pg;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jejari.payment.pg.PgClient.Outcome;
import com.jejari.payment.pg.PgClient.Status;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/** HttpPgClient: 응답 해석, 시간 초과·연결 실패·5xx를 "모름"으로 (돈이 빠졌는지 모를 때 실패로 치지 않음, §6.2) */
class HttpPgClientTest {

	HttpServer server;
	/** 다음 응답: 상태 코드, 본문, 지연(ms) */
	final AtomicReference<Object[]> next = new AtomicReference<>(new Object[] { 200, "{}", 0L });
	final AtomicReference<String> lastPath = new AtomicReference<>();
	final AtomicReference<String> lastBody = new AtomicReference<>();
	SimpleMeterRegistry meters;
	HttpPgClient pg;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		server.createContext("/", ex -> {
			lastPath.set(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
			lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			Object[] r = next.get();
			try {
				Thread.sleep((Long) r[2]);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			byte[] b = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
			try {
				ex.sendResponseHeaders((Integer) r[0], b.length);
				try (OutputStream os = ex.getResponseBody()) {
					os.write(b);
				}
			} catch (IOException ignored) {
				// 클라이언트가 시간 초과로 먼저 끊음
			}
		});
		server.start();
		meters = new SimpleMeterRegistry();
		pg = client("http://127.0.0.1:" + server.getAddress().getPort(), 300);
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	HttpPgClient client(String url, long readMs) {
		return new HttpPgClient(url, Duration.ofMillis(300), Duration.ofMillis(readMs), JsonMapper.builder().build(), meters);
	}

	void reply(int status, String body, long delayMs) {
		next.set(new Object[] { status, body, delayMs });
	}

	@Test
	void 승인_거절_모름을_읽는다() {
		reply(200, "{\"outcome\":\"APPROVED\",\"pgTxId\":\"pg-1\",\"reason\":null}", 0);
		var a = pg.approve(7, 59800, "4111111111111111");
		assertThat(a.outcome()).isEqualTo(Outcome.APPROVED);
		assertThat(a.pgTxId()).isEqualTo("pg-1");
		assertThat(lastPath.get()).isEqualTo("POST /v1/payments/approve");
		assertThat(lastBody.get()).contains("\"orderId\":7").contains("\"amount\":59800").contains("4111111111111111");

		reply(200, "{\"outcome\":\"DECLINED\",\"pgTxId\":null,\"reason\":\"CARD_DECLINED\"}", 0);
		var d = pg.approve(8, 1000, "4111111111111110");
		assertThat(d.outcome()).isEqualTo(Outcome.DECLINED);
		assertThat(d.reason()).isEqualTo("CARD_DECLINED");

		reply(200, "{\"outcome\":\"UNKNOWN\"}", 0);
		assertThat(pg.approve(9, 1000, "4111111111111119").outcome()).isEqualTo(Outcome.UNKNOWN);
	}

	@Test
	void 승인_응답이_늦으면_모름() {
		reply(200, "{\"outcome\":\"APPROVED\",\"pgTxId\":\"pg-late\"}", 1500); // 읽기 한도 300ms
		long t0 = System.nanoTime();
		var r = pg.approve(1, 1000, "4111111111111111");
		long ms = (System.nanoTime() - t0) / 1_000_000;
		assertThat(r.outcome()).isEqualTo(Outcome.UNKNOWN);
		assertThat(r.reason()).isEqualTo("PG_TIMEOUT");
		assertThat(ms).as("한도에서 끊겨야 스레드를 오래 붙잡지 않는다").isLessThan(1200);
		assertThat(meters.get("jejari_pg_call_seconds").tag("op", "approve").tag("result", "timeout").timer().count())
				.isEqualTo(1);
	}

	@Test
	void PG_5xx와_연결_실패도_모름() throws IOException {
		reply(503, "{\"error\":\"PG_OVERLOADED\"}", 0);
		var r = pg.approve(1, 1000, "4111111111111111");
		assertThat(r.outcome()).isEqualTo(Outcome.UNKNOWN);
		assertThat(r.reason()).isEqualTo("PG_HTTP_503");

		int closed;
		try (ServerSocket s = new ServerSocket(0)) {
			closed = s.getLocalPort(); // 닫힌 포트 = 연결 거부
		}
		HttpPgClient down = client("http://127.0.0.1:" + closed, 300);
		assertThat(down.approve(1, 1000, "4111111111111111").outcome()).isEqualTo(Outcome.UNKNOWN);
		assertThat(down.inquire(1).status()).isEqualTo(Status.UNKNOWN);
		assertThat(down.blockOrder(1)).isFalse();
		assertThat(down.refund(1, "pg-1", 1000, "cancel:1").done()).isFalse();
	}

	@Test
	void 밖의_PG는_https만() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> client("http://pg.example.com", 300))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("https");
		client("https://pg.example.com", 300); // 만들기만 (부르지 않음)
		client("http://localhost:8090", 300);
	}

	@Test
	void 조회_막기_환불() {
		reply(200, "{\"status\":\"APPROVED\",\"pgTxId\":\"pg-9\"}", 0);
		var q = pg.inquire(42);
		assertThat(q.status()).isEqualTo(Status.APPROVED);
		assertThat(q.pgTxId()).isEqualTo("pg-9");
		assertThat(lastPath.get()).isEqualTo("GET /v1/payments/42");

		reply(200, "{\"status\":\"NOT_FOUND\"}", 0);
		assertThat(pg.inquire(43).status()).isEqualTo(Status.NOT_FOUND);
		reply(200, "{\"status\":\"이상한값\"}", 0);
		assertThat(pg.inquire(44).status()).isEqualTo(Status.UNKNOWN);

		reply(200, "{\"blocked\":true}", 0);
		assertThat(pg.blockOrder(45)).isTrue();
		assertThat(lastPath.get()).isEqualTo("POST /v1/payments/45/block");

		reply(200, "{\"done\":true,\"refundTxId\":\"rf-1\"}", 0);
		var rf = pg.refund(46, "pg-46", 5000, "cancel:9");
		assertThat(rf.done()).isTrue();
		assertThat(rf.refundTxId()).isEqualTo("rf-1");
		assertThat(lastBody.get()).contains("\"refundKey\":\"cancel:9\"");
	}

	@Test
	void 기다리는_요청_수를_센다() throws Exception {
		reply(200, "{\"outcome\":\"APPROVED\",\"pgTxId\":\"x\"}", 250);
		var own = new SimpleMeterRegistry(); // 지표 이름이 같으면 먼저 등록한 클라이언트(pg)의 값이 보이므로 따로
		HttpPgClient slow = new HttpPgClient("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(300),
				Duration.ofMillis(2000), JsonMapper.builder().build(), own);
		Thread t = new Thread(() -> slow.approve(1, 1, "4111111111111111"));
		t.start();
		Thread.sleep(120);
		assertThat(own.get("jejari_pg_inflight").gauge().value()).isEqualTo(1.0);
		t.join();
		assertThat(own.get("jejari_pg_inflight").gauge().value()).isEqualTo(0.0);
	}
}
