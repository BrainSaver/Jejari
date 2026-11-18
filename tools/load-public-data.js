#!/usr/bin/env node
/*
 * 공공데이터포털 CSV → 기준 데이터 시드 SQL 값 생성 (DATA-01, 담당 B 강동균)
 *
 *   node tools/load-public-data.js          검사만 (SQL이 CSV와 맞는지, 다르면 종료코드 1)
 *   node tools/load-public-data.js --write  SQL의 값 블록을 다시 생성
 *
 * 왜 필요한가
 *   CSV를 눈으로 보고 SQL에 손으로 옮기면 틀려도 아무도 모른다. 실제로 역코드를 두 번
 *   틀렸다(4자리로 추측 → 3자리로 정정 → 진짜는 7자리). 값이 CSV에서 왔다는 것을
 *   기계가 보증해야 한다. 기능설계서 1.1이 ADM-01 등록 화면 대신 약속한 "적재 스크립트"다.
 *
 * 하는 일
 *   - db/seed/data/*.csv 를 읽는다 (cp949. Node 기본 ICU의 euc-kr 디코더로 읽는다)
 *   - db/seed/data/tago-train-info.json 도 읽는다 (tools/fetch-train-api.js 가 받아 둔 실제 시각표·운임)
 *   - 역코드·역 순번·누적거리·차량유형을 뽑아 SQL의 @generated 블록만 바꾼다
 *     (MERGE 문과 주석은 손으로 쓴 것이라 건드리지 않는다)
 *   - 거리 원본은 역×역 매트릭스라 기준역명이 ORIGIN 인 행을 읽어 누적거리로 바꾼다
 *
 * 역을 늘리거나 노선을 바꾸려면 아래 TARGET 만 고치면 된다.
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const DATA = path.join(ROOT, 'db', 'seed', 'data');
const SEED = path.join(ROOT, 'db', 'seed');

// ───────────────────────────────── 무엇을 적재할지
const TARGET = {
  routeId: 1,
  lineName: '경부선',          // KTX 노선별 역정보의 노선명
  origin: '서울',              // 누적거리 기준역 (거리 매트릭스의 기준역명)
  // 기획서가 "노선 1개·역 4~5개"로 줄였다. KTX 경부선 19개 역 중 아래만 쓴다.
  stations: ['서울', '광명', '대전', '동대구', '부산'],
  // 차량유형 247건 중 등급이 이름에 분명히 적힌 둘. seat_grade·seat_columns는
  // 원본에 없는 값이라 우리가 정한다 (DB설계서에 '생성'으로 표기).
  carTypes: [
    { code: '50554101', grade: 'GENERAL', columns: 'ABCD' },
    { code: '60303101', grade: 'FIRST', columns: 'ABC' },
  ],
};

// ───────────────────────────────── CSV 읽기
function readCsv(globPart) {
  const file = fs.readdirSync(DATA).find((f) => f.includes(globPart) && f.endsWith('.csv'));
  if (!file) throw new Error(`db/seed/data 에 "${globPart}" 가 들어간 CSV가 없습니다. db/seed/data/README.md 참고`);
  const text = new TextDecoder('euc-kr').decode(fs.readFileSync(path.join(DATA, file)));
  return { file, rows: parseCsv(text) };
}

/** 따옴표로 감싼 필드와 그 안의 쉼표를 처리하는 최소 CSV 파서. */
function parseCsv(text) {
  const rows = [];
  let row = [], field = '', quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; } else quoted = false;
      } else field += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\n') { row.push(field); rows.push(row); row = []; field = ''; }
    else if (c !== '\r') field += c;
  }
  if (field !== '' || row.length) { row.push(field); rows.push(row); }
  return rows.filter((r) => r.some((v) => v.trim() !== '')).map((r) => r.map((v) => v.trim()));
}

