// 시나리오 13: 용량 시험 — 동시 사용자를 단계별로 늘려 "어디까지 버티나"를 잰다 (PERF-01)
//
// 한 VU = 실제 회원 한 명 (JWT 로그인). 각 단계(STEPS)마다 STEP_SEC 동안 그 수의 사람이 아래를 반복한다.
//   열차 조회 → 대기열 진입·입장 대기 → 좌석 배치도 → (BOOK=hold면 좌석 선점 → 바로 취소) → 대기열 나가기 → 쉬기(THINK_SEC)
// 명절에 몰리는 요청 대부분이 이것(조회·대기열 폴링·배치도)이라 기본은 선점 없이 돈다. 선점까지 넣으면 같은 계정이
// 잡고 취소를 반복해 매수 제한·취소 남용 규칙에 걸릴 수 있다 (BOOK=hold는 그걸 알고 쓸 때만).
//
// 결과: 단계마다 초당 요청 · p95 · 5xx · 바퀴 실패 · 대기열 입장까지 걸린 시간을 표로, 그리고
//   "버틴 최대 단계" = p95 < P95_MS 이고 5xx < ERR_PCT% 인 가장 높은 단계. capacity-result.json에도 남긴다.
//   그 단계의 초당 요청이 이 서버가 감당하는 양 → 대기열 입장 속도(queue.throughput)·요청 제한을 이 숫자로 정한다.
//
// 안전장치 (공용 서버를 쓰러뜨리지 않게): 어느 단계든 5xx가 ABORT_ERR_PCT%(기본 20)를 넘거나 p95가 ABORT_P95_MS(기본 5초)를
//   넘으면 20초 지켜본 뒤 바로 멈춘다. 그 단계가 곧 한계라 더 올릴 이유가 없다.
//
// 공용 서버에서 돌릴 때는 반드시 docs/강동균/용량-시험-안내.md 순서대로 (시간 정하기 · 가입/로그인 한도 잠깐 올리기 · 끝나면 되돌리기).
//   BASE_URL이 내부 주소가 아니면 -e I_OWN_THIS_TARGET=yes 없이는 돌지 않는다 (config.js).
//
// 로컬: ./gradlew bootRun --args="--jejari.auth.trusted-proxies=127.0.0.1,0:0:0:0:0:0:0:1"  (개발 모드 아님, JWT)
//       ./run.sh capacity -e STEPS=10,25,50,100 -e STEP_SEC=60
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Rate, Trend, Counter } from 'k6/metrics';
import { RUN_ID, BASE_URL } from '../config.js';
import { identity, fakeIp, verify, signup, login, authSmokeTest, fail } from '../lib/auth.js';
import { browse, queueUntilAdmitted, queueLeave, emptySeats, hold, cancel, pick, codeOf } from '../lib/journey.js';
import { safeJson } from '../lib/helpers.js';

const STEPS = (__ENV.STEPS || '10,25,50,100,200').split(',').map((s) => Math.max(1, Number(s.trim()))).filter(Boolean);
const STEP_SEC = Math.max(30, Number(__ENV.STEP_SEC || 90));
const THINK_SEC = Math.max(0, Number(__ENV.THINK_SEC || 3));       // 한 바퀴 뒤 쉬는 시간 (사람처럼)
// 단계가 오를 때 새로 들어온 사람들의 첫 로그인을 이 시간 안에 흩뜨린다. 0이면 전원이 같은 순간에 로그인 (로그인 폭주 시험).
// 로그인은 Argon2id(일부러 느림)라 한꺼번에 몰리면 그것만으로 서버가 멈출 수 있다 — 두 경우를 따로 재려고
const LOGIN_SPREAD_SEC = Math.max(0, Number(__ENV.LOGIN_SPREAD_SEC ?? 30));
const QUEUE_WAIT_SEC = Math.max(5, Number(__ENV.QUEUE_WAIT_SEC || 60));
const BOOK = (__ENV.BOOK || 'no') === 'hold';
const FROM = __ENV.FROM || '3900023';                               // 서울 (시드)
const TO = __ENV.TO || '3900114';                                   // 부산 (시드)
const P95_MS = Number(__ENV.P95_MS || 1000);                        // 버틴다고 볼 응답 시간 (A-10 "주의" 문턱과 같음)
const ERR_PCT = Number(__ENV.ERR_PCT || 1);                         // 버틴다고 볼 5xx 비율 (A-10 "주의" 문턱과 같음)
const ABORT_ERR_PCT = Number(__ENV.ABORT_ERR_PCT || 20);
const ABORT_P95_MS = Number(__ENV.ABORT_P95_MS || 5000);
const MAX_USERS = Math.max(...STEPS);
if (MAX_USERS > 2000) throw new Error('STEPS의 가장 큰 값은 2000 이하로 (계정 만들기가 너무 오래 걸림)');

