package com.jejari.train;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 예매 가능 기간이 하루씩 따라오게 운행을 이어 넣는다 (DATA-01, 담당 B 강동균).
 *
 * <p>코레일은 출발일 <b>2개월 전 07:00</b>부터 일반 승차권을 판매한다. 그래서 날짜가 하루 지나면
 * 두 달 뒤의 새 날짜가 하나 열린다. 시드는 <b>돌린 순간</b>의 창을 만들 뿐이라 그 다음은 아무도
 * 이어 주지 않았다. 어제까지 조회되던 끝 날짜가 그대로 끝이고, 날이 갈수록 창이 짧아진다.
 * 결국 창이 다 지나면 어느 날짜로 조회해도 운행이 없어 화면이 비어 버린다.
 *
 * <p>검색은 운행일이 <b>정확히 일치</b>하는 운행만 찾으므로({@link TrainQueryService}) 날짜에
 * 구멍이 있으면 그 날로 조회한 사람에게는 아무것도 안 보인다. 그래서 창을 사람이 아니라
 * 스케줄러가 유지한다.
 *
 * <p><b>아직 안 열린 날짜까지 넣는다.</b> 지평은 {@code ADD_MONTHS(오늘, 2) + 1일}이고,
 * 오픈 시각이 아직 안 지난 날은 {@code SCHEDULED}로 들어간다. 그래야 "이 날짜는 오전 7시에
 * 열립니다"를 화면에 보여 줄 수 있고, 07:00이 되면 {@link SaleOpenScheduler}가 {@code ON_SALE}로
 * 넘기는 것이 매일 실제로 일어난다. 행을 열린 뒤에 만들면 그 전이를 볼 수 없다.
 *
 * <p><b>운행 번호는 {@code train_run_seq}가 준다</b> (ERD v27). 같은 날짜가 이미 있는지는 번호가 아니라
 * 진짜 자연키 {@code (train_id, run_date)}로 본다 — {@code uq_train_run_train_id_run_date}가 이미 있다.
 * 전에는 번호에 날짜를 넣었는데({@code 700000 + ...}), 길동현 님 실제 시간표 적재는
 * {@code 400000 + 연중일 * 1000 + 열차번호}를 써서 두 대역이 겹칠 수 있었다 (겹칠 수 있는 번호 880개).
 * 지금 안 깨지는 건 실제 열차번호가 1~128이라 211 이상이 안 나오기 때문이고, 설계가 막아 주는 것이 아니다.
 * 자연키로 맞추면 번호를 맞출 필요가 없어 산식이 사라진다.
 *
 * <p><b>정차 시각은 그 열차의 기존 운행에서 베낀다.</b> 시각표의 원천은 공공데이터(TAGO 열차정보)이고
 * 시드가 넣은 값이다. 여기서 다시 계산하면 원천이 둘이 된다. 날짜만 바꿔 같은 시각을 쓰며, 날을
 * 넘기는 열차도 기준 운행일로부터의 간격을 그대로 옮기므로 어긋나지 않는다.
 *
 * <p><b>어느 열차를 이어 줄지는 설정으로 둔다</b> ({@code jejari.train.horizon-trains}, 기본 1,2 = 시드 편성).
 * 모든 열차를 자동으로 이어 주면 관리자가 화면에서 지운 운행이 되살아난다. 실제 서비스라면
 * 운행 계획(다이어) 테이블을 읽어야 하는데 우리 스키마에는 없어서, 시연 편성만 유지한다.
 *
 * <p>앱이 여러 대면 이 스케줄러도 여러 대에서 돈다. 두 대가 같은 날짜를 동시에 넣으면 한쪽이
 * {@code uq_train_run_train_id_run_date}에 걸려 그 문장만 되돌아간다. 다음 주기에는 {@code NOT EXISTS}가
 * 상대가 넣은 행을 보므로 할 일이 없어진다 — 그래서 중복 키는 경고하지 않고 넘긴다.
 *
 * <p><b>기본값은 꺼짐이다</b> ({@code jejari.train.run-horizon-enabled=true}로 켠다).
 * 판매 중 운행이 늘면 대기열 입장 스케줄러가 매초 그만큼 돈다
 * ({@code QueueAdmitScheduler} → {@code admit()}은 대기열이 비어도 Redis 왕복 4번).
 * 공용 서버는 이미 {@code deploy/tago-refresh.sh}가 실제 KTX 시간표를 매일 넣어 ON_SALE 운행이
 * 900건쯤이고, 1초 틱의 ~830ms를 쓰고 있다 (로컬 왕복 0.23ms 기준). 여기에 이 스케줄러의
 * 120건을 더하면 ~940ms로 틱을 거의 다 써서 입장 처리가 밀린다. 그래서 켜는 것은 선택으로 두고,
 * 대기열이 빈 운행을 건너뛰게 고쳐진 뒤에 기본값을 바꾼다 (팀에-알림 2-4).
 *
 * <p>로컬에서 날짜별 운행을 쓰려면 시드 04가 이미 예매 가능 기간을 깔아 두므로 그대로 쓰면 되고,
 * 날이 지나 창이 짧아지는 것까지 막으려면 이 속성을 켜면 된다.
 */
@Component
@ConditionalOnProperty(name = "jejari.train.run-horizon-enabled", havingValue = "true")
public class RunHorizonScheduler {

	private static final Logger log = LoggerFactory.getLogger(RunHorizonScheduler.class);

