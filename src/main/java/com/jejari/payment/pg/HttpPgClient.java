package com.jejari.payment.pg;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 네트워크(HTTP)로 부르는 PG. 실제 PG를 붙일 때의 모양 그대로이고, 지금은 가짜 PG 서버({@code loadtest/mock-pg/server.js})를
 * 부른다. {@code jejari.payment.pg.mode=http}일 때만 쓴다 (기본은 같은 JVM 안의 {@link MockPgClient}).
 *
 * <p><b>왜 필요한가</b>: MockPgClient는 0초 만에 답해서 "PG가 느릴 때 결제를 기다리는 요청 스레드가 다 차서 조회·선점까지 같이
 * 멈추는지"를 잴 수 없다. 실제 결제 장애는 대부분 여기서 난다 (PG 지연 → 연결·스레드 대기 → 다른 기능까지 전파).
 *
 * <p>시간 초과는 두 개다.
 * <ul>
 *   <li>연결(connect-timeout-ms): PG에 연결조차 안 될 때</li>
 *   <li>응답(read-timeout-ms): 보낸 뒤 답이 늦을 때. <b>승인 요청이 이걸 넘기면 결과 모름(UNKNOWN)</b> — PG는 이미 승인했을 수
 *       있으니 실패로 치지 않고, 마감에 §6.2 스캐너가 조회해 정한다. 조회·주문 막기·환불은 실패해도 다음 주기에 다시 한다</li>
 * </ul>
 *
 * <p>지표: {@code jejari_pg_call_seconds{op,result}} (승인·조회·막기·환불 각각의 걸린 시간과 결과),
 * {@code jejari_pg_inflight} (지금 PG 응답을 기다리는 요청 수 = PG 때문에 묶인 스레드 수).
 */
public class HttpPgClient implements PgClient {

	private static final Logger log = LoggerFactory.getLogger(HttpPgClient.class);

	private final URI base;
	private final Duration readTimeout;
	private final HttpClient http;
	private final JsonMapper json;
	private final MeterRegistry meters;
	private final AtomicInteger inflight = new AtomicInteger();

