#!/usr/bin/env node
// 실제 KTX 시간표(국토교통부 TAGO 열차정보 API)를 받아 Oracle에 넣을 SQL을 만든다.
//
//   node tools/load-tago-schedule.js            오늘 기준 앞뒤 7일 (15일)
//   node tools/load-tago-schedule.js --days 3   앞뒤 3일
//
// 결과: build/tago/schedule.sql (MERGE라 다시 돌려도 같음), 원본 응답은 build/tago/raw/에 캐시
// 넣기: docker exec -i jejari-oracle-1 bash -c 'sqlplus -s / as sysdba' < build/tago/schedule.sql
//
// 서비스키: 환경변수 DATA_GO_KR_KEY_DEC, 없으면 .env의 그 줄만 읽는다. 키는 출력하지 않는다.
// API: https://www.data.go.kr/data/15098552/openapi.do (출/도착지기반열차정보 조회)
//
// 넣는 범위 (기존 시드·관리자 화면과 겹치지 않게)
//   train      train_id = 10000 + 열차번호, train_no는 시드와 같은 5자리('00103'), train_kind 'KTX'(운임표와 같게), 좌석 배치는 열차 1을 복사
//              시드(train_id < 10000)에 같은 열차 번호가 있으면 그 편은 넣지 않는다 (시드가 이미 매일 운행을 깔아 둠).
//              예전에 넣어 둔 같은 편의 운행은 예약·대기·좌석 행이 없으면 지운다 (검색에 같은 열차가 두 번 나오던 것)
//   train_run  run_id = 400000 + 연중일 × 1000 + 열차번호 (시드 1001~9003, 관리자 1,000,000~ 와 안 겹침)
//              지난 날은 DEPARTED, 나머지는 ON_SALE. 판매 마감 = 출발 시각
//   train_stop 서울·광명·대전·동대구·부산 중 실제로 서는 역만. stop_order는 진행 방향 노선 순서 (건너뛴 역은 빈 번호)
// 서울 출발 → 부산 도착(하행), 부산 출발 → 서울 도착(상행) KTX만 넣는다.

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(ROOT, 'build', 'tago');
const RAW = path.join(OUT, 'raw');
const BASE = 'https://apis.data.go.kr/1613000/TrainInfo';

// 노선 순서(하행). id = 우리 station_id(코레일 7자리), tago = TAGO 역 ID
const STATIONS = [
  { id: '3900023', tago: 'NAT010000', name: '서울' },
  { id: '3900883', tago: 'NATH10219', name: '광명' },
  { id: '3900073', tago: 'NAT011668', name: '대전' },
  { id: '3900096', tago: 'NAT013271', name: '동대구' },
  { id: '3900114', tago: 'NAT014445', name: '부산' },
];
const TEMPLATE_TRAIN = 1; // 좌석 배치를 복사할 시드 열차
const trainNo = (no) => String(no).padStart(5, '0'); // 시드와 같은 표기 ('00103')

function serviceKey() {
  if (process.env.DATA_GO_KR_KEY_DEC) return process.env.DATA_GO_KR_KEY_DEC.trim();
  const envFile = path.join(ROOT, '.env');
  if (fs.existsSync(envFile)) {
    const m = fs.readFileSync(envFile, 'utf8').match(/^DATA_GO_KR_KEY_DEC\s*=\s*"?([^"\r\n]+)"?/m);
    if (m) return m[1].trim();
  }
  throw new Error('DATA_GO_KR_KEY_DEC가 없습니다 (환경변수 또는 .env)');
}

const KEY = serviceKey();
const redact = (s) => String(s).split(KEY).join('***').split(encodeURIComponent(KEY)).join('***');

async function trains(dep, arr, ymd) {
  const cache = path.join(RAW, `${ymd}-${dep.tago}-${arr.tago}.json`);
  let body;
  if (fs.existsSync(cache)) {
    body = JSON.parse(fs.readFileSync(cache, 'utf8'));
  } else {
    const q = new URLSearchParams({
      serviceKey: KEY, _type: 'json', numOfRows: '500', pageNo: '1',
      depPlaceId: dep.tago, arrPlaceId: arr.tago, depPlandTime: ymd,
    });
    let res;
    for (let attempt = 1; ; attempt++) {
      try {
        res = await fetch(`${BASE}/GetStrtpntAlocFndTrainInfo?${q}`);
        break;
      } catch (e) {
        if (attempt >= 3) throw new Error(redact(e.message));
        await new Promise((r) => setTimeout(r, 1000 * attempt));
      }
    }
    const text = await res.text();
    try {
      body = JSON.parse(text);
    } catch {
      throw new Error(`${dep.name}→${arr.name} ${ymd}: HTTP ${res.status} ${redact(text).slice(0, 200)}`);
    }
    const code = body?.response?.header?.resultCode;
    if (code !== '00') {
      throw new Error(`${dep.name}→${arr.name} ${ymd}: ${redact(JSON.stringify(body)).slice(0, 300)}`);
    }
    fs.writeFileSync(cache, JSON.stringify(body));
  }
  let items = body?.response?.body?.items?.item ?? [];
  if (!Array.isArray(items)) items = [items];
  return items.filter((it) => String(it.traingradename).startsWith('KTX'));
}