	/**
	 * 구멍 난 날짜에 운행을 넣는다. 오늘부터 지평(두 달 하루 뒤)까지 훑으므로, 끝 하루를 이어 주는
	 * 일과 묵은 DB를 메우는 일을 같은 문장이 한다.
	 *
	 * <p>{@code sale_open_at}·{@code sale_close_at}·{@code status}는 시드 04와 같은 규칙으로 계산한다.
	 * 상태를 하드코딩하지 않는 이유는 시드와 같다 — 규칙과 어긋날 수 있다.
	 */
	private static final String FILL_RUNS = """
			INSERT INTO train_run (run_id, train_id, sale_event_id, run_date,
			                       sale_open_at, sale_close_at, status)
			SELECT train_run_seq.NEXTVAL,
			       t.train_id, NULL, d.run_date,
			       CAST(ADD_MONTHS(d.run_date, -2) AS TIMESTAMP) + INTERVAL '7' HOUR,
			       CAST(d.run_date + 1 AS TIMESTAMP),
			       CASE WHEN CAST(ADD_MONTHS(d.run_date, -2) AS TIMESTAMP) + INTERVAL '7' HOUR
			                 <= SYSTIMESTAMP THEN 'ON_SALE' ELSE 'SCHEDULED' END
			  FROM train t
			 CROSS JOIN (SELECT TRUNC(SYSDATE) + LEVEL - 1 AS run_date
			               FROM dual
			            CONNECT BY TRUNC(SYSDATE) + LEVEL - 1 <= ADD_MONTHS(TRUNC(SYSDATE), 2) + 1) d
			 WHERE t.train_id IN (%s)
			   AND NOT EXISTS (SELECT 1 FROM train_run x
			                    WHERE x.train_id = t.train_id AND x.run_date = d.run_date)""";

	/**
	 * 정차 시각이 없는 운행에 시각표를 붙인다. 같은 열차의 가장 늦은 운행을 본뜬다.
	 *
	 * <p>시각은 {@code 새 운행일 + (원본 정차 시각 - 원본 운행일)}이다. 시·분만 떼어 쓰지 않고
	 * 원본 운행일로부터의 간격을 쓰는 이유는, 자정을 넘겨 도착하는 열차의 날짜 차이를 보존하기 위해서다.
	 */
	private static final String FILL_STOPS = """
			INSERT INTO train_stop (run_id, station_id, stop_order, arrival_time, departure_time)
			SELECT r.run_id, s.station_id, s.stop_order,
			       CASE WHEN s.arrival_time IS NULL THEN NULL
			            ELSE CAST(r.run_date AS TIMESTAMP)
			                 + (s.arrival_time - CAST(src.run_date AS TIMESTAMP)) END,
			       CASE WHEN s.departure_time IS NULL THEN NULL
			            ELSE CAST(r.run_date AS TIMESTAMP)
			                 + (s.departure_time - CAST(src.run_date AS TIMESTAMP)) END
			  FROM train_run r
			  JOIN (SELECT train_id, run_id, run_date
			          FROM (SELECT x.train_id, x.run_id, x.run_date,
			                       ROW_NUMBER() OVER (PARTITION BY x.train_id
			                                          ORDER BY x.run_date DESC, x.run_id DESC) AS rn
			                  FROM train_run x
			                 WHERE EXISTS (SELECT 1 FROM train_stop y WHERE y.run_id = x.run_id))
			         WHERE rn = 1) src ON src.train_id = r.train_id
			  JOIN train_stop s ON s.run_id = src.run_id
			 WHERE r.train_id IN (%s)
			   AND NOT EXISTS (SELECT 1 FROM train_stop z WHERE z.run_id = r.run_id)""";

	private final JdbcTemplate jdbc;
	private final String fillRuns;
	private final String fillStops;

	public RunHorizonScheduler(JdbcTemplate jdbc,
			@Value("${jejari.train.horizon-trains:1,2}") List<Long> trains) {
		this.jdbc = jdbc;
		// 열차 번호는 설정값(숫자)이라 그대로 넣는다. Long으로 받았으니 문자열이 섞일 수 없다
		String in = trains.isEmpty() ? "NULL" : trains.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("NULL");
		this.fillRuns = FILL_RUNS.formatted(in);
		this.fillStops = FILL_STOPS.formatted(in);
	}

	/**
	 * 하루에 한 날짜만 늘어나므로 자주 볼 필요가 없다. 10분이면 자정과 07:00을 넉넉히 따라간다.
	 * 정책값(app_policy)으로 두지 않은 이유는 바꿀 일이 없고, 키를 늘리면 ERD 예시 행까지 따라오기 때문이다.
	 */
	@Scheduled(fixedDelay = 600_000, initialDelay = 20_000)
	void tick() {
		try {
			extendOnce();
		} catch (DataIntegrityViolationException e) {
			// 다른 앱이 같은 날짜를 먼저 넣었다. 다음 주기엔 NOT EXISTS가 걸러 준다
			log.debug("운행 지평 연장: 다른 인스턴스와 겹침 (무시)", e);
		} catch (RuntimeException e) {
			log.warn("운행 지평 연장 실패 (다음 주기에 다시)", e);
		}
	}

	/**
	 * 지평까지 빠진 운행과 정차를 한 번 채운다.
	 *
	 * @return 넣은 운행 수와 정차 수
	 */
	public Extended extendOnce() {
		int runs = jdbc.update(fillRuns);
		int stops = jdbc.update(fillStops);
		if (runs > 0 || stops > 0) {
			log.info("운행 지평 연장: 운행 {}건, 정차 {}건", runs, stops);
		}
		return new Extended(runs, stops);
	}

	/** 한 번 채운 결과 */
	public record Extended(int runs, int stops) {
	}
}
