// 시나리오 12: 봇 탐지 평가 — 실제 앱 (DATA-03 봇 쪽, RISK-02·04의 탐지율·오탐률)
//
// 정상 사용자와 봇 4종을 같이 돌리고, 끝난 뒤 tools/eval_risk.sh가 정답 라벨(synthetic_label)과
// 서버의 판정(suspicious_case)을 비교해 탐지율·오탐률을 낸다. 07(Mock 서버용)의 실제 앱 버전이다.
//
//   종류(이메일에 표시)  정답 라벨        행동                                         걸려야 하는 규칙
//   normal              NORMAL          사람 속도로 검색 → 대기열 → 좌석 → 결제, 일부는 1번 취소   없음 (오탐 측정)
//   fast                MACRO           운행 검색을 20~60ms 간격으로 연타한 뒤 예매                FAST_CLICK
//   retry               MACRO           이미 팔린 좌석 하나를 0.3~0.6초 간격으로 반복 선점         SAME_SEAT_RETRY
//   multi               RESELLER        한 기기(X-Device-Id)로 계정 3개가 몇 초 안에 각자 선점     MULTI_ACCOUNT_SYNC (기기)
//   cancel              CANCEL_ABUSER   결제하고 1~3초 뒤 취소를 3번                              CANCEL_AFTER_PAY
//   steady              MACRO           운행 검색을 정확히 일정한 간격(기본 500ms)으로 40번       REGULAR_INTERVAL (보조 규칙)
//
// 경계 측정 (봇 강도를 여러 단계로 섞기): *_LEVELS에 쉼표로 강도를 주면 봇마다 돌아가며 배정하고,
// 이메일의 g{강도}에 남겨 eval_risk.sh가 강도별 탐지율 표를 낸다. 한 번 실행으로 "몇 번부터 잡히나"가 보인다.
//   FAST_LEVELS=10,15,19,20,21,25,50   RETRY_LEVELS=3,4,5,6,8   CANCEL_LEVELS=1,2,3   MULTI_SIZES=2,3   STEADY_LEVELS=300,500,1000
//   또는 BOUNDARY=1 (위 값 + 강도마다 3명, 정상 사용자 20명)
//
// 봇마다 규칙 하나만 건드리게 만들었다 (fast는 느리게 선점, retry는 느리게 반복). 그래야 어느 규칙이 놓쳤는지 보인다.
// 모든 회원은 서로 다른 카드·기기를 쓴다 (multi 묶음만 기기 공유). 카드를 같이 쓰면 결제 수단 점수가 전원에게 번진다.
//
// 라벨은 서버로 보내지 않는다. 이메일 아이디에 종류를 넣어 두고(lt{tag}.{종류}.g{묶음}.{번호}@loadtest.example.com)
// 평가 스크립트가 Oracle에서 읽어 synthetic_label에 넣는다. 앱 코드는 이 표시를 보지 않는다.
//
// 준비: 시나리오 11과 같음 (docker compose up -d, .env 비밀값 3종, 앱을 신뢰 프록시 옵션으로 실행)
//   ./run.sh 12                       (또는 ./run.sh botdetect -e NORMAL=80)
//   끝나면 출력 마지막의 안내대로:  ./tools/eval_risk.sh <tag>     (1분 배치를 기다렸다가 계산)
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';
import { RUN_ID } from '../config.js';
import { identity, fakeIp, verify, signup, login, authSmokeTest, fail, EMAIL_DOMAIN } from '../lib/auth.js';
import {
  setDevice, browse, runDate, search, queueUntilAdmitted, queueLeave, emptySeats, hold, pay, cancel, pick, codeOf, uuid,
} from '../lib/journey.js';
import { safeJson } from '../lib/helpers.js';

const BOUNDARY = __ENV.BOUNDARY === '1';
// 경계 측정 기본값: 강도마다 PER_LEVEL명
const B = BOUNDARY
  ? { FAST_LEVELS: '10,15,19,20,21,25,50', RETRY_LEVELS: '3,4,5,6,8', CANCEL_LEVELS: '1,2,3', MULTI_SIZES: '2,3',
      STEADY_LEVELS: '300,500,1000', ROTATE_LEVELS: '2,3,4', NORMAL: 20 }
  : {};
