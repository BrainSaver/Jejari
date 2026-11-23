#!/usr/bin/env node
/*
 * 국토교통부 (TAGO) 열차정보 API → db/seed/data/tago-train-*.json (DATA-01, 담당 B 강동균)
 *
 *   node tools/fetch-train-api.js            받아서 저장 (이미 있으면 덮어쓰기 전에 묻지 않고 바꿈)
 *   node tools/fetch-train-api.js --date 20261125   조회일 지정 (기본: 오늘 + 7일)
 *   node tools/fetch-train-api.js --dry       저장하지 않고 요약만
 *
 * 왜 API까지 쓰나
 *   역·노선·거리·차량유형은 CSV(파일데이터)로 받아 쓰고 있었지만, **정차 시각과 운임은
 *   공공데이터가 아니라 우리 계산식**이었다 (거리÷2.7km/분 + 정차 2분, 2,800원 + 125원/km).
 *   이 API가 실제 시각표와 운임을 주므로 그 두 칸을 실제 값으로 바꾼다.
 *
 * 왜 한 번 받아 파일로 두나
 *   CSV와 같은 이유다. 빌드할 때마다 외부를 부르면 느리고, 포털이 멈추면 빌드도 멈춘다.
 *   받은 것을 db/seed/data/ 에 그대로 두면 "이 값이 어디서 왔나"를 추적할 수 있고
 *   CI가 네트워크 없이 검사할 수 있다.
 *
 * 주의
 *   - 오퍼레이션 이름은 **첫 글자가 대문자**다 (GetCtyCodeList). 소문자로 부르면
 *     NO_OPENAPI_SERVICE_ERROR(12)가 나는데 "키가 없다"가 아니라 "그런 경로가 없다"는 뜻이다.
 *   - API는 **중간역 정차 시각을 주지 않는다** (출발역·도착역 둘뿐). 그래서 역 쌍을 돌며
 *     모아야 한다. 어떤 역의 도착 시각은 (기점 → 그 역), 출발 시각은 (그 역 → 종점)에서 나온다.
 *   - 운임은 **일반실 어른(adultcharge)만** 준다. 특실은 여전히 우리가 정한다.
 *   - 서비스키는 .env 의 DATA_GO_KR_KEY (옛 이름 DATA_GO_KR_KEY_DEC 도 받는다). 저장소에 올리지 않는다.
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(ROOT, 'db', 'seed', 'data');
const BASE = 'https://apis.data.go.kr/1613000/TrainInfo';

// ───────────────────────────────── 무엇을 받을지
// 시드와 같은 역 5개. 순서가 곧 노선 순서다 (db/seed/01_station_route.sql).
const STATIONS = [
  { name: '서울', cityCode: '11' },
  { name: '광명', cityCode: '31' },
  { name: '대전', cityCode: '25' },
  { name: '동대구', cityCode: '22' },
  { name: '부산', cityCode: '21' },
];

/** 우리가 쓰는 열차 종류. 시드가 KTX 편성이라 KTX만 본다 */
const GRADE = 'KTX';

// ───────────────────────────────── API 호출
/**
 * 서비스키를 찾는다. 환경변수 먼저, 없으면 .env 의 그 줄만 읽는다.
 *
 * 이름이 둘인 이유: 예전 포털은 인코딩·디코딩 키를 따로 줬고, tools/load-tago-schedule.js 가
 * 디코딩 키를 쓴다는 뜻으로 DATA_GO_KR_KEY_DEC 를 썼다. 요즘 포털은 64자 키 하나만 주므로
 * 팀은 DATA_GO_KR_KEY 하나로 모으는 중이다. 팀원 .env 를 깨뜨리지 않으려고 옛 이름도 받는다.
 */
function key() {
  const NAMES = ['DATA_GO_KR_KEY', 'DATA_GO_KR_KEY_DEC'];
  for (const n of NAMES) {
    if (process.env[n]) return process.env[n].trim();
  }
  const env = path.join(ROOT, '.env');
  if (fs.existsSync(env)) {
    const text = fs.readFileSync(env, 'utf8');
    for (const n of NAMES) {
      const hit = text.split(/\r?\n/).find((l) => l.startsWith(n + '='));
      if (hit) return hit.slice(n.length + 1).trim();
    }
  }
  throw new Error('DATA_GO_KR_KEY 가 없습니다 (환경변수 또는 .env, 공공데이터포털 일반 인증키)');
}

const SERVICE_KEY = key();
let calls = 0;