	public HttpPgClient(String baseUrl, Duration connectTimeout, Duration readTimeout, JsonMapper json, MeterRegistry meters) {
		this.base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
		// 승인 요청 본문에 카드번호가 들어간다. 같은 기기 안의 가짜 PG가 아니면 https만 (평문으로 밖에 나가지 않게)
		String host = base.getHost() == null ? "" : base.getHost();
		boolean local = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1") || host.equals("[::1]");
		if (!"https".equalsIgnoreCase(base.getScheme()) && !local) {
			throw new IllegalStateException("PG 주소는 https여야 합니다 (127.0.0.1·localhost의 가짜 PG만 http 허용): " + baseUrl);
		}
		this.readTimeout = readTimeout;
		// HTTP/1.1 고정: 평문 http에 HTTP/2 업그레이드를 시도하지 않게 (실제 PG는 https라 상관없음)
		this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout).build();
		this.json = json;
		this.meters = meters;
		Gauge.builder("jejari_pg_inflight", inflight, AtomicInteger::get)
				.description("PG 응답을 기다리는 요청 수 (PG 때문에 묶인 스레드)").register(meters);
		log.info("PG: HTTP {} (연결 {}ms, 응답 {}ms)", this.base, connectTimeout.toMillis(), readTimeout.toMillis());
	}

	@Override
	public ApproveResult approve(long orderId, long amount, String cardNumber) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("orderId", orderId);
		body.put("amount", amount);
		body.put("cardNumber", cardNumber);
		Call c = post("approve", "v1/payments/approve", body);
		if (c.node == null) {
			return new ApproveResult(Outcome.UNKNOWN, null, c.failure); // 시간 초과·연결 실패·5xx: 돈이 빠졌는지 모름
		}
		String outcome = c.node.path("outcome").asString("");
		return switch (outcome) {
			case "APPROVED" -> new ApproveResult(Outcome.APPROVED, text(c.node, "pgTxId"), null);
			case "DECLINED" -> new ApproveResult(Outcome.DECLINED, null, text(c.node, "reason"));
			default -> new ApproveResult(Outcome.UNKNOWN, null, "PG_UNKNOWN");
		};
	}

	@Override
	public Inquiry inquire(long orderId) {
		Call c = send("inquire", HttpRequest.newBuilder(base.resolve("v1/payments/" + orderId)).GET());
		if (c.node == null) {
			return new Inquiry(Status.UNKNOWN, null, c.failure); // PG 장애 = 모름 → 다음 조회로 미룸
		}
		String s = c.node.path("status").asString("UNKNOWN");
		Status status = switch (s) {
			case "APPROVED", "FAILED", "NOT_FOUND" -> Status.valueOf(s);
			default -> Status.UNKNOWN;
		};
		return new Inquiry(status, text(c.node, "pgTxId"), text(c.node, "reason"));
	}

	@Override
	public boolean blockOrder(long orderId) {
		Call c = post("block", "v1/payments/" + orderId + "/block", Map.of());
		return c.node != null && c.node.path("blocked").asBoolean(false); // 확인 못 하면 false (스캐너가 다음에 다시)
	}

	@Override
	public RefundResult refund(long orderId, String pgTxId, long amount, String refundKey) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("orderId", orderId);
		body.put("pgTxId", pgTxId);
		body.put("amount", amount);
		body.put("refundKey", refundKey);
		Call c = post("refund", "v1/refunds", body);
		if (c.node == null) {
			return new RefundResult(false, null, c.failure); // 같은 환불 키로 다시 보내도 한 번만 환불 (CNL-03 재시도)
		}
		return new RefundResult(c.node.path("done").asBoolean(false), text(c.node, "refundTxId"), text(c.node, "reason"));
	}

	// ── HTTP ────────────────────────────────────────────

	/** node가 null이면 실패, failure에 이유 (PG_TIMEOUT · PG_CONNECT · PG_HTTP_5xx · PG_ERROR) */
	private record Call(JsonNode node, String failure) {
	}

	private Call post(String op, String path, Object body) {
		String payload;
		try {
			payload = json.writeValueAsString(body);
		} catch (RuntimeException e) {
			throw new IllegalStateException("PG 요청 JSON 만들기 실패", e);
		}
		return send(op, HttpRequest.newBuilder(base.resolve(path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(payload)));
	}

	private Call send(String op, HttpRequest.Builder req) {
		long t0 = System.nanoTime();
		String result;
		Call call;
		inflight.incrementAndGet();
		try {
			HttpResponse<String> res = http.send(req.timeout(readTimeout).build(), HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() / 100 == 2) {
				call = new Call(json.readTree(res.body()), null);
				result = "ok";
			} else {
				call = new Call(null, "PG_HTTP_" + res.statusCode());
				result = "http_" + res.statusCode();
			}
		} catch (HttpTimeoutException e) {
			// HttpConnectTimeoutException도 HttpTimeoutException이다
			boolean connect = e instanceof java.net.http.HttpConnectTimeoutException;
			call = new Call(null, connect ? "PG_CONNECT" : "PG_TIMEOUT");
			result = connect ? "connect_timeout" : "timeout";
		} catch (IOException e) {
			call = new Call(null, "PG_CONNECT");
			result = "io_error";
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			call = new Call(null, "PG_ERROR");
			result = "interrupted";
		} catch (RuntimeException e) {
			call = new Call(null, "PG_ERROR");
			result = "error";
		} finally {
			inflight.decrementAndGet();
		}
		Timer.builder("jejari_pg_call_seconds").description("PG 호출 시간 (op: approve·inquire·block·refund)")
				.tag("op", op).tag("result", result).register(meters)
				.record(Duration.ofNanos(System.nanoTime() - t0));
		if (call.node == null) {
			log.warn("PG {} 실패: {} ({}ms)", op, call.failure, (System.nanoTime() - t0) / 1_000_000);
		}
		return call;
	}

	private static String text(JsonNode n, String field) {
		JsonNode v = n.get(field);
		return v == null || v.isNull() ? null : v.asString();
	}
}