const ts = (s) => `TIMESTAMP '${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)} ${s.slice(8, 10)}:${s.slice(10, 12)}:${s.slice(12, 14)}'`;
const ymdOf = (d) => d.toISOString().slice(0, 10).replace(/-/g, '');
const dayOfYear = (ymd) => {
  const d = Date.UTC(+ymd.slice(0, 4), +ymd.slice(4, 6) - 1, +ymd.slice(6, 8));
  return Math.round((d - Date.UTC(+ymd.slice(0, 4), 0, 1)) / 86400000) + 1;
};
const plusMin = (s, m) => {
  const d = new Date(Date.UTC(+s.slice(0, 4), +s.slice(4, 6) - 1, +s.slice(6, 8), +s.slice(8, 10), +s.slice(10, 12) + m));
  return d.toISOString().replace(/[-T:]/g, '').slice(0, 14);
};

/** 한 방향 하루치: 열차번호 → { no, stops: [{order, id, arr, dep}] } */
async function dayDirection(ymd, route) {
  const origin = route[0];
  const dest = route[route.length - 1];
  const runs = new Map();
  for (const it of await trains(origin, dest, ymd)) {
    runs.set(it.trainno, {
      no: Number(it.trainno),
      stops: [
        { order: 1, id: origin.id, arr: null, dep: String(it.depplandtime) },
        { order: route.length, id: dest.id, arr: String(it.arrplandtime), dep: null },
      ],
    });
  }
  // 중간역: (출발역 → X)에서 X 도착, (X → 종착역)에서 X 출발
  for (let i = 1; i < route.length - 1; i++) {
    const x = route[i];
    const arrAt = new Map((await trains(origin, x, ymd)).map((it) => [it.trainno, String(it.arrplandtime)]));
    const depAt = new Map((await trains(x, dest, ymd)).map((it) => [it.trainno, String(it.depplandtime)]));
    for (const [no, run] of runs) {
      let a = arrAt.get(no);
      let d = depAt.get(no);
      if (!a && !d) continue; // 이 역은 서지 않음
      if (!a) a = plusMin(d, -1);
      if (!d) d = plusMin(a, 1);
      run.stops.push({ order: i + 1, id: x.id, arr: a, dep: d });
    }
  }
  for (const run of runs.values()) run.stops.sort((p, q) => p.order - q.order);
  return [...runs.values()];
}

