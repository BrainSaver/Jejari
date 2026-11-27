// 실제 앱의 예매 흐름 (JWT 로그인 → 열차 조회 → 대기열 → 좌석 배치도 → 좌석 선점 → 결제)을 한 단계씩 부르는 함수.
// 시나리오 11(정상 사용자)이 쓰고, 봇 시뮬레이션(07)을 실제 앱으로 옮길 때도 같은 함수를 쓴다.
//
// helpers.js의 대기열 함수는 AUTH_MODE(개발 모드 X-Member-Id / jwt)를 보고 헤더를 고르지만, 여기서는 항상
// JWT(Authorization: Bearer)를 쓴다. 실제 회원(Oracle)으로 흐름 전체를 재는 것이 목적이라서다.
import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { API, FIELDS, HEADERS, RUN_ID } from '../config.js';
import { url, safeJson } from './helpers.js';

// 이 VU가 쓰는 기기 ID. k6는 VU마다 모듈을 따로 읽으므로 VU 하나 = 기기 하나로 둘 수 있다
let deviceId = null;

/** 이후 모든 요청에 X-Device-Id를 붙인다 (봇 시뮬레이션: 다계정이 한 기기를 같이 쓰는 경우 등) */
export function setDevice(id) {
  deviceId = id;
}

/** JWT 요청 헤더. ip를 주면 X-Forwarded-For로 보낸다 (앱이 신뢰 프록시 옵션으로 떠 있을 때만 의미 있음) */
export function bearer(accessToken, extra = {}, ip = null) {
  const h = { 'Content-Type': 'application/json', [HEADERS.loadTest]: '1' };
  if (accessToken) h.Authorization = `Bearer ${accessToken}`;
  if (deviceId) h[HEADERS.deviceId] = deviceId;
  if (ip) h[HEADERS.forwardedFor] = ip;
  return Object.assign(h, extra);
}

/** k6에는 crypto.randomUUID가 없어 멱등 키용 UUID v4를 직접 만든다 */
export function uuid() {
  const hex = '0123456789abcdef';
  let s = '';
  for (let i = 0; i < 36; i++) {
    if (i === 8 || i === 13 || i === 18 || i === 23) s += '-';
    else if (i === 14) s += '4';
    else if (i === 19) s += hex[(Math.random() * 4) | 8];
    else s += hex[(Math.random() * 16) | 0];
  }
  return s;
}

export function codeOf(res) {
  const b = safeJson(res);
  return b && b[FIELDS.errorCode] ? b[FIELDS.errorCode] : '';
}

// ── 열차 조회 (비회원) ──────────────────────────────────────

export function browse(runId, from, to) {
  const s = http.get(url(API.stations), { headers: bearer(null), tags: { name: 'stations' } });
  const d = http.get(url(`${API.runDetail(runId)}?from=${from}&to=${to}`), {
    headers: bearer(null),
    tags: { name: 'run_detail' },
  });
  return s.status === 200 && d.status === 200;
}

/** 운행 상세에서 운행 날짜(YYYY-MM-DD)를 읽는다. 운행 검색(search)에 필요 */
export function runDate(runId, from, to) {
  const d = http.get(url(`${API.runDetail(runId)}?from=${from}&to=${to}`), { headers: bearer(null), tags: { name: 'run_detail' } });
  return d.status === 200 ? (safeJson(d) || {}).runDate : null;
}

/** 운행 검색 (TRN-02). 토큰을 주면 회원 세션으로 남아 클릭 간격(interval_ms)이 계산된다 */
export function search(token, from, to, date, name = 'run_search') {
  return http.get(url(`${API.runSearch}?from=${from}&to=${to}&date=${date}`), { headers: bearer(token), tags: { name } });
}

// ── 대기열 ────────────────────────────────────────────────

/**
 * 진입하고 입장할 때까지 번호표를 조회한다.
 * @return {{ok, passToken, waitedSec, status, code}}
 */
export function queueUntilAdmitted(token, { runId = RUN_ID, maxWaitSec = 120 } = {}) {
  const started = Date.now();
  const enter = http.post(url(API.queueEnter(runId)), null, { headers: bearer(token), tags: { name: 'queue_enter' } });
  if (enter.status !== 200) {
    return { ok: false, passToken: null, waitedSec: 0, status: enter.status, code: codeOf(enter) };
  }
  let body = safeJson(enter) || {};
  while (body[FIELDS.queueState] === FIELDS.waitingValue && (Date.now() - started) / 1000 < maxWaitSec) {
    sleep(Math.max(0.2, Number(body[FIELDS.pollInterval]) || 1));
    const st = http.get(url(API.queueMe(runId)), { headers: bearer(token), tags: { name: 'queue_status' } });
    if (st.status !== 200) {
      return { ok: false, passToken: null, waitedSec: (Date.now() - started) / 1000, status: st.status, code: codeOf(st) };
    }
    body = safeJson(st) || {};
  }
  const admitted = body[FIELDS.queueState] === FIELDS.admittedValue;
  return {
    ok: admitted,
    passToken: admitted ? body[FIELDS.entryToken] : null,
    waitedSec: (Date.now() - started) / 1000,
    status: 200,
    code: admitted ? '' : 'QUEUE_WAIT_TIMEOUT',
  };
}