// ───────────────────────────────── 데이터 뽑기
function extract() {
  const notes = [];

  // 1) 역코드
  const stn = readCsv('철도운영정보_역코드');
  const codeByName = new Map();
  for (const [code, name] of stn.rows.slice(1)) {
    if (!codeByName.has(name)) codeByName.set(name, code);   // 같은 역명이 여러 건이면 첫 건
  }

  // 2) 노선별 역 순번 — 우리가 고른 역의 상대 순서를 정하는 데 쓴다
  const ktx = readCsv('KTX 노선별 역정보');
  const hdr = ktx.rows[0];
  const iLine = hdr.findIndex((h) => h.includes('노선명'));
  const iSeq = hdr.findIndex((h) => h.includes('순번'));
  const iName = hdr.findIndex((h) => h.includes('역명'));
  const seqByName = new Map();
  for (const r of ktx.rows.slice(1)) {
    if (r[iLine] === TARGET.lineName) seqByName.set(r[iName], Number(r[iSeq]));
  }

  // 3) 누적거리 — 원본은 역×역 매트릭스. 기준역명이 ORIGIN 인 행을 읽는다
  const dist = readCsv('철도운행거리');
  const dHdr = dist.rows[0];
  const originRow = dist.rows.slice(1).find((r) => r[0] === TARGET.origin);
  if (!originRow) throw new Error(`거리 CSV에 기준역명 "${TARGET.origin}" 행이 없습니다`);

  // 역 정보를 모아 공공데이터 순번대로 정렬한 뒤 1..n 으로 다시 매긴다
  const stations = TARGET.stations.map((name) => {
    const code = codeByName.get(name);
    if (!code) throw new Error(`역코드 CSV에 "${name}" 이(가) 없습니다`);
    const seq = seqByName.get(name);
    if (seq === undefined) throw new Error(`${TARGET.lineName} 역 목록에 "${name}" 이(가) 없습니다`);
    const col = dHdr.indexOf(name);
    if (col < 0) throw new Error(`거리 CSV에 "${name}" 컬럼이 없습니다`);
    const km = Number(originRow[col]);
    if (!Number.isFinite(km)) throw new Error(`"${name}" 거리 값을 읽지 못했습니다: ${originRow[col]}`);
    return { name, code, publicSeq: seq, km };
  }).sort((a, b) => a.publicSeq - b.publicSeq);
  stations.forEach((s, i) => { s.order = i + 1; });
  notes.push(`역 ${stations.length}개 (${TARGET.lineName} 공공 순번 ${stations.map((s) => s.publicSeq).join('·')} → 1~${stations.length})`);

  // 4) 차량유형
  const car = readCsv('차량유형코드');
  const carByCode = new Map(car.rows.slice(1).map((r) => [r[0], { name: r[1], seats: Number(r[2]) }]));
  const carTypes = TARGET.carTypes.map((t) => {
    const found = carByCode.get(t.code);
    if (!found) throw new Error(`차량유형 CSV에 코드 "${t.code}" 가 없습니다`);
    if (!Number.isFinite(found.seats) || found.seats <= 0) throw new Error(`차량유형 ${t.code} 좌석수가 이상합니다: ${found.seats}`);
    return { ...t, name: found.name, seats: found.seats };
  });
  notes.push(`차량유형 ${carTypes.map((c) => `${c.code}(${c.seats}석)`).join(' · ')}`);

  // 5) TAGO 열차정보 (실제 시각표·운임). tools/fetch-train-api.js 가 받아 둔 것
  if (!fs.existsSync(TAGO_FILE)) {
    throw new Error('db/seed/data/tago-train-info.json 이 없습니다. node tools/fetch-train-api.js 로 먼저 받으세요');
  }
  const doc = JSON.parse(fs.readFileSync(TAGO_FILE, 'utf8'));
  const tago = { down: pickTrain(doc, 'down'), up: pickTrain(doc, 'up'), fares: fareTable(doc) };
  notes.push(`TAGO 조회일 ${doc.queryDate} · 하행 ${tago.down.trainNo} · 상행 ${tago.up.trainNo} · 구간 운임 ${tago.fares.size}개`);

  const files = [stn.file, ktx.file, dist.file, car.file, path.basename(TAGO_FILE)];
  return { stations, carTypes, tago, files, notes };
}

// ───────────────────────────────── SQL 블록 만들기
const q = (s) => `'${String(s).replace(/'/g, "''")}'`;