const env = (k, d) => __ENV[k] ?? B[k] ?? d;
const n = (k, d, max = 2000) => Math.min(max, Math.max(0, Number(env(k, d))));
/** "10,20,30" → [10,20,30]. 비었거나 잘못되면 기본값 하나 */
const levels = (k, d, min, max) => {
  const out = String(env(k, d)).split(',').map((s) => Math.round(Number(s.trim())))
    .filter((v) => Number.isFinite(v) && v >= min && v <= max);
  return out.length ? out : [Number(d)];
};
const PER_LEVEL = n('PER_LEVEL', 3, 100);
const FAST_CLICKS = n('FAST_CLICKS', 50, 500);           // FAST_CLICK 문턱: 200ms 미만 간격 20회(MID)·60회(HIGH)
const RETRY_TIMES = n('RETRY_TIMES', 8, 100);             // SAME_SEAT_RETRY 문턱: 같은 좌석 실패 5회(MID)·15회(HIGH)
const CANCEL_ROUNDS = n('CANCEL_ROUNDS', 3, 9);           // CANCEL_AFTER_PAY 문턱: 구매 후 30분 안 취소 2건(MID)·5건(HIGH)
const FAST_LEVELS = levels('FAST_LEVELS', FAST_CLICKS, 1, 500);
const RETRY_LEVELS = levels('RETRY_LEVELS', RETRY_TIMES, 1, 100);
const CANCEL_LEVELS = levels('CANCEL_LEVELS', CANCEL_ROUNDS, 1, 9);
const MULTI_SIZES = levels('MULTI_SIZES', n('MULTI_SIZE', 3, 10), 2, 10);
const STEADY_LEVELS = levels('STEADY_LEVELS', 500, 200, 10000); // 간격 ms. 200 미만은 FAST_CLICK과 섞여 제외
const STEADY_COUNT = n('STEADY_COUNT', 40, 500);                // REGULAR_INTERVAL: 간격 30건 이상, 변동계수 0.05 이하
// 인원: 강도를 여러 개 주면 강도마다 PER_LEVEL명, 아니면 예전처럼 직접
const count = (k, d, lv) => n(k, lv.length > 1 ? lv.length * PER_LEVEL : d);
const NORMAL = n('NORMAL', 40);
const FAST = count('FAST', 5, FAST_LEVELS);
const RETRY = count('RETRY', 5, RETRY_LEVELS);
const MULTI_GROUPS = count('MULTI_GROUPS', 3, MULTI_SIZES);
const CANCEL = count('CANCEL', 5, CANCEL_LEVELS);
const STEADY = n('STEADY', BOUNDARY || __ENV.STEADY_LEVELS ? STEADY_LEVELS.length * PER_LEVEL : 0);
// 좌석을 돌려 가며 문턱 아래로 시도하는 봇 (SAME_SEAT_RETRY "여러 좌석 합산" 확인용). 강도 = 좌석마다 시도 수, 좌석 ROTATE_SEATS개
const ROTATE_LEVELS = levels('ROTATE_LEVELS', 4, 1, 50);
const ROTATE_SEATS = n('ROTATE_SEATS', 3, 9);
const ROTATE = n('ROTATE', BOUNDARY || __ENV.ROTATE_LEVELS ? ROTATE_LEVELS.length * PER_LEVEL : 0);
const SPREAD_SEC = n('SPREAD_SEC', 30, 600);
const pickLevel = (lv, i) => lv[i % lv.length];
const NORMAL_CANCEL_RATE = Number(__ENV.NORMAL_CANCEL_RATE ?? 0.2); // 정상 사용자도 마음이 바뀌면 1번은 취소한다 (문턱 2건 아래)
const FROM = __ENV.FROM || '3900023';
const TO = __ENV.TO || '3900114';
const MAX_WAIT_SEC = n('MAX_WAIT_SEC', 180, 1800);

const KINDS = ['normal', 'fast', 'retry', 'multi', 'cancel', 'steady', 'rotate'];
const flowOk = new Rate('bot_flow_ok');        // 각 사용자가 의도한 행동을 끝까지 했는지 (kind 태그). 낮으면 평가를 믿을 수 없음
const fastClicks = new Counter('fast_clicks'); // 실제로 보낸 연타 수
const retry409 = new Counter('retry_409');     // 반복 선점이 실제로 409를 받은 수 (좌석이 안 팔려 있으면 0)
const cancelled = new Counter('cancel_after_pay');

