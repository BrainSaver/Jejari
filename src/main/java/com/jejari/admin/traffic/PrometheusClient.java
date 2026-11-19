package com.jejari.admin.traffic;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prometheus HTTP API를 읽기만 하는 얇은 클라이언트 (A-10 트래픽 관제).
 *
 * <p>쿼리는 {@link TrafficService}에 <b>고정</b>돼 있고 화면은 기간(30m·1h·6h·24h)만 고른다. 화면에서 PromQL을 받지 않으므로
 * 관리자 화면을 통해 Prometheus에 아무 쿼리나 보내는 통로가 생기지 않는다.
 *
 * <p>Prometheus는 서버에서 {@code 127.0.0.1:9090}에만 열려 있다 (compose.yaml). 앱이 같은 호스트에서 돌므로 그대로 닿는다.
 */
@Component
public class PrometheusClient {

	static final Duration TIMEOUT = Duration.ofSeconds(3);

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
	private final String base;
	private final JsonMapper json;

	public PrometheusClient(@Value("${jejari.ops.prometheus-url:http://127.0.0.1:9090}") String base, JsonMapper json) {
		this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
		this.json = json;
	}

	public String base() {
		return base;
	}

	/** 시계열 하나: 라벨 + (초, 값) 점들. 값이 NaN·Inf면 null */
	public record Series(Map<String, String> labels, long[] t, Double[] v) {
		public String label(String k) {
			return labels.getOrDefault(k, "");
		}
	}

	/** 조회 실패 (연결 안 됨, 시간 초과, 쿼리 오류) */
	public static class Unavailable extends RuntimeException {
		public Unavailable(String message) {
			super(message);
		}
	}

	public CompletableFuture<List<Series>> range(String query, long start, long end, int step) {
		return get("/api/v1/query_range?query=" + enc(query) + "&start=" + start + "&end=" + end + "&step=" + step);
	}

	public CompletableFuture<List<Series>> instant(String query, long time) {
		return get("/api/v1/query?query=" + enc(query) + "&time=" + time);
	}

	private CompletableFuture<List<Series>> get(String path) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).timeout(TIMEOUT).GET().build();
		return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).handle((res, err) -> {
			if (err != null) {
				throw new Unavailable("Prometheus(" + base + ")에 연결하지 못했습니다");
			}
			if (res.statusCode() != 200) {
				throw new Unavailable("Prometheus 응답 " + res.statusCode());
			}
			return parse(res.body());
		});
	}

	List<Series> parse(String body) {
		JsonNode root = json.readTree(body);
		if (!"success".equals(root.path("status").asString(""))) {
			throw new Unavailable("Prometheus 쿼리 오류: " + root.path("error").asString(""));
		}
		List<Series> out = new ArrayList<>();
		for (JsonNode r : root.path("data").path("result")) {
			Map<String, String> labels = new LinkedHashMap<>();
			r.path("metric").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asString("")));
			JsonNode pts = r.has("values") ? r.path("values") : null;
			if (pts == null) {
				JsonNode one = r.path("value");
				out.add(new Series(labels, new long[] { (long) one.path(0).asDouble() }, new Double[] { num(one.path(1)) }));
				continue;
			}
			long[] t = new long[pts.size()];
			Double[] v = new Double[pts.size()];
			for (int i = 0; i < pts.size(); i++) {
				t[i] = (long) pts.path(i).path(0).asDouble();
				v[i] = num(pts.path(i).path(1));
			}
			out.add(new Series(labels, t, v));
		}
		return out;
	}

	private static Double num(JsonNode n) {
		try {
			double d = Double.parseDouble(n.asString(""));
			return Double.isFinite(d) ? d : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