/** 한글·한자는 터미널·편집기에서 두 칸을 차지한다. 글자 수로 세면 정렬이 어긋난다. */
function width(s) {
  let w = 0;
  for (const ch of String(s)) {
    const c = ch.codePointAt(0);
    w += (c >= 0x1100 && (c <= 0x115f || c === 0x2329 || c === 0x232a
      || (c >= 0x2e80 && c <= 0xa4cf && c !== 0x303f)
      || (c >= 0xac00 && c <= 0xd7a3) || (c >= 0xf900 && c <= 0xfaff)
      || (c >= 0xfe30 && c <= 0xfe6f) || (c >= 0xff00 && c <= 0xff60)
      || (c >= 0xffe0 && c <= 0xffe6))) ? 2 : 1;
  }
  return w;
}
const pad = (s, n) => String(s) + ' '.repeat(Math.max(0, n - width(s)));

function blockStation(d) {
  return d.stations.map((s, i) =>
    `  SELECT ${pad(q(s.code), 11)}${i === 0 ? ' AS station_id,' : ','} ${pad(q(s.name), 10)}${i === 0 ? ' AS station_name' : ''} FROM dual${i < d.stations.length - 1 ? ' UNION ALL' : ''}`
  ).join('\n');
}

function blockRoute(d) {
  const o = d.stations[0], z = d.stations[d.stations.length - 1];
  return `  SELECT ${TARGET.routeId} AS route_id, ${q(TARGET.lineName)} AS route_name,\n` +
         `         ${q(o.code)} AS origin_station_id, ${q(z.code)} AS dest_station_id FROM dual`;
}

function blockRouteStation(d) {
  return d.stations.map((s, i) => {
    const km = s.km.toFixed(1);
    return i === 0
      ? `  SELECT ${TARGET.routeId} AS route_id, ${q(s.code)} AS station_id, ${s.order} AS station_order, ${pad(km, 7)} AS distance_from_origin_km FROM dual UNION ALL`
      : `  SELECT ${TARGET.routeId},             ${q(s.code)},               ${s.order},${' '.repeat(16)}${pad(km, 7)}${' '.repeat(24)}FROM dual${i < d.stations.length - 1 ? ' UNION ALL' : ''}`;
  }).join('\n');
}

function blockCarType(d) {
  return d.carTypes.map((c, i) =>
    `  SELECT ${q(c.code)}${i === 0 ? ' AS car_type_code,' : ','} ${pad(q(c.name), 22)}${i === 0 ? ' AS car_type_name,' : ','} ${pad(c.seats, 2)}${i === 0 ? ' AS seat_count,' : ','}\n` +
    `         ${pad(q(c.grade), 11)}${i === 0 ? ' AS seat_grade,' : ','} ${pad(q(c.columns), 8)}${i === 0 ? ' AS seat_columns' : ''} FROM dual${i < d.carTypes.length - 1 ? ' UNION ALL' : ''}`
  ).join('\n');
}

function blockTrainCar(d) {
  const first = d.carTypes.find((c) => c.grade === 'FIRST');
  const general = d.carTypes.find((c) => c.grade === 'GENERAL');
  return `         CASE WHEN c.car_no = 1 THEN ${q(first.code)} ELSE ${q(general.code)} END AS car_type_code`;
}

// ───────────────────────────────── TAGO 열차정보 (실제 시각표·운임)
// tools/fetch-train-api.js 가 받아 둔 파일을 읽는다. 없으면 그 블록만 건너뛴다
// (CSV만 있는 사람도 검사가 돌게).
const TAGO_FILE = path.join(DATA, 'tago-train-info.json');