const SEQ_SMOKE = 999;
const seqOf = (i) => (i >= SEQ_SMOKE ? i + 1 : i);
const stepName = (n) => `step_${String(n).padStart(4, '0')}`;

// 서버 오류 = 모든 요청 중 5xx 또는 응답 없음(시간 초과·연결 끊김). 4xx(대기열에 없음 404, 경합 409, 요청 제한 429)는
// 서버가 제대로 판단해 돌려준 것이라 실패로 세지 않는다 → k6 기본 지표 http_req_failed를 그렇게 쓴다
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));
const roundOk = new Rate('round_ok');             // 한 바퀴를 끝까지 마쳤나 (사용자 체감)
const rounds = new Counter('rounds');              // 한 바퀴를 끝낸 수
const queueWait = new Trend('queue_wait_sec');
const roundFail = new Counter('round_failed');

// 단계마다 따로 잰다: 단계 = k6 시나리오 하나 (startTime을 이어 붙임). 태그 scenario로 단계별 값이 나뉜다
const scenarios = {};
const thresholds = {};
STEPS.forEach((n, i) => {
  const name = stepName(n);
  scenarios[name] = { executor: 'constant-vus', vus: n, duration: `${STEP_SEC}s`, startTime: `${i * STEP_SEC}s`, gracefulStop: '10s' };
  // 판정용(넘어도 계속) + 안전장치(넘으면 멈춤). 하위 지표에 문턱이 있어야 요약에 단계별 값이 나온다
  thresholds[`http_req_duration{scenario:${name}}`] = [
    `p(95)<${P95_MS}`,
    { threshold: `p(95)<${ABORT_P95_MS}`, abortOnFail: true, delayAbortEval: '20s' },
  ];
  thresholds[`http_req_failed{scenario:${name}}`] = [
    `rate<${ERR_PCT / 100}`,
    { threshold: `rate<${ABORT_ERR_PCT / 100}`, abortOnFail: true, delayAbortEval: '20s' },
  ];
  thresholds[`round_ok{scenario:${name}}`] = ['rate>=0'];
  thresholds[`http_reqs{scenario:${name}}`] = ['count>=0'];
  thresholds[`rounds{scenario:${name}}`] = ['count>=0'];
  thresholds[`queue_wait_sec{scenario:${name}}`] = ['avg>=0'];
});

export const options = {
  setupTimeout: '30m', // 계정 만들기 (Argon2id라 한 명에 수백 ms)
  scenarios,
  thresholds,
  summaryTrendStats: ['avg', 'p(95)', 'max'],
};