const rand = (a, b) => a + Math.random() * (b - a);
const think = (a, b) => sleep(rand(a, b));

// 시나리오별 VU = 그 종류의 계정 수. VU 하나가 회원 하나를 한 번 끝까지 연기한다
const group = (execName, vus) => ({
  executor: 'per-vu-iterations', exec: execName, vus: Math.max(1, vus), iterations: 1,
  maxDuration: `${SPREAD_SEC + MAX_WAIT_SEC + 300}s`,
});
const scenarios = {};
if (NORMAL) scenarios.normal = group('normalUser', NORMAL);
if (FAST) scenarios.fast = group('fastClicker', FAST);
if (RETRY) scenarios.retry = group('seatRetrier', RETRY);
const MULTI_TOTAL = Array.from({ length: MULTI_GROUPS }, (_, g) => pickLevel(MULTI_SIZES, g)).reduce((a, b) => a + b, 0);
if (MULTI_TOTAL) scenarios.multi = group('multiAccount', MULTI_TOTAL);
if (CANCEL) scenarios.cancel = group('cancelAbuser', CANCEL);
if (STEADY) scenarios.steady = group('steadyMacro', STEADY);
if (ROTATE) scenarios.rotate = group('rotateRetrier', ROTATE);

export const options = {
  setupTimeout: '20m',
  scenarios,
  thresholds: {
    // 봇이 행동을 못 하면(대기열·좌석 문제) 탐지율이 낮게 나와도 서버 탓이 아니다 → 먼저 이것부터 본다
    bot_flow_ok: ['rate>=0.9'],
  },
};

// ── 계정 ──────────────────────────────────────────────────────

function tagNum(tag) {
  return Number.parseInt(tag, 36) % 1000;
}

/** 종류·묶음·번호 → 계정. 이메일 아이디에 종류를 넣는다 (평가 스크립트가 읽는 정답) */
function account(tag, kind, grp, seq) {
  const id = identity(tag, seq);
  id.email = `lt${tag}.${kind}.g${grp}.${seq}@${EMAIL_DOMAIN}`;
  // 카드 13자리, 사람마다 다르게. 끝자리 1 = 모의 PG 승인 (0 거절, 8 결과 모름)
  id.card = `4${String(tagNum(tag)).padStart(3, '0')}${String(seq).padStart(8, '0')}1`;
  id.ip = fakeIp(200000 + seq);
  return id;
}

/** 이 실행에서 만들 계정 목록. seq는 0부터, authSmokeTest의 999는 건너뛴다 */
function plan(tag) {
  const out = [];
  let seq = 0;
  const next = () => (seq === 999 ? (seq += 2) - 1 : seq++);
  const add = (kind, count, grpOf = () => 0) => {
    for (let i = 0; i < count; i++) out.push({ kind, i, ...account(tag, kind, grpOf(i), next()), grp: grpOf(i) });
  };
  // 봇은 묶음 번호(g) 자리에 강도를 적는다 → eval_risk.sh가 강도별로 셈. multi만 g = 묶음 번호
  const multiGroupOf = [];
  for (let g = 0; g < MULTI_GROUPS; g++) for (let k = 0; k < pickLevel(MULTI_SIZES, g); k++) multiGroupOf.push(g);
  add('normal', NORMAL);
  add('fast', FAST, (i) => pickLevel(FAST_LEVELS, i));
  add('retry', RETRY, (i) => pickLevel(RETRY_LEVELS, i));
  add('multi', multiGroupOf.length, (i) => multiGroupOf[i]);
  add('cancel', CANCEL, (i) => pickLevel(CANCEL_LEVELS, i));
  add('steady', STEADY, (i) => pickLevel(STEADY_LEVELS, i));
  add('rotate', ROTATE, (i) => pickLevel(ROTATE_LEVELS, i));
  add('decoy', 1); // 반복 선점용 좌석을 미리 사 두는 도우미. 평가에서 뺀다
  return out;
}

