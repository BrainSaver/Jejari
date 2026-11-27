// 시나리오 15: PG가 느릴 때 — 결제가 몰려도 다른 기능이 같이 멈추지 않나 (PERF, 결제)
//
// 앱 안의 MockPgClient는 0초 만에 답해서, 지금까지 잰 결제 시간에는 "PG를 기다리는 시간"이 빠져 있었다.
// 이 시나리오는 가짜 PG 서버(loadtest/mock-pg/server.js)를 실제 PG처럼 느리게 두고 두 무리를 같이 돌린다.
//
//   buyers   BUYERS명이 SPREAD_SEC 안에 도착해 로그인 → 대기열 → 배치도 → 선점 → 결제 (11번과 같은 흐름)
//            결제가 503 PAYMENT_BUSY(PG 동시 상한)면 Retry-After만큼 뒤 같은 멱등 키로 다시 (최대 PAY_RETRY번)
//   browsers BROWSERS명이 그동안 계속 열차 조회만 (역 목록 · 운행 상세) — "결제와 상관없는 사람"
//
// 핵심 지표: browsers의 응답 시간(browse_ms). 결제가 PG를 기다리며 요청 스레드(Tomcat 기본 200)를 다 잡으면 이 값이 같이 나빠진다.
//   기다리는 스레드 수 ≈ 초당 결제 수 × PG 한 건 시간. 대기열이 초당 200명까지 들여보내므로 PG가 1초면 200개가 찰 수 있다.
//
// 준비
//   1) docker compose --profile pg up -d mock-pg
//   2) 앱: ./gradlew bootRun --args="--jejari.auth.trusted-proxies=127.0.0.1,0:0:0:0:0:0:0:1 --jejari.payment.pg.mode=http"
//      상한을 켜려면 --jejari.payment.pg.max-concurrent=40 (끄면 0, 기본)
//   3) ./run.sh pgslow -e PG_PROFILE=slow -e BUYERS=400
//
// PG_PROFILE (setup에서 가짜 PG 설정을 바꿈, 비우면 그대로)
//   fast    0초 (예전 MockPgClient와 같은 조건, 비교 기준)
//   normal  0.5~1.5초, 처리 칸 200
//   slow    2~4초, 처리 칸 200
//   broken  1~3초, 처리 칸 200, 10%는 응답이 안 옴 (앱 read-timeout에서 끊김 → 결과 모름 → 마감에 스캐너가 조회)
//   down    PG 응답 장애: 승인은 처리하지만 응답이 30초 동안 안 옴 (모든 결제가 앱 read-timeout까지 스레드를 붙잡음)
//
// 끝나면: 결제 결과별 수(승인·결과 모름·실패·상한에 막혀 포기), 결제·조회 p95, 가짜 PG 통계(최대 동시·줄)를 표로,
//   results/pg-slow-result.json에도. 결과 모름은 결제 마감(+ payment.pg_timeout_sec)에 스캐너가 정리하니 정합성 점검은 그 뒤에.
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import { RUN_ID } from '../config.js';
import { identity, fakeIp, verify, signup, login, authSmokeTest, fail } from '../lib/auth.js';
import { browse, queueUntilAdmitted, queueLeave, emptySeats, hold, pay, pick, codeOf, uuid } from '../lib/journey.js';
import { safeJson } from '../lib/helpers.js';

const BUYERS = Math.min(2000, Math.max(1, Number(__ENV.BUYERS || 300)));
const SPREAD_SEC = Math.max(0, Number(__ENV.SPREAD_SEC || 10));
const BROWSERS = Math.max(0, Number(__ENV.BROWSERS || 100));
const BROWSE_THINK = Math.max(0, Number(__ENV.BROWSE_THINK || 1));
const PAY_RETRY = Math.max(0, Number(__ENV.PAY_RETRY || 5));
const MAX_WAIT_SEC = Number(__ENV.MAX_WAIT_SEC || 120);
const HOLD_RETRY = 3;
const FROM = __ENV.FROM || '3900023';
const TO = __ENV.TO || '3900114';
const PG_ADMIN = __ENV.PG_ADMIN || 'http://127.0.0.1:8090';
const PG_PROFILE = (__ENV.PG_PROFILE || '').toLowerCase();
const PROFILES = {
  fast: { latencyMinMs: 0, latencyMaxMs: 0, capacity: 200, hangRate: 0 },
  normal: { latencyMinMs: 500, latencyMaxMs: 1500, capacity: 200, hangRate: 0 },
  slow: { latencyMinMs: 2000, latencyMaxMs: 4000, capacity: 200, hangRate: 0 },
  broken: { latencyMinMs: 1000, latencyMaxMs: 3000, capacity: 200, hangRate: 0.1, hangMs: 30000 },
  down: { latencyMinMs: 0, latencyMaxMs: 0, capacity: 200, hangRate: 1, hangMs: 30000 },
};
if (PG_PROFILE && !PROFILES[PG_PROFILE]) throw new Error(`PG_PROFILE은 ${Object.keys(PROFILES).join(' · ')} 중 하나: ${PG_PROFILE}`);
// 구매자가 다 끝날 때까지 조회도 계속 (도착 + 대기열 + PG 대기 + 결제 재시도)
const DURATION_SEC = Number(__ENV.DURATION_SEC || SPREAD_SEC + 90);

