package com.jejari.admin.traffic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.jejari.admin.traffic.PrometheusClient.Series;
import com.jejari.admin.traffic.TrafficView.Health;
import com.jejari.admin.traffic.TrafficView.Now;
import com.jejari.admin.traffic.TrafficView.Pipeline;
import com.jejari.admin.traffic.TrafficView.Reason;

/**
 * A-10 트래픽 관제의 상태 판정 (정상 · 주의 · 위험과 그 이유). 지금 값({@link Now})과 데이터 놓침·밀림({@link Pipeline})을
 * 고정 문턱과 비교한다. 쿼리·조립은 {@link TrafficService}, 시계열 셈은 {@link TrafficSeries}.
 *
 * <p>DB·네트워크 없이 값만 받아 판정하므로 단위 테스트로 문턱을 바로 확인할 수 있다. 문턱을 바꾸면 docs/ui/A-10.md 표도 같이.
 */
final class TrafficHealth {

	private TrafficHealth() {
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
		// 로그인 대기실은 설계대로 동작하는 것이라 "알림" (대기열·조회는 영향 없음). 사람들이 로그인에 오래 기다린다는 뜻
		if (n.loginQueuePct() != null && n.loginQueuePct() >= 50) {
			rs.add(new Reason("INFO", "loginQueue", "로그인 대기 " + Math.round(n.loginQueue() == null ? 0 : n.loginQueue())
					+ "명 (대기실 " + Math.round(n.loginQueuePct()) + "%) — 로그인이 몰림, 다른 API는 영향 없음"));
		}
		if (n.loginBusy5m() != null && n.loginBusy5m() >= 1) {
			rs.add(new Reason("INFO", "loginBusy", "로그인 " + Math.round(n.loginBusy5m()) + "건을 \"잠시 뒤 자동 재시도\"로 돌려보냄 (최근 5분, 대기실 가득)"));
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
		if (p.deadLetters5m() != null && p.deadLetters5m() >= 1) {
			rs.add(new Reason("WARN", "deadLetters", "격리 보관 " + Math.round(p.deadLetters5m())
					+ "건 (최근 5분, 소비자가 처리하지 못한 메시지) — tools/dlq.sh list로 원인 확인 후 다시 넣기"));
		}
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

	static void level(List<Reason> rs, String metric, Double v, double warn, double crit,
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

	static String fmt(double v) {
		return v >= 10 ? String.valueOf(Math.round(v)) : String.valueOf(Math.round(v * 10) / 10.0);
	}
}