export function setup() {
  const tag = (__ENV.RUN_TAG || Math.random().toString(36).slice(2, 6)).toLowerCase();
  authSmokeTest(tag);

  const users = plan(tag);
  users.forEach((u, k) => {
    const v = verify(u, { ip: u.ip, tag: 'setup_verify' });
    if (!v.token) fail(`계정 ${k + 1}/${users.length} 본인인증`, v.res);
    const s = signup(v.token, u, { ip: u.ip, tag: 'setup_signup' });
    if (s.status !== 201) fail(`계정 ${k + 1}/${users.length} 가입`, s);
  });

  // 도우미가 좌석 하나를 사 둔다 → retry 봇이 이 좌석을 반복해서 잡으려다 409를 받는다
  const decoy = users[users.length - 1];
  setDevice(`ltdev${tag}decoy`);
  const token = loginOrFail(decoy, 'preflight');
  const q = queueUntilAdmitted(token, { maxWaitSec: 30 });
  if (!q.ok) throw new Error(`사전 점검 실패(대기열): ${q.status} ${q.code}. 운행 ${RUN_ID}이 판매 중인지, 다른 부하가 돌고 있지 않은지 확인하세요.`);
  const map = emptySeats(token, q.passToken, { from: FROM, to: TO });
  if (!map.seats || map.seats.length === 0) {
    throw new Error(`사전 점검 실패(좌석 배치도): ${map.res.status} ${codeOf(map.res)} 빈 좌석 ${map.seats ? 0 : '?'}석. Oracle 볼륨을 초기화하세요.`);
  }
  const need = NORMAL + FAST + MULTI_TOTAL + CANCEL * Math.max(...CANCEL_LEVELS) + 1;
  if (map.seats.length < need) console.warn(`빈 좌석 ${map.seats.length}석 < 필요 ${need}석: 일부 사용자가 좌석을 못 잡습니다.`);
  // 좌석 1 + ROTATE_SEATS개를 한 번에 산다 (retry 봇은 첫 좌석, rotate 봇은 나머지를 돌아가며)
  const bought = map.seats.slice().sort(() => Math.random() - 0.5).slice(0, 1 + (ROTATE ? ROTATE_SEATS : 0));
  const targetSeat = bought[0];
  const rotateSeats = bought.slice(1);
  const h = hold(token, q.passToken, { from: FROM, to: TO, seatIds: bought });
  const rid = (safeJson(h) || {}).reservationId;
  const p = rid ? pay(token, rid, { cardNumber: decoy.card }) : h;
  queueLeave(token);
  if (!(p.status === 201 || p.status === 200)) fail('도우미 좌석 구매', p);
  setDevice(null);

  const date = runDate(RUN_ID, FROM, TO);
  if (!date) throw new Error(`운행 ${RUN_ID} 상세를 읽지 못했습니다 (FROM=${FROM}, TO=${TO}).`);
  return { tag, users, targetSeat, rotateSeats, date };
}

// ── 공통 동작 ─────────────────────────────────────────────────

function loginOrFail(u, tag = 'login') {
  const l = login(u.email, u.password, { ip: u.ip, tag });
  if (l.status !== 200) fail(`로그인 ${u.email.split('@')[0]}`, l);
  return (safeJson(l) || {}).accessToken;
}

function me(data, kind) {
  const idx = exec.scenario.iterationInTest; // 시나리오 안에서 0부터. VU당 1번이라 계정 번호로 씀
  return data.users.filter((u) => u.kind === kind)[idx];
}

/** 빈 좌석 하나를 잡아 결제까지. 정상 경합(이미 잡힘)은 다른 좌석으로 2번 더. 성공하면 예약 ID */
function buyOne(u, token, passToken, { slow = true } = {}) {
  for (let attempt = 0; attempt < 3; attempt++) {
    const map = emptySeats(token, passToken, { from: FROM, to: TO });
    if (!map.seats || map.seats.length === 0) return null;
    if (slow) think(1, 3); // 좌석 고르는 시간
    const h = hold(token, passToken, { from: FROM, to: TO, seatIds: [pick(map.seats)] });
    if (h.status === 200 || h.status === 201) {
      const rid = (safeJson(h) || {}).reservationId;
      if (slow) think(2, 5); // 카드 입력
      const p = pay(token, rid, { cardNumber: u.card, idempotencyKey: uuid() });
      const body = safeJson(p) || {};
      return (p.status === 201 || p.status === 200) && body.paymentStatus === 'APPROVED' ? rid : null;
    }
    if (codeOf(h) !== 'SEAT_ALREADY_TAKEN') return null;
    if (slow) think(1, 2);
  }
  return null;
}

