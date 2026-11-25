package com.jejari.admin.traffic;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.jejari.admin.traffic.PrometheusClient.Series;
import com.jejari.admin.traffic.TrafficView.Endpoint;

/**
 * A-10 트래픽 관제의 시계열 셈. Prometheus 결과를 공통 시각에 맞추고(빈칸은 0이 아니라 null), 비율·합·마지막 값을 구한다.
 * 많이 불린 API 표와 상태별 합도 여기서 만든다. DB·네트워크 없이 값만 다룬다.
 */
final class TrafficSeries {

	private TrafficSeries() {
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
					return new Endpoint(s.label("method"), s.label("uri"), TrafficService.group(s.label("uri")), rps, lat.get(key),
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
			m[5] += okPost && TrafficService.RESERVE_URI.equals(uri) ? v : 0;
			m[6] += okPost && TrafficService.PAY_URI.equals(uri) ? v : 0;
		}
		return m;
	}

	static Double ratio(double part, double total) {
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

	static void blank(Double[] a, boolean[] present) {
		for (int i = 0; i < a.length; i++) {
			if (!present[i]) {
				a[i] = null;
			}
		}
	}

	static int idx(long ts, long start, int step, int n) {
		long d = ts - start;
		if (d < 0 || d % step != 0) {
			return -1;
		}
		long i = d / step;
		return i < n ? (int) i : -1;
	}

	static Double[] zeros(int n) {
		Double[] a = new Double[n];
		java.util.Arrays.fill(a, 0d);
		return a;
	}

	static void add(Double[] a, int i, double v) {
		a[i] = (a[i] == null ? 0 : a[i]) + v;
	}

	static Double[] pct(Double[] part, Double[] total) {
		Double[] out = new Double[total.length];
		for (int i = 0; i < out.length; i++) {
			out[i] = total[i] == null || part[i] == null ? null : total[i] <= 0 ? 0d : part[i] / total[i] * 100;
		}
		return out;
	}

	/** 결과 첫 시계열을 공통 시각에 맞춘다 (없으면 전부 null) */
	static Double[] one(List<Series> ss, long start, int step, int n) {
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
	static Double last(Double[] a) {
		for (int i = a.length - 1; i >= Math.max(0, a.length - 3); i--) {
			if (a[i] != null) {
				return a[i];
			}
		}
		return null;
	}

	static Double scalar(List<Series> ss) {
		return ss.isEmpty() ? null : ss.get(0).v()[0];
	}
}