/** 정차·운임에 쓸 열차 한 편을 고른다: 순수 KTX · 5역 전정차 · 09시 이후 첫 편 */
function pickTrain(doc, dirn) {
  const names = doc.stations.map((s) => s.name);
  const order = dirn === 'down' ? names : [...names].reverse();
  const origin = order[0], term = order[order.length - 1];
  const arr = {}, dep = {}, grade = {};
  for (const r of doc[dirn].fares) {
    if (r.from === origin) (arr[r.trainNo] ??= {})[r.to] = r.arrive;
    if (r.to === term) (dep[r.trainNo] ??= {})[r.from] = r.depart;
    grade[r.trainNo] = r.grade;
  }
  const full = Object.keys(arr).filter((t) => grade[t] === 'KTX'
    && order.slice(1).every((s) => arr[t]?.[s]) && order.slice(0, -1).every((s) => dep[t]?.[s]));
  if (!full.length) throw new Error(`${dirn}: 5역에 모두 서는 KTX가 없습니다`);
  full.sort((a, b) => dep[a][origin].slice(8, 12).localeCompare(dep[b][origin].slice(8, 12)));
  const trainNo = full.find((t) => dep[t][origin].slice(8, 10) >= '09') ?? full[0];
  return { trainNo, order, arr: arr[trainNo], dep: dep[trainNo] };
}

/** 구간 운임(일반실 어른). 같은 구간에 여러 편이 있으면 최빈값을 쓴다 (특별열차 값에 끌려가지 않게) */
function fareTable(doc) {
  const by = new Map();
  for (const dirn of ['down', 'up']) {
    for (const r of doc[dirn].fares) {
      if (r.grade !== 'KTX' || !Number.isFinite(r.adultCharge) || r.adultCharge <= 0) continue;
      const k = `${r.from}>${r.to}`;
      if (!by.has(k)) by.set(k, new Map());
      const m = by.get(k);
      m.set(r.adultCharge, (m.get(r.adultCharge) ?? 0) + 1);
    }
  }
  const out = new Map();
  for (const [k, m] of by) {
    out.set(k, [...m.entries()].sort((a, b) => b[1] - a[1] || a[0] - b[0])[0][0]);
  }
  return out;
}

function blockTrain(d) {
  const [down, up] = [d.tago.down.trainNo, d.tago.up.trainNo];
  return `  SELECT 1 AS train_id, ${q(down)} AS train_no, 'KTX' AS train_kind, ${TARGET.routeId} AS route_id, 'DOWN' AS direction FROM dual UNION ALL\n`
       + `  SELECT 2,             ${q(up)},${' '.repeat(Math.max(1, 13 - up.length))}'KTX',${' '.repeat(16)}${TARGET.routeId},${' '.repeat(13)}'UP'               FROM dual`;
}

/** 방향별 정차 시각표. 시·분만 쓰고 날짜는 SQL이 run_date에서 붙인다 */
function blockSchedule(d) {
  const codeOf = new Map(d.stations.map((s) => [s.name, s.code]));
  const rows = [];
  for (const [dirn, label] of [['down', 'DOWN'], ['up', 'UP']]) {
    const t = d.tago[dirn];
    t.order.forEach((name, i) => {
      const hm = (v) => (v ? q(v.slice(8, 12)) : 'CAST(NULL AS VARCHAR2(4))');
      const first = rows.length === 0;
      rows.push(`  SELECT ${q(label)}${first ? ' AS direction,' : ','} ${pad(q(codeOf.get(name)), 11)}`
        + `${first ? ' AS station_id,' : ','} ${i + 1}${first ? ' AS stop_order,' : ','} `
        + `${pad(hm(t.arr[name]), 29)}${first ? ' AS arr_hm,' : ','} ${pad(hm(t.dep[name]), 29)}`
        + `${first ? ' AS dep_hm' : ''} FROM dual`);
    });
  }
  return rows.join(' UNION ALL\n');
}

function blockFare(d) {
  const codeOf = new Map(d.stations.map((s) => [s.name, s.code]));
  const names = d.stations.map((s) => s.name);
  const rows = [];
  for (const g of [{ grade: 'GENERAL', rate: 1.0, base: 0 }, { grade: 'FIRST', rate: 1.4, base: 1000 }]) {
    for (let i = 0; i < names.length; i++) {
      for (let j = 0; j < names.length; j++) {
        if (i === j) continue;
        const basic = d.tago.fares.get(`${names[i]}>${names[j]}`);
        if (!basic) throw new Error(`운임 없음: ${names[i]} → ${names[j]}`);
        const amount = Math.round(basic * g.rate / 100) * 100;
        const first = rows.length === 0;
        rows.push(`  SELECT ${g.base + (i + 1) * 10 + (j + 1)}${first ? ' AS fare_id,' : ','} `
          + `'KTX'${first ? ' AS train_kind,' : ','} ${pad(q(g.grade), 9)}${first ? ' AS seat_grade,' : ','} `
          + `${pad(q(codeOf.get(names[i])), 11)}${first ? ' AS from_station_id,' : ','} `
          + `${pad(q(codeOf.get(names[j])), 11)}${first ? ' AS to_station_id,' : ','} `
          + `${pad(amount, 6)}${first ? ' AS amount' : ''} FROM dual`);
      }
    }
  }
  return rows.join(' UNION ALL\n');
}