function done(kind, ok) {
  flowOk.add(ok, { kind });
}

// ── 정상 사용자 ───────────────────────────────────────────────
export function normalUser(data) {
  const u = me(data, 'normal');
  setDevice(`ltdev${data.tag}n${u.i}`);
  sleep(Math.random() * SPREAD_SEC);
  const token = loginOrFail(u);
  think(1, 3);
  search(token, FROM, TO, data.date);
  think(2, 5);
  if (Math.random() < 0.5) {
    search(token, FROM, TO, data.date); // 다시 한 번 비교해 봄
    think(1, 4);
  }
  browse(RUN_ID, FROM, TO);
  const q = queueUntilAdmitted(token, { maxWaitSec: MAX_WAIT_SEC });
  if (!q.ok) return done('normal', false);
  try {
    const rid = buyOne(u, token, q.passToken);
    if (rid && Math.random() < NORMAL_CANCEL_RATE) {
      think(5, 15); // 일정이 바뀌어 취소 (한 번뿐)
      cancel(token, rid);
    }
    done('normal', rid != null);
  } finally {
    queueLeave(token);
  }
}

// ── 봇 1: 운행 검색 연타 (FAST_CLICK) ─────────────────────────
export function fastClicker(data) {
  const u = me(data, 'fast');
  setDevice(`ltdev${data.tag}f${u.i}`);
  sleep(Math.random() * SPREAD_SEC);
  const token = loginOrFail(u);
  let sent = 0;
  const clicks = u.grp; // 강도 (FAST_LEVELS)
  for (let i = 0; i < clicks; i++) {
    if (search(token, FROM, TO, data.date, 'run_search_bot').status === 200) sent++;
    sleep(rand(0.02, 0.06));
  }
  fastClicks.add(sent);
  sleep(1); // 연타 뒤 대기열 진입까지는 사람 속도로 (연타 수만 정확히 재려고)
  const q = queueUntilAdmitted(token, { maxWaitSec: MAX_WAIT_SEC });
  if (!q.ok) return done('fast', false);
  try {
    buyOne(u, token, q.passToken); // 사는 것도 사람 속도로 (이 봇은 연타 하나만 보이게)
    done('fast', sent >= clicks * 0.9);
  } finally {
    queueLeave(token);
  }
}

// ── 봇 2: 이미 팔린 좌석 반복 선점 (SAME_SEAT_RETRY) ──────────
export function seatRetrier(data) {
  const u = me(data, 'retry');
  setDevice(`ltdev${data.tag}r${u.i}`);
  sleep(Math.random() * SPREAD_SEC);
  const token = loginOrFail(u);
  const q = queueUntilAdmitted(token, { maxWaitSec: MAX_WAIT_SEC });
  if (!q.ok) return done('retry', false);
  try {
    let conflicts = 0;
    const times = u.grp; // 강도 (RETRY_LEVELS)
    for (let i = 0; i < times; i++) {
      const h = hold(token, q.passToken, { from: FROM, to: TO, seatIds: [data.targetSeat] });
      if (h.status === 409) conflicts++;
      sleep(rand(0.3, 0.6)); // 200ms 넘게: 연타 규칙은 건드리지 않음
    }
    retry409.add(conflicts);
    done('retry', conflicts >= times - 1);
  } finally {
    queueLeave(token);
  }
}

// ── 봇 3: 한 기기 다계정 동시 선점 (MULTI_ACCOUNT_SYNC) ───────
export function multiAccount(data) {
  const u = me(data, 'multi');
  setDevice(`ltdev${data.tag}m${u.grp}`); // 같은 묶음은 같은 기기
  // 묶음마다 출발 시각을 정하고, 묶음 안의 계정은 3초 안에 같이 움직인다
  sleep(((u.grp * 7919) % 1000) / 1000 * SPREAD_SEC + Math.random() * 3);
  const token = loginOrFail(u);
  const q = queueUntilAdmitted(token, { maxWaitSec: MAX_WAIT_SEC });
  if (!q.ok) return done('multi', false);
  try {
    done('multi', buyOne(u, token, q.passToken, { slow: false }) != null);
  } finally {
    queueLeave(token);
  }
}