async function call(op, params) {
  const q = new URLSearchParams({ serviceKey: SERVICE_KEY, _type: 'json', numOfRows: '200', pageNo: '1', ...params });
  const url = `${BASE}/${op}?${q}`;
  for (let attempt = 1; ; attempt++) {
    calls++;
    let res;
    try {
      res = await fetch(url, { signal: AbortSignal.timeout(20000) });
    } catch (e) {
      if (attempt >= 3) throw new Error(`${op} 호출 실패 (3번 시도): ${e.message}`);
      await sleep(1000 * attempt);
      continue;
    }
    const text = await res.text();
    let json;
    try {
      json = JSON.parse(text);
    } catch {
      throw new Error(`${op} 응답이 JSON이 아닙니다: ${text.slice(0, 200)}`);
    }
    // 포털은 오류도 200으로 준다. 모양을 보고 판단한다
    const err = json.OpenAPI_ServiceResponse?.cmmMsgHeader;
    if (err) {
      throw new Error(`${op}: ${err.errMsg} (${err.returnReasonCode}) — ${err.returnAuthMsg}`);
    }
    const header = json.response?.header;
    if (header && header.resultCode !== '00') {
      throw new Error(`${op}: ${header.resultMsg} (${header.resultCode})`);
    }
    const body = json.response?.body;
    const items = body?.items;
    if (!items || items === '') return [];        // 결과 없음은 빈 문자열로 온다
    const item = items.item;
    return Array.isArray(item) ? item : [item];
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ───────────────────────────────── 1) 역 코드 찾기
async function resolveStations() {
  const out = [];
  for (const s of STATIONS) {
    const list = await call('GetCtyAcctoTrainSttnList', { cityCode: s.cityCode });
    const hit = list.find((x) => x.nodename === s.name);
    if (!hit) throw new Error(`${s.cityCode} 도시의 기차역 목록에 "${s.name}" 이(가) 없습니다`);
    out.push({ name: s.name, nodeId: hit.nodeid });
    await sleep(120);
  }
  return out;
}

// ───────────────────────────────── 2) 시각표·운임
/** 한 구간(출발역 → 도착역)의 KTX 목록 */
async function section(from, to, date) {
  const rows = await call('GetStrtpntAlocFndTrainInfo', {
    depPlaceId: from.nodeId, arrPlaceId: to.nodeId, depPlandTime: date,
  });
  await sleep(120);
  return rows
    .filter((r) => String(r.traingradename).startsWith(GRADE))
    .map((r) => ({
      trainNo: String(r.trainno),
      grade: r.traingradename,
      from: from.name,
      to: to.name,
      depart: String(r.depplandtime),     // YYYYMMDDHHMMSS
      arrive: String(r.arrplandtime),
      adultCharge: Number(r.adultcharge),
    }));
}

/**
 * 한 방향(역 순서대로)의 시각표와 운임을 모은다.
 *
 * <p>정차 시각: 어떤 역의 도착은 (기점 → 그 역), 출발은 (그 역 → 종점)에서 나온다.
 * API가 중간역 시각을 주지 않아서 이렇게 모은다.
 * <p>운임: 모든 순서쌍을 받는다 (5역이면 20쌍). 화면이 아무 구간이나 검색할 수 있어야 한다.
 */
async function direction(stations, date) {
  const origin = stations[0];
  const terminus = stations[stations.length - 1];
  const fares = [];
  for (let i = 0; i < stations.length; i++) {
    for (let j = i + 1; j < stations.length; j++) {
      fares.push(...await section(stations[i], stations[j], date));
    }
  }
  // 도착·출발 시각을 모으기 위한 구간은 위 운임 조회에 모두 들어 있다 (기점→X, X→종점 포함)
  const arrivals = fares.filter((r) => r.from === origin.name);
  const departures = fares.filter((r) => r.to === terminus.name);
  return { origin: origin.name, terminus: terminus.name, fares, arrivals, departures };
}

// ───────────────────────────────── 실행
function ymd(d) {
  return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`;
}

async function main() {
  const argv = process.argv.slice(2);
  const dry = argv.includes('--dry');
  const di = argv.indexOf('--date');
  const date = di >= 0 ? argv[di + 1] : ymd(new Date(Date.now() + 7 * 86400000));
  if (!/^\d{8}$/.test(date)) throw new Error(`--date 는 YYYYMMDD 입니다: ${date}`);

  console.log(`조회일 ${date} · 열차 종류 ${GRADE}`);
  console.log('\n역 코드 찾는 중…');
  const down = await resolveStations();
  down.forEach((s) => console.log(`  ${s.name.padEnd(6)} ${s.nodeId}`));

  const up = [...down].reverse();
  console.log('\n하행 (서울 → 부산) 받는 중…');
  const downData = await direction(down, date);
  console.log(`  구간 ${downData.fares.length}건`);
  console.log('상행 (부산 → 서울) 받는 중…');
  const upData = await direction(up, date);
  console.log(`  구간 ${upData.fares.length}건`);

  const doc = {
    source: {
      portal: 'https://www.data.go.kr/data/15098552/openapi.do',
      name: '국토교통부_(TAGO)_열차정보',
      endPoint: BASE,
      operations: ['GetCtyCodeList', 'GetCtyAcctoTrainSttnList', 'GetStrtpntAlocFndTrainInfo'],
      note: '운임은 일반실 어른(adultcharge)만 제공. 특실은 우리가 정한다',
    },
    fetchedAt: new Date().toISOString(),
    queryDate: date,
    grade: GRADE,
    stations: down,
    down: downData,
    up: upData,
  };

  const file = path.join(OUT, 'tago-train-info.json');
  if (dry) {
    console.log(`\n--dry 라 저장하지 않음. 호출 ${calls}번`);
  } else {
    fs.mkdirSync(OUT, { recursive: true });
    fs.writeFileSync(file, JSON.stringify(doc, null, 2) + '\n', 'utf8');
    console.log(`\n저장: ${path.relative(ROOT, file)} (호출 ${calls}번)`);
  }

  // 받은 것 한눈에
  const sample = downData.fares.filter((r) => r.from === '서울' && r.to === '부산').slice(0, 3);
  if (sample.length) {
    console.log('\n서울 → 부산 (앞 3편)');
    sample.forEach((r) => console.log(
      `  ${r.grade.padEnd(12)} ${r.trainNo}  ${hhmm(r.depart)} → ${hhmm(r.arrive)}  ${r.adultCharge.toLocaleString('ko-KR')}원`));
  }
}

const hhmm = (s) => `${s.slice(8, 10)}:${s.slice(10, 12)}`;

main().catch((e) => {
  console.error('\n실패:', e.message);
  process.exit(1);
});