const BLOCKS = {
  'db/seed/01_station_route.sql': { station: blockStation, route: blockRoute, route_station: blockRouteStation },
  'db/seed/02_car_type_train_seat.sql': { car_type: blockCarType, train_car: blockTrainCar, train: blockTrain },
  'db/seed/05_train_stop_fare.sql': { schedule: blockSchedule, fare: blockFare },
};

// ───────────────────────────────── 블록 교체·비교
const START = (k) => `-- @generated:${k} (tools/load-public-data.js — 손으로 고치지 마세요)`;
const END = (k) => `-- @end:${k}`;

function replaceBlock(text, key, body, file) {
  const s = text.indexOf(START(key));
  const e = text.indexOf(END(key));
  if (s < 0 || e < 0) {
    throw new Error(`${file} 에 @generated:${key} / @end:${key} 마커가 없습니다`);
  }
  const head = text.slice(0, s + START(key).length);
  const tail = text.slice(e);
  return `${head}\n${body}\n${tail}`;
}

function currentBlock(text, key) {
  const s = text.indexOf(START(key));
  const e = text.indexOf(END(key));
  if (s < 0 || e < 0) return null;
  // 개행만 떼어낸다. 공백까지 떼면 첫 줄 들여쓰기 차이를 못 잡는다.
  return text.slice(s + START(key).length, e).replace(/^[\r\n]+/, '').replace(/[\r\n]+$/, '');
}

// ───────────────────────────────── 실행
function main() {
  const write = process.argv.includes('--write');
  const d = extract();

  console.log('읽은 파일');
  d.files.forEach((f) => console.log('  ' + f));
  console.log('\n뽑은 값');
  d.notes.forEach((n) => console.log('  ' + n));
  d.stations.forEach((s) => console.log(`    ${pad(s.name, 8)} ${s.code}  순번 ${s.order}  누적 ${s.km.toFixed(1)} km`));

  let diff = 0;
  for (const [rel, makers] of Object.entries(BLOCKS)) {
    const file = path.join(ROOT, rel);
    let text = fs.readFileSync(file, 'utf8');
    for (const [key, make] of Object.entries(makers)) {
      const want = make(d);                 // 첫 줄 들여쓰기를 살려 그대로 쓴다
      const have = currentBlock(text, key);
      if (have === null) { console.error(`\n! ${rel} 에 @generated:${key} 마커가 없습니다`); diff++; continue; }
      if (have === want) continue;
      diff++;
      if (write) {
        text = replaceBlock(text, key, want, rel);
        console.log(`\n고침  ${rel}  [${key}]`);
      } else {
        console.log(`\n다름  ${rel}  [${key}]`);
        console.log('  지금:\n' + have.split('\n').map((l) => '    ' + l).join('\n'));
        console.log('  CSV:\n' + want.trim().split('\n').map((l) => '    ' + l).join('\n'));
      }
    }
    if (write) fs.writeFileSync(file, text);
  }

  console.log();
  if (diff === 0) {
    console.log('OK — 시드 SQL이 공공데이터 CSV와 일치합니다.');
  } else if (write) {
    console.log(`${diff}개 블록을 다시 생성했습니다. db/seed/99_verify.sql 로 확인하세요.`);
  } else {
    console.log(`FAIL — ${diff}개 블록이 CSV와 다릅니다. 고치려면: node tools/load-public-data.js --write`);
    process.exit(1);
  }
}

main();