async function main() {
  const i = process.argv.indexOf('--days');
  const days = i > 0 ? Number(process.argv[i + 1]) : 7;
  fs.mkdirSync(RAW, { recursive: true });

  // 날짜는 서울 기준
  const todaySeoul = new Date(Date.now() + 9 * 3600 * 1000);
  todaySeoul.setUTCHours(0, 0, 0, 0);
  const dates = [];
  for (let d = -days; d <= days; d++) dates.push(ymdOf(new Date(todaySeoul.getTime() + d * 86400000)));
  const today = ymdOf(todaySeoul);

  const down = STATIONS;
  const up = [...STATIONS].reverse();
  const trainsSeen = new Map(); // train_id → {no, direction}
  const runRows = [];
  const stopRows = [];
  const skipped = new Set();

  for (const ymd of dates) {
    for (const [route, direction] of [[down, 'DOWN'], [up, 'UP']]) {
      const runs = await dayDirection(ymd, route);
      for (const r of runs) {
        if (r.no >= 1000) {
          // 9045 같은 4자리는 임시 열차. run_id 규칙(열차번호 3자리)에 안 맞아 건너뛴다
          skipped.add(r.no);
          continue;
        }
        const trainId = 10000 + r.no;
        const prev = trainsSeen.get(trainId);
        if (prev && prev.direction !== direction) throw new Error(`열차 ${r.no}가 상·하행 모두에 나옵니다`);
        trainsSeen.set(trainId, { no: r.no, direction });
        const runId = 400000 + dayOfYear(ymd) * 1000 + r.no;
        const departure = r.stops[0].dep;
        runRows.push({ runId, trainId, ymd, departure, status: ymd < today ? 'DEPARTED' : 'ON_SALE' });
        for (const s of r.stops) stopRows.push({ runId, trainId, ...s });
      }
      process.stdout.write(`${ymd} ${direction} ${runs.filter((r) => r.no < 1000).length}편\n`);
    }
  }

  const sql = [];
  sql.push('-- 생성: tools/load-tago-schedule.js (TAGO 열차정보). 직접 고치지 말고 다시 생성하세요');
  sql.push(`-- 범위 ${dates[0]} ~ ${dates[dates.length - 1]}, 열차 ${trainsSeen.size}개, 운행 ${runRows.length}건, 정차 ${stopRows.length}건`);
  sql.push('WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK');
  sql.push('SET FEEDBACK OFF');
  sql.push('ALTER SESSION SET CONTAINER = FREEPDB1;');
  sql.push('ALTER SESSION SET CURRENT_SCHEMA = jejari;');
  sql.push('');

  // 시드에 같은 번호의 열차가 있으면 그 편은 시드 몫이다 (같은 열차가 검색에 두 번 나오지 않게)
  const TAGO_IDS = 'BETWEEN 10000 AND 10999';
  const notSeed = (no) =>
    `NOT EXISTS (SELECT 1 FROM train o WHERE o.train_id NOT ${TAGO_IDS} AND LPAD(o.train_no, 5, '0') = '${trainNo(no)}')`;

  // 예전에 넣은 운행 중 시드와 겹치는 편: 아무도 안 쓰면 지운다
  const shadowRuns = `SELECT r.run_id FROM train_run r JOIN train t ON t.train_id = r.train_id
   WHERE t.train_id ${TAGO_IDS}
     AND EXISTS (SELECT 1 FROM train o WHERE o.train_id NOT ${TAGO_IDS} AND LPAD(o.train_no, 5, '0') = LPAD(t.train_no, 5, '0'))
     AND NOT EXISTS (SELECT 1 FROM reservation x WHERE x.run_id = r.run_id)
     AND NOT EXISTS (SELECT 1 FROM waitlist x WHERE x.run_id = r.run_id)
     AND NOT EXISTS (SELECT 1 FROM seat_inventory x WHERE x.run_id = r.run_id)`;
  sql.push(`DELETE FROM train_stop WHERE run_id IN (${shadowRuns});`);
  sql.push(`DELETE FROM train_run WHERE run_id IN (${shadowRuns});`);
  sql.push('');

  // 열차 (번호 표기는 시드와 같은 5자리로 맞춘다)
  for (const [trainId, t] of trainsSeen) {
    sql.push(`MERGE INTO train t USING (SELECT ${trainId} AS train_id, '${trainNo(t.no)}' AS train_no, 'KTX' AS train_kind, 1 AS route_id, '${t.direction}' AS direction FROM dual
   WHERE ${notSeed(t.no)}) s
  ON (t.train_id = s.train_id)
  WHEN MATCHED THEN UPDATE SET t.train_no = s.train_no, t.updated_at = SYSTIMESTAMP
  WHEN NOT MATCHED THEN INSERT (train_id, train_no, train_kind, route_id, direction) VALUES (s.train_id, s.train_no, s.train_kind, s.route_id, s.direction);`);
  }
  const ids = [...trainsSeen.keys()].join(',');
  // 객차·좌석: 시드 열차 1의 배치를 그대로 (seat_id = train_id × 100000 + 호차 × 1000 + 순번, 시드와 같은 규칙)
  sql.push(`INSERT INTO train_car (train_id, car_no, car_type_code)
  SELECT t.train_id, c.car_no, c.car_type_code FROM train t CROSS JOIN train_car c
   WHERE t.train_id IN (${ids}) AND c.train_id = ${TEMPLATE_TRAIN}
     AND NOT EXISTS (SELECT 1 FROM train_car x WHERE x.train_id = t.train_id AND x.car_no = c.car_no);`);
  sql.push(`INSERT INTO seat (seat_id, train_id, car_no, seat_no, seat_grade, is_window)
  SELECT t.train_id * 100000 + MOD(s.seat_id, 100000), t.train_id, s.car_no, s.seat_no, s.seat_grade, s.is_window
    FROM train t CROSS JOIN seat s
   WHERE t.train_id IN (${ids}) AND s.train_id = ${TEMPLATE_TRAIN}
     AND NOT EXISTS (SELECT 1 FROM seat x WHERE x.train_id = t.train_id);`);
  sql.push('');

  // 운행: 판매 오픈은 코레일 규칙(2개월 전 07:00), 마감은 출발 시각
  // 우리 열차(train 행이 있는 것)만. 같은 run_id를 다른 열차가 쓰고 있으면 건드리지 않는다 (시드 700000번대와 번호가 겹칠 수 있음)
  for (const r of runRows) {
    sql.push(`MERGE INTO train_run t USING (SELECT ${r.runId} AS run_id, ${r.trainId} AS train_id, DATE '${r.ymd.slice(0, 4)}-${r.ymd.slice(4, 6)}-${r.ymd.slice(6, 8)}' AS run_date, ${ts(r.departure)} AS close_at, '${r.status}' AS status FROM dual
   WHERE EXISTS (SELECT 1 FROM train x WHERE x.train_id = ${r.trainId}) AND ${notSeed(r.trainId - 10000)}) s
  ON (t.run_id = s.run_id)
  WHEN MATCHED THEN UPDATE SET t.sale_close_at = s.close_at, t.status = CASE WHEN t.status = 'CLOSED' THEN t.status ELSE s.status END, t.updated_at = SYSTIMESTAMP
    WHERE t.train_id = s.train_id
  WHEN NOT MATCHED THEN INSERT (run_id, train_id, sale_event_id, run_date, sale_open_at, sale_close_at, status)
    VALUES (s.run_id, s.train_id, NULL, s.run_date, CAST(ADD_MONTHS(s.run_date, -2) AS TIMESTAMP) + INTERVAL '7' HOUR, s.close_at, s.status);`);
  }
  sql.push('');

  // 정차 시각
  for (const s of stopRows) {
    sql.push(`MERGE INTO train_stop t USING (SELECT ${s.runId} AS run_id, '${s.id}' AS station_id, ${s.order} AS stop_order, ${s.arr ? ts(s.arr) : 'CAST(NULL AS TIMESTAMP)'} AS a, ${s.dep ? ts(s.dep) : 'CAST(NULL AS TIMESTAMP)'} AS d FROM dual
   WHERE EXISTS (SELECT 1 FROM train_run x WHERE x.run_id = ${s.runId} AND x.train_id = ${s.trainId})) s
  ON (t.run_id = s.run_id AND t.station_id = s.station_id)
  WHEN MATCHED THEN UPDATE SET t.stop_order = s.stop_order, t.arrival_time = s.a, t.departure_time = s.d, t.updated_at = SYSTIMESTAMP
  WHEN NOT MATCHED THEN INSERT (run_id, station_id, stop_order, arrival_time, departure_time) VALUES (s.run_id, s.station_id, s.stop_order, s.a, s.d);`);
  }
  sql.push('');
  sql.push('COMMIT;');
  sql.push(`SELECT 'tago loaded: train ' || (SELECT COUNT(*) FROM train WHERE train_id ${TAGO_IDS})
    || ', run ' || (SELECT COUNT(*) FROM train_run WHERE train_id ${TAGO_IDS})
    || ', stop ' || (SELECT COUNT(*) FROM train_stop s JOIN train_run r ON r.run_id = s.run_id WHERE r.train_id ${TAGO_IDS})
    || ', seed와 겹쳐 남은 운행(예약 있음) ' || (SELECT COUNT(*) FROM train_run r JOIN train t ON t.train_id = r.train_id
         WHERE t.train_id ${TAGO_IDS} AND EXISTS (SELECT 1 FROM train o WHERE o.train_id NOT ${TAGO_IDS} AND LPAD(o.train_no, 5, '0') = LPAD(t.train_no, 5, '0'))) AS result FROM dual;`);
  sql.push('EXIT');

  const file = path.join(OUT, 'schedule.sql');
  fs.writeFileSync(file, sql.join('\n') + '\n');
  if (skipped.size) console.log(`건너뛴 4자리 임시 열차: ${[...skipped].sort((a, b) => a - b).join(', ')}`);
  console.log(`\n열차 ${trainsSeen.size}개, 운행 ${runRows.length}건, 정차 ${stopRows.length}건 → ${path.relative(ROOT, file)}`);
}

main().catch((e) => {
  console.error(redact(e.message));
  process.exit(1);
});
