// 시나리오 7: 정상 사용자 + 봇 시뮬레이션 (탐지율·오탐률용 트래픽)
// data/users.csv 의 label 에 따라 행동 패턴이 달라진다. 라벨은 서버로 보내지 않는다.
//   normal      : 사람처럼 느린 조회 → 대기열 → 좌석 1개 → 결제
//   bot_rapid   : 조회를 20~60ms 간격으로 연타 (클릭 간격 기준 미달)
//   bot_bulk    : 짧은 시간에 서로 다른 좌석 여러 개 선점 시도 (단시간 대량 선점)
//   bot_cancel  : 선점·결제 직후 몇 초 안에 취소를 반복 (구매 후 단시간 취소)
//
// 봇 행동은 사람이 하기 어려운 수준의 보수적 기준으로 정의했다(기획서 '데이터 활용' 참고).
// 실행 후: python3 tools/eval_detection.py --truth data/ground_truth.csv --suspects <관리자 API 결과>
import http from 'k6/http';
import { SharedArray } from 'k6/data';
import { sleep } from 'k6';
import {
  parseCsv, login, enterQueue, holdSeat, pay, cancelReservation,
  seatIdFor, think, headers, url, preflightHoldApi } from '../lib/helpers.js';
import { API, FIELDS, RUN_ID } from '../config.js';

const csvText = open(__ENV.USERS_FILE || '../data/users.csv');
const byLabel = (label) => new SharedArray(label, () => parseCsv(csvText).filter((u) => u.label === label));
const normals = byLabel('normal');
const rapid = byLabel('bot_rapid');
const bulk = byLabel('bot_bulk');
const cancelers = byLabel('bot_cancel');

// 계정 1개당 시나리오 1번 실행 → per-vu-iterations 로 VU 수 = 해당 라벨 계정 수
const scenario = (exec, pool, startTime = '0s') => ({
  executor: 'per-vu-iterations',
  exec,
  vus: Math.max(1, Math.min(pool.length, Number(__ENV.MAX_PER_GROUP || 2000))),
  iterations: 1,
  startTime,
  maxDuration: '10m',
});

export const options = {
  scenarios: {
    normal_users: scenario('normalUser', normals),
    bot_rapid_click: scenario('botRapidClick', rapid, '5s'),
    bot_bulk_hold: scenario('botBulkHold', bulk, '5s'),
    bot_hold_cancel: scenario('botHoldCancel', cancelers, '5s'),
  },
  thresholds: {
    'http_req_duration{name:seat_hold}': ['p(95)<3000'],
  },
};

// 시작 전에 서버·좌석 선점 API 상태를 점검한다 (없으면 이유를 알려 주고 중단)
export function setup() {
  preflightHoldApi();
}

function pick(pool) {
  return pool[(__VU - 1) % pool.length];
}

// ── 정상 사용자 ─────────────────────────────────────────────
export function normalUser() {
  const user = pick(normals);
  const token = login(user);
  if (!token) return;
  think(1, 3);
  http.get(url(`${API.trains}?runId=${RUN_ID}`), { headers: headers(token), tags: { name: 'trains' } });
  think(2, 5);
  http.get(url(`${API.trains}?runId=${RUN_ID}`), { headers: headers(token), tags: { name: 'trains' } });
  think(2, 4);

  const q = enterQueue(token, { maxWaitSec: 180, pollSec: 2 });
  if (!q.ok) return;
  think(2, 6);
  const hold = holdSeat(token, q.entryToken, seatIdFor('BOT-N', __VU));
  if (hold.status !== 200 && hold.status !== 201) return;
  think(3, 8);
  pay(token, hold.json(FIELDS.reservationId), { mockPg: 'success' });
}

// ── 봇 1: 조회 연타 ─────────────────────────────────────────
export function botRapidClick() {
  const user = pick(rapid);
  const token = login(user);
  if (!token) return;
  for (let i = 0; i < 40; i++) {
    http.get(url(`${API.trains}?runId=${RUN_ID}`), { headers: headers(token), tags: { name: 'trains_bot' } });
    sleep(0.02 + Math.random() * 0.04);
  }
}

// ── 봇 2: 대량 선점 ─────────────────────────────────────────
export function botBulkHold() {
  const user = pick(bulk);
  const token = login(user);
  if (!token) return;
  const q = enterQueue(token, { maxWaitSec: 180, pollSec: 2 });
  if (!q.ok) return;
  for (let i = 0; i < 8; i++) {
    holdSeat(token, q.entryToken, seatIdFor('BOT-B', __VU, i)); // 1인 매수 제한에 걸려도 시도 자체가 신호
    sleep(0.2);
  }
}

// ── 봇 3: 선점·결제 직후 취소 반복 ──────────────────────────
export function botHoldCancel() {
  const user = pick(cancelers);
  const token = login(user);
  if (!token) return;
  const q = enterQueue(token, { maxWaitSec: 180, pollSec: 2 });
  if (!q.ok) return;
  for (let i = 0; i < 3; i++) {
    const hold = holdSeat(token, q.entryToken, seatIdFor('BOT-C', __VU, i));
    if (hold.status !== 200 && hold.status !== 201) continue;
    const rid = hold.json(FIELDS.reservationId);
    pay(token, rid, { mockPg: 'success' });
    sleep(1 + Math.random() * 2); // 결제 후 1~3초 안에 취소
    cancelReservation(token, rid);
    sleep(0.5);
  }
}