export function queueLeave(token, runId = RUN_ID) {
  return http.del(url(API.queueMe(runId)), null, { headers: bearer(token), tags: { name: 'queue_leave' } });
}

// ── 좌석 ──────────────────────────────────────────────────

/** 입장 토큰으로 좌석 배치도를 받아 빈 좌석 ID 목록을 돌려준다. 실패하면 null */
export function emptySeats(token, passToken, { runId = RUN_ID, from, to }) {
  const res = http.get(url(`${API.seatMap(runId)}?from=${from}&to=${to}`), {
    headers: bearer(token, { [HEADERS.entryToken]: passToken }),
    tags: { name: 'seat_map' },
  });
  if (res.status !== 200) return { seats: null, res };
  const map = safeJson(res) || {};
  const seats = [];
  for (const car of map.cars || []) {
    for (const s of car.seats || []) if (s.state === 'EMPTY') seats.push(s.seatId);
  }
  return { seats, res };
}

/** 좌석 선점 (예약 HOLD). 같은 요청을 다시 보내도 한 번만 처리되게 멱등 키를 붙인다 */
export function hold(token, passToken, { runId = RUN_ID, from, to, seatIds, idempotencyKey = uuid() }) {
  return http.post(
    url(API.reservationHold),
    JSON.stringify({
      runId: Number(runId),
      fromStationId: from,
      toStationId: to,
      seats: seatIds.map((id) => ({ seatId: id, passengerType: 'ADULT' })),
      channel: 'WEB',
    }),
    {
      headers: bearer(token, { [HEADERS.entryToken]: passToken, [HEADERS.idempotency]: idempotencyKey }),
      tags: { name: 'hold' },
    },
  );
}

/**
 * 화면 녹화 한 묶음 (ADM-11 녹화기가 보내는 것과 같은 모양). 사람은 화면으로 좌석을 고르므로 그 로그인 세션에 녹화가 있다.
 * 위험 점수의 HEADLESS_HOLD(선점했는데 녹화 0건 = 브라우저 없이 API만)가 k6 사용자를 봇으로 보지 않게, 선점 전에 부른다.
 * 클릭은 키보드(pointer k)로 남겨 마우스 궤적 규칙(LINEAR_MOUSE)에는 들어가지 않게 한다. 실패해도 흐름은 그대로.
 */
let replaySeq = 0;
export function recordScreen(token, runId = RUN_ID) {
  const now = Date.now();
  replaySeq = Math.max(replaySeq + 2, now * 1000);
  const screen = 'runs/:id/seats';
  return http.post(url('/api/v1/replay/events'), JSON.stringify({ events: [
    { seq: replaySeq, ts: now, type: 'PAGE_VIEW', screen, runId: Number(runId) },
    { seq: replaySeq + 1, ts: now, type: 'CLICK', screen, x: 500, y: 500, target: 'seat', pointer: 'k', runId: Number(runId) },
  ] }), { headers: bearer(token), tags: { name: 'replay' } });
}

/**
 * 결제 시작 → 모의 PG 승인까지 (카드 끝자리 0 거절, 8 결과 모름, 그 외 승인).
 * 카드를 안 주면 VU마다 다른 시험 카드 (끝자리 1). 모두 같은 카드를 쓰면 카드 명의 상한(payment.max_ci_per_card)을
 * 켠 서버에서 네 번째 계정부터 결제가 거절돼 성능 대신 그 규칙을 재게 된다
 */
/** VU마다 다른 시험 카드 (4111 + VU 번호 11자리 + 끝자리) */
export const vuCard = (last = '1') => `4111${String(exec.vu.idInTest).padStart(11, '0')}${last}`;

export function pay(token, reservationId, { cardNumber = vuCard(), idempotencyKey = uuid() } = {}) {
  return http.post(url(API.reservationPay(reservationId)), JSON.stringify({ cardNumber }), {
    headers: bearer(token, { [HEADERS.idempotency]: idempotencyKey }),
    tags: { name: 'pay' },
  });
}

export function cancel(token, reservationId) {
  return http.post(url(API.reservationCancel(reservationId)), null, {
    headers: bearer(token),
    tags: { name: 'cancel' },
  });
}

/** 배열에서 무작위로 하나 (경합을 흩뜨리려고. 모두 앞 좌석을 고르면 정상 사용자끼리도 계속 부딪힌다) */
export function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}