const SEQ_SMOKE = 999;
const seqOf = (i) => (i >= SEQ_SMOKE ? i + 1 : i);
const cardOf = (i) => `4111${String(i).padStart(11, '0')}1`; // 사람마다 다른 카드, 끝자리 1 = 승인

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }, 503)); // 503 PAYMENT_BUSY는 앱이 판단한 거절
const journeyOk = new Rate('journey_success');
const payMs = new Trend('pay_ms', true);              // 첫 결제 요청 → 마지막 응답 (재시도 대기 포함 = 사람이 느끼는 시간)
const browseMs = new Trend('browse_ms', true);        // 조회 두 요청 합 (결제와 상관없는 사람)
const browseOk = new Rate('browse_ok');
const payApproved = new Counter('pay_approved');
const payUnknown = new Counter('pay_unknown');        // 결과 모름 (PG 응답 없음·시간 초과 → 마감에 스캐너)
const payFailed = new Counter('pay_failed');
const payBusy = new Counter('pay_busy');              // 503 PAYMENT_BUSY 받은 횟수 (재시도 포함)
const payGaveUp = new Counter('pay_busy_gave_up');    // 재시도를 다 써도 상한에 막힘
const failedAt = new Counter('journey_failed');

export const options = {
  setupTimeout: '30m',
  scenarios: {
    buyers: { executor: 'per-vu-iterations', vus: BUYERS, iterations: 1, maxDuration: `${SPREAD_SEC + MAX_WAIT_SEC + 180}s`, exec: 'buyer' },
    ...(BROWSERS > 0 ? { browsers: { executor: 'constant-vus', vus: BROWSERS, duration: `${DURATION_SEC}s`, exec: 'browser' } } : {}),
  },
  thresholds: {
    journey_success: ['rate>=0'],
    pay_ms: ['p(95)>=0'],
    browse_ms: ['p(95)>=0'],
    browse_ok: ['rate>=0'],
    'http_req_duration{name:pay}': ['p(95)>=0'],
    'http_req_duration{scenario:browsers}': ['p(95)>=0'],
    'http_req_failed{scenario:browsers}': ['rate>=0'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

function pgAdmin(method, path, body) {
  try {
    const r = http.request(method, `${PG_ADMIN}${path}`, body ? JSON.stringify(body) : null,
      { headers: { 'Content-Type': 'application/json' }, timeout: '5s', tags: { name: 'pg_admin' } });
    return r.status === 200 ? safeJson(r) : null;
  } catch (e) {
    return null;
  }
}

export function setup() {
  const tag = (__ENV.RUN_TAG || Math.random().toString(36).slice(2, 6)).toLowerCase();
  if (PG_PROFILE) {
    const c = pgAdmin('PUT', '/_config', PROFILES[PG_PROFILE]);
    if (!c) throw new Error(`가짜 PG(${PG_ADMIN})에 설정을 못 보냄. docker compose --profile pg up -d mock-pg 를 했는지, 앱이 --jejari.payment.pg.mode=http 인지 확인`);
    console.log(`가짜 PG 설정 ${PG_PROFILE}: ${JSON.stringify(c)}`);
  }
  authSmokeTest(tag);
  console.log(`PG 지연 시험: 구매자 ${BUYERS}명/${SPREAD_SEC}초 + 조회만 ${BROWSERS}명 × ${DURATION_SEC}초. 계정 ${BUYERS}개 만드는 중 (태그 ${tag})…`);
  for (let i = 0; i < BUYERS; i++) {
    const id = identity(tag, seqOf(i));
    const ip = fakeIp(100 + i);
    const v = verify(id, { ip, tag: 'setup_verify' });
    if (!v.token) fail(`계정 ${i + 1}/${BUYERS} 본인인증`, v.res);
    const s = signup(v.token, id, { ip, tag: 'setup_signup' });
    if (s.status !== 201) fail(`계정 ${i + 1}/${BUYERS} 가입`, s);
    if ((i + 1) % 100 === 0) console.log(`  ${i + 1}/${BUYERS}`);
  }
  // 사전 점검: 대기열·배치도가 열리고 빈 좌석이 구매자 수만큼 있나 (운행 방향이 FROM→TO와 반대면 배치도가 400)
  const first = identity(tag, seqOf(0));
  const l = login(first.email, first.password, { ip: fakeIp(50000), tag: 'preflight' });
  if (l.status !== 200) fail('로그인', l);
  const tk = (safeJson(l) || {}).accessToken;
  const q = queueUntilAdmitted(tk, { maxWaitSec: 30 });
  if (!q.ok) throw new Error(`사전 점검 실패(대기열): ${q.status} ${q.code}. 운행 ${RUN_ID}이 판매 중인지 (-e RUN_ID=…)`);
  const map = emptySeats(tk, q.passToken, { from: FROM, to: TO });
  queueLeave(tk);
  if (!map.seats) throw new Error(`사전 점검 실패(배치도): ${map.res.status} ${codeOf(map.res)}. FROM=${FROM}→TO=${TO}가 운행 ${RUN_ID}의 방향·정차역과 맞는지`);
  if (map.seats.length < BUYERS) console.warn(`빈 좌석 ${map.seats.length}석 < 구매자 ${BUYERS}명: 일부는 sold_out으로 끝납니다. 새 운행을 쓰세요`);
  pgAdmin('POST', '/_stats/reset');
  return { tag, profile: PG_PROFILE || '(그대로)' };
}

export function browser() {
  const t0 = Date.now();
  const ok = browse(RUN_ID, FROM, TO);
  browseMs.add(Date.now() - t0);
  browseOk.add(ok);
  sleep(BROWSE_THINK * (0.5 + Math.random()));
}

export function buyer(data) {
  const i = exec.scenario.iterationInTest; // 0..BUYERS-1 (VU 번호는 browsers와 섞여서 못 씀)
  const id = identity(data.tag, seqOf(i));
  const ip = fakeIp(60000 + i);
  sleep(Math.random() * SPREAD_SEC);
  const stop = (stage) => {
    console.warn(`멈춤: ${stage}`);
    failedAt.add(1, { stage });
    journeyOk.add(false);
  };

  let l = login(id.email, id.password, { ip, tag: 'login' });
  for (let k = 0; k < 10 && l.status === 429 && Number(l.headers['Retry-After']) > 0; k++) {
    sleep(Number(l.headers['Retry-After']) * (0.8 + Math.random() * 0.4));
    l = login(id.email, id.password, { ip, tag: 'login' });
  }
  if (!check(l, { '로그인 200': (r) => r.status === 200 })) return stop(`login_${l.status}`);
  const token = (safeJson(l) || {}).accessToken;

  const q = queueUntilAdmitted(token, { maxWaitSec: MAX_WAIT_SEC });
  if (!q.ok) return stop(`queue_${q.code || q.status}`);
  try {
    let reservationId = null;
    for (let a = 0; a < HOLD_RETRY && reservationId == null; a++) {
      const map = emptySeats(token, q.passToken, { from: FROM, to: TO });
      if (!map.seats) return stop(`seat_map_${map.res.status}`);
      if (map.seats.length === 0) return stop('sold_out');
      const h = hold(token, q.passToken, { from: FROM, to: TO, seatIds: [pick(map.seats)] });
      if (h.status === 200 || h.status === 201) reservationId = (safeJson(h) || {}).reservationId;
      else if (codeOf(h) !== 'SEAT_ALREADY_TAKEN') return stop(`hold_${codeOf(h) || h.status}`);
    }
    if (reservationId == null) return stop('hold_retry_exhausted');

    // 결제: 같은 멱등 키로 재시도 (화면의 "다시 결제"와 같음). 상한(503)일 때만 다시 보낸다
    const key = uuid();
    const t0 = Date.now();
    let p = pay(token, reservationId, { cardNumber: cardOf(i), idempotencyKey: key });
    for (let k = 0; k < PAY_RETRY && p.status === 503 && codeOf(p) === 'PAYMENT_BUSY'; k++) {
      payBusy.add(1);
      // 서버가 알려 준 Retry-After만큼 (±30% 흩뜨림, 모두 같은 순간에 다시 몰리지 않게). 없으면 1~2초
      const after = Number(p.headers['Retry-After']);
      sleep(after > 0 ? after * (0.7 + Math.random() * 0.6) : 1 + Math.random());
      p = pay(token, reservationId, { cardNumber: cardOf(i), idempotencyKey: key });
    }
    payMs.add(Date.now() - t0);
    const body = safeJson(p) || {};
    if (p.status === 503 && codeOf(p) === 'PAYMENT_BUSY') {
      payBusy.add(1);
      payGaveUp.add(1);
      return stop('pay_busy_gave_up');
    }
    if ((p.status === 200 || p.status === 201) && body.paymentStatus === 'APPROVED') {
      payApproved.add(1);
      journeyOk.add(true);
      return;
    }
    if ((p.status === 200 || p.status === 201) && body.paymentStatus === 'REQUESTED') {
      payUnknown.add(1); // 돈이 빠졌는지 모름 → 마감에 스캐너가 PG에 조회해 확정·실패 (§6.2)
      return stop('pay_unknown');
    }
    payFailed.add(1);
    return stop(`pay_${codeOf(p) || body.paymentStatus || p.status}`);
  } finally {
    queueLeave(token);
  }
}

export function teardown(data) {
  const s = pgAdmin('GET', '/_stats');
  if (s) {
    console.log(`가짜 PG: 승인 요청 ${s.approve} (승인 ${s.approved} · 거절 ${s.declined} · 모름 ${s.unknown} · 응답 늦춤 ${s.hung} · 과부하 ${s.overloaded}), `
      + `최대 동시 처리 ${s.maxInflight} · 최대 줄 ${s.maxWaiting}, PG 안에서 걸린 시간 p50 ${s.approveP50Ms}ms · p95 ${s.approveP95Ms}ms`);
  }
  return s;
}

export function handleSummary(data) {
  const v = (k, stat) => {
    const m = data.metrics[k];
    return m && m.values && m.values[stat] !== undefined ? m.values[stat] : 0;
  };
  const r = (x) => Math.round(x);
  const out = {
    profile: PG_PROFILE || '(그대로)',
    buyers: BUYERS,
    spreadSec: SPREAD_SEC,
    browsers: BROWSERS,
    journeySuccess: v('journey_success', 'rate'),
    pay: {
      approved: v('pay_approved', 'count'), unknown: v('pay_unknown', 'count'), failed: v('pay_failed', 'count'),
      busyResponses: v('pay_busy', 'count'), busyGaveUp: v('pay_busy_gave_up', 'count'),
      userMsP50: r(v('pay_ms', 'p(50)')), userMsP95: r(v('pay_ms', 'p(95)')), userMsMax: r(v('pay_ms', 'max')),
      requestMsP95: r(v('http_req_duration{name:pay}', 'p(95)')),
    },
    browse: {
      ok: v('browse_ok', 'rate'), msP50: r(v('browse_ms', 'p(50)')), msP95: r(v('browse_ms', 'p(95)')),
      msP99: r(v('browse_ms', 'p(99)')), msMax: r(v('browse_ms', 'max')),
      requestMsP95: r(v('http_req_duration{scenario:browsers}', 'p(95)')),
      errorRate: v('http_req_failed{scenario:browsers}', 'rate'),
    },
  };
  const lines = [
    '',
    `── PG 지연 시험 (PG ${out.profile}, 구매자 ${BUYERS}명/${SPREAD_SEC}초, 조회 ${BROWSERS}명) ──`,
    `예매 완료       ${(out.journeySuccess * 100).toFixed(1)}%`,
    `결제 결과       승인 ${out.pay.approved} · 결과 모름 ${out.pay.unknown} · 실패 ${out.pay.failed} · 상한에 막혀 포기 ${out.pay.busyGaveUp} (503 받은 횟수 ${out.pay.busyResponses})`,
    `결제 체감 시간  p50 ${out.pay.userMsP50}ms · p95 ${out.pay.userMsP95}ms · 최대 ${out.pay.userMsMax}ms (재시도 대기 포함)`,
    `조회 (결제 안 하는 사람)  p50 ${out.browse.msP50}ms · p95 ${out.browse.msP95}ms · p99 ${out.browse.msP99}ms · 최대 ${out.browse.msMax}ms · 오류 ${(out.browse.errorRate * 100).toFixed(2)}%`,
    '',
  ];
  return {
    stdout: lines.join('\n'),
    'results/pg-slow-result.json': JSON.stringify(out, null, 2),
  };
}