export function setup() {
  const tag = (__ENV.RUN_TAG || Math.random().toString(36).slice(2, 6)).toLowerCase();
  authSmokeTest(tag);
  console.log(`용량 시험: 단계 ${STEPS.join(' → ')}명 × ${STEP_SEC}초, 첫 로그인 ${LOGIN_SPREAD_SEC}초에 흩뜨림, 쉬기 ${THINK_SEC}초, 선점 ${BOOK ? '함(잡고 바로 취소)' : '안 함'} → ${BASE_URL}`);
  console.log(`계정 ${MAX_USERS}개를 만듭니다 (실행 태그 ${tag})…`);
  const t0 = Date.now();
  for (let i = 0; i < MAX_USERS; i++) {
    const id = identity(tag, seqOf(i));
    const ip = fakeIp(100 + i);
    const v = verify(id, { ip, tag: 'setup_verify' });
    if (!v.token) fail(`계정 ${i + 1}/${MAX_USERS} 본인인증`, v.res);
    const s = signup(v.token, id, { ip, tag: 'setup_signup' });
    if (s.status !== 201) fail(`계정 ${i + 1}/${MAX_USERS} 가입`, s);
    if ((i + 1) % 100 === 0) console.log(`  ${i + 1}/${MAX_USERS} (${Math.round((Date.now() - t0) / 1000)}초)`);
  }
  // 대기열·배치도가 열리는지 한 번 (판매 중이 아니면 모든 VU가 같은 이유로 실패)
  const first = identity(tag, seqOf(0));
  const l = login(first.email, first.password, { ip: fakeIp(50000), tag: 'preflight' });
  if (l.status !== 200) fail('로그인', l);
  const token = (safeJson(l) || {}).accessToken;
  const q = queueUntilAdmitted(token, { maxWaitSec: 30 });
  if (!q.ok) throw new Error(`사전 점검 실패(대기열): ${q.status} ${q.code}. 운행 ${RUN_ID}이 판매 중인지 확인하세요 (-e RUN_ID=…)`);
  const map = emptySeats(token, q.passToken, { from: FROM, to: TO });
  queueLeave(token);
  if (!map.seats) throw new Error(`사전 점검 실패(좌석 배치도): ${map.res.status} ${codeOf(map.res)}. FROM·TO가 운행 ${RUN_ID}의 정차역인지 확인하세요`);
  return { tag, startedAt: new Date().toISOString() };
}

// VU마다 로그인 토큰을 들고 다닌다. 만료(15분)·401이면 다시 로그인
let token = null;
let spread = false;
function ensureLogin(data, i) {
  if (token) return token;
  if (!spread) {
    spread = true;
    sleep(Math.random() * LOGIN_SPREAD_SEC);
  }
  const id = identity(data.tag, seqOf(i));
  const l = login(id.email, id.password, { ip: fakeIp(60000 + i), tag: 'login' });
  token = l.status === 200 ? (safeJson(l) || {}).accessToken : null;
  return token;
}

export default function (data) {
  const i = (exec.vu.idInTest - 1) % MAX_USERS; // 단계가 바뀌어도 같은 VU 번호면 같은 계정
  const t = ensureLogin(data, i);
  if (!t) {
    roundOk.add(false);
    roundFail.add(1, { stage: 'login' });
    sleep(THINK_SEC + 1);
    return;
  }
  const done = (ok, stage) => {
    roundOk.add(ok);
    if (ok) rounds.add(1);
    else roundFail.add(1, { stage });
    sleep(THINK_SEC * (0.5 + Math.random()));
  };

  if (!browse(RUN_ID, FROM, TO)) return done(false, 'browse');

  const q = queueUntilAdmitted(t, { maxWaitSec: QUEUE_WAIT_SEC });
  queueWait.add(q.waitedSec);
  if (q.status === 401) {
    token = null; // 토큰 만료 → 다음 바퀴에 다시 로그인
    return done(false, 'expired');
  }
  if (!q.ok) {
    queueLeave(t);
    return done(false, `queue_${q.code || q.status}`);
  }
  try {
    const map = emptySeats(t, q.passToken, { from: FROM, to: TO });
    if (!check(map, { '좌석 배치도': (m) => !!m.seats })) return done(false, `seat_map_${map.res.status}`);
    if (BOOK && map.seats.length > 0) {
      const h = hold(t, q.passToken, { from: FROM, to: TO, seatIds: [pick(map.seats)] });
      const rid = (safeJson(h) || {}).reservationId;
      if (rid) cancel(t, rid); // 좌석이 바닥나지 않게 바로 돌려 놓음
    }
    done(true);
  } finally {
    queueLeave(t);
  }
}

