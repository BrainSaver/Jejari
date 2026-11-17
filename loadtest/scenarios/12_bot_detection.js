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

const n = (k, d, max = 2000) => Math.min(max, Math.max(0, Number(__ENV[k] ?? d)));
const NORMAL = n('NORMAL', 40);
const FAST = n('FAST', 5);
const RETRY = n('RETRY', 5);
const MULTI_GROUPS = n('MULTI_GROUPS', 3);
const MULTI_SIZE = n('MULTI_SIZE', 3, 10);
const CANCEL = n('CANCEL', 5);
const SPREAD_SEC = n('SPREAD_SEC', 30, 600);
const FAST_CLICKS = n('FAST_CLICKS', 50, 500);           // FAST_CLICK 문턱: 200ms 미만 간격 20회(MID)·60회(HIGH)
const RETRY_TIMES = n('RETRY_TIMES', 8, 100);             // SAME_SEAT_RETRY 문턱: 같은 좌석 실패 5회(MID)·15회(HIGH)
const CANCEL_ROUNDS = n('CANCEL_ROUNDS', 3, 9);           // CANCEL_AFTER_PAY 문턱: 구매 후 30분 안 취소 2건(MID)·5건(HIGH)
const NORMAL_CANCEL_RATE = Number(__ENV.NORMAL_CANCEL_RATE ?? 0.2); // 정상 사용자도 마음이 바뀌면 1번은 취소한다 (문턱 2건 아래)
const FROM = __ENV.FROM || '3900023';
const TO = __ENV.TO || '3900114';
const MAX_WAIT_SEC = n('MAX_WAIT_SEC', 180, 1800);

const KINDS = ['normal', 'fast', 'retry', 'multi', 'cancel'];
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
if (MULTI_GROUPS && MULTI_SIZE) scenarios.multi = group('multiAccount', MULTI_GROUPS * MULTI_SIZE);
if (CANCEL) scenarios.cancel = group('cancelAbuser', CANCEL);

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
  add('normal', NORMAL);
  add('fast', FAST);
  add('retry', RETRY);
  add('multi', MULTI_GROUPS * MULTI_SIZE, (i) => Math.floor(i / MULTI_SIZE));
  add('cancel', CANCEL);
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
  const need = NORMAL + FAST + RETRY + MULTI_GROUPS * MULTI_SIZE + CANCEL * CANCEL_ROUNDS + 1;
  if (map.seats.length < need) console.warn(`빈 좌석 ${map.seats.length}석 < 필요 ${need}석: 일부 사용자가 좌석을 못 잡습니다.`);
  const targetSeat = pick(map.seats);
  const h = hold(token, q.passToken, { from: FROM, to: TO, seatIds: [targetSeat] });
  const rid = (safeJson(h) || {}).reservationId;
  const p = rid ? pay(token, rid, { cardNumber: decoy.card }) : h;
  queueLeave(token);
  if (!(p.status === 201 || p.status === 200)) fail('도우미 좌석 구매', p);
  setDevice(null);

  const date = runDate(RUN_ID, FROM, TO);
  if (!date) throw new Error(`운행 ${RUN_ID} 상세를 읽지 못했습니다 (FROM=${FROM}, TO=${TO}).`);
  return { tag, users, targetSeat, date };
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
  for (let i = 0; i < FAST_CLICKS; i++) {
    if (search(token, FROM, TO, data.date, 'run_search_bot').status === 200) sent++;
    sleep(rand(0.02, 0.06));
  }
  fastClicks.add(sent);
  const q = queueUntilAdmitted(token, { maxWaitSec: MAX_WAIT_SEC });
  if (!q.ok) return done('fast', false);
  try {
    buyOne(u, token, q.passToken); // 사는 것도 사람 속도로 (이 봇은 연타 하나만 보이게)
    done('fast', sent >= FAST_CLICKS * 0.9);
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
    for (let i = 0; i < RETRY_TIMES; i++) {
      const h = hold(token, q.passToken, { from: FROM, to: TO, seatIds: [data.targetSeat] });
      if (h.status === 409) conflicts++;
      sleep(rand(0.3, 0.6)); // 200ms 넘게: 연타 규칙은 건드리지 않음
    }
    retry409.add(conflicts);
    done('retry', conflicts >= RETRY_TIMES - 1);
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
    for (let r = 0; r < CANCEL_ROUNDS; r++) {
      const rid = buyOne(u, token, q.passToken);
      if (rid == null) continue;
      sleep(rand(1, 3));
      if (check(cancel(token, rid), { '취소 200': (res) => res.status === 200 })) ok++;
      sleep(rand(0.5, 1));
    }
    cancelled.add(ok);
    done('cancel', ok >= 2);
  } finally {
    queueLeave(token);
  }
}

export function teardown(data) {
  const counts = KINDS.map((k) => `${k} ${data.users.filter((u) => u.kind === k).length}`).join(', ');
  console.log(`실행 태그 ${data.tag} (${counts}). 1분 배치가 돈 뒤 평가: ./tools/eval_risk.sh ${data.tag}`);
}