// ── 봇 4: 결제 직후 취소 반복 (CANCEL_AFTER_PAY) ──────────────
export function cancelAbuser(data) {
  const u = me(data, 'cancel');
  setDevice(`ltdev${data.tag}c${u.i}`);
  sleep(Math.random() * SPREAD_SEC);
  const token = loginOrFail(u);
  const q = queueUntilAdmitted(token, { maxWaitSec: MAX_WAIT_SEC });
  if (!q.ok) return done('cancel', false);
  try {
    let ok = 0;
    const rounds = u.grp; // 강도 (CANCEL_LEVELS)
    for (let r = 0; r < rounds; r++) {
      const rid = buyOne(u, token, q.passToken);
      if (rid == null) continue;
      sleep(rand(1, 3));
      if (check(cancel(token, rid), { '취소 200': (res) => res.status === 200 })) ok++;
      sleep(rand(0.5, 1));
    }
    cancelled.add(ok);
    done('cancel', ok >= rounds);
  } finally {
    queueLeave(token);
  }
}

// ── 봇 6: 좌석을 돌려 가며 문턱 아래로 반복 (SAME_SEAT_RETRY "여러 좌석 합산") ──
// 한 좌석에는 문턱(5) 아래로만 시도하고 좌석을 바꿔 가며 반복 → 한 좌석 최댓값으로는 안 걸림.
// 규칙은 좌석마다 "두 번째 실패부터"를 합산하므로 좌석 3개 × 4번이면 합 9로 걸려야 한다
export function rotateRetrier(data) {
  const u = me(data, 'rotate');
  setDevice(`ltdev${data.tag}t${u.i}`);
  sleep(Math.random() * SPREAD_SEC);
  const token = loginOrFail(u);
  const q = queueUntilAdmitted(token, { maxWaitSec: MAX_WAIT_SEC });
  if (!q.ok) return done('rotate', false);
  try {
    const perSeat = u.grp; // 강도 (ROTATE_LEVELS)
    let conflicts = 0;
    for (let round = 0; round < perSeat; round++) {
      for (const seat of data.rotateSeats) {
        const h = hold(token, q.passToken, { from: FROM, to: TO, seatIds: [seat] });
        if (h.status === 409) conflicts++;
        sleep(rand(0.3, 0.6)); // 200ms 넘게: 연타 규칙은 건드리지 않음
      }
    }
    retry409.add(conflicts);
    done('rotate', conflicts >= perSeat * data.rotateSeats.length - 1);
  } finally {
    queueLeave(token);
  }
}

// ── 봇 5: 일정 간격 매크로 (REGULAR_INTERVAL) ─────────────────
// 빠르지 않은(200ms 이상) 대신 간격이 기계처럼 일정하다. 요청 시작 시각을 t0 + i×간격에 맞춰
// 응답 시간이 흔들려도 간격이 일정하게 (서버는 요청을 받은 시각으로 간격을 잼)
export function steadyMacro(data) {
  const u = me(data, 'steady');
  setDevice(`ltdev${data.tag}s${u.i}`);
  sleep(Math.random() * SPREAD_SEC);
  const token = loginOrFail(u);
  const periodMs = u.grp; // 강도 = 간격 ms (STEADY_LEVELS)
  const t0 = Date.now();
  let sent = 0;
  for (let i = 0; i < STEADY_COUNT; i++) {
    const wait = t0 + i * periodMs - Date.now();
    if (wait > 0) sleep(wait / 1000);
    if (search(token, FROM, TO, data.date, 'run_search_steady').status === 200) sent++;
  }
  done('steady', sent >= STEADY_COUNT * 0.9);
}

export function teardown(data) {
  const counts = KINDS.map((k) => `${k} ${data.users.filter((u) => u.kind === k).length}`).join(', ');
  console.log(`실행 태그 ${data.tag} (${counts}). 1분 배치가 돈 뒤 평가: ./tools/eval_risk.sh ${data.tag}`);
}