// ── 결과: 단계별 표 + 버틴 최대 단계 ────────────────────────────────
export function handleSummary(data) {
  const m = (k, stat) => {
    const x = data.metrics[k];
    return x && x.values ? x.values[stat] : undefined;
  };
  const rows = STEPS.map((n) => {
    const s = stepName(n);
    const reqs = m(`http_reqs{scenario:${s}}`, 'count') || 0;
    const p95 = m(`http_req_duration{scenario:${s}}`, 'p(95)');
    const err = m(`http_req_failed{scenario:${s}}`, 'rate');
    const okRate = m(`round_ok{scenario:${s}}`, 'rate');
    const done = m(`rounds{scenario:${s}}`, 'count') || 0;
    const wait = m(`queue_wait_sec{scenario:${s}}`, 'avg');
    const ran = reqs > 0;
    if (!ran) {
      return { users: n, ran, ok: false, rps: null, p95Ms: null, errPct: null, roundFailPct: null, roundsPerSec: null, queueWaitSec: null };
    }
    const ok = ran && p95 != null && p95 < P95_MS && (err || 0) * 100 < ERR_PCT;
    return { users: n, ran, ok, rps: reqs / STEP_SEC, p95Ms: p95, errPct: err == null ? null : err * 100,
      roundFailPct: okRate == null ? null : (1 - okRate) * 100, roundsPerSec: done / STEP_SEC, queueWaitSec: wait };
  });
  const passed = rows.filter((r) => r.ok);
  const best = passed.length ? passed[passed.length - 1] : null;
  const firstFail = rows.find((r) => r.ran && !r.ok);
  const f = (v, d = 0) => (v == null ? '—' : Number(v).toFixed(d));
  let text = '\n용량 시험 결과 (' + BASE_URL + ')\n';
  text += '  사용자 |  초당 요청 |   p95(ms) |  5xx(%) | 바퀴 실패(%) | 바퀴/초 | 입장 대기(초) | 판정\n';
  for (const r of rows) {
    text += `  ${String(r.users).padStart(6)} | ${f(r.rps, 1).padStart(10)} | ${f(r.p95Ms).padStart(9)} | ${f(r.errPct, 2).padStart(7)} | `
      + `${f(r.roundFailPct, 1).padStart(12)} | ${f(r.roundsPerSec, 1).padStart(7)} | ${f(r.queueWaitSec, 1).padStart(13)} | `
      + `${!r.ran ? '안 돎(앞에서 멈춤)' : r.ok ? '버팀' : '넘음'}\n`;
  }
  if (!rows.some((r) => r.ran)) {
    text += '\n시험이 시작되지 않았습니다 — 준비 단계(계정 만들기·사전 점검)에서 멈춤. 위의 오류를 보세요\n';
    return { stdout: text, 'capacity-result.json': JSON.stringify({ target: BASE_URL, error: 'setup_failed' }, null, 1) };
  }
  text += best
    ? `\n버틴 최대 단계: 동시 ${best.users}명 · 초당 ${f(best.rps, 1)}건 (p95 ${f(best.p95Ms)}ms, 5xx ${f(best.errPct, 2)}%)\n`
    : '\n버틴 단계가 없습니다 (첫 단계부터 기준을 넘음)\n';
  if (firstFail) {
    text += `처음 넘은 단계: 동시 ${firstFail.users}명 (p95 ${f(firstFail.p95Ms)}ms, 5xx ${f(firstFail.errPct, 2)}%) — 이 사이가 한계\n`;
  }
  text += `기준: p95 < ${P95_MS}ms, 5xx < ${ERR_PCT}% (A-10 "주의" 문턱과 같음)\n`;
  const result = { target: BASE_URL, startedAt: data.setup_data ? data.setup_data.startedAt : null, stepSec: STEP_SEC,
    loginSpreadSec: LOGIN_SPREAD_SEC,
    thinkSec: THINK_SEC, book: BOOK, criteria: { p95Ms: P95_MS, errPct: ERR_PCT }, steps: rows,
    best: best ? { users: best.users, rps: best.rps } : null, firstFail: firstFail ? { users: firstFail.users } : null };
  return { stdout: text, 'capacity-result.json': JSON.stringify(result, null, 1) };
}
