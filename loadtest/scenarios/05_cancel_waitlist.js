// 시나리오 5: 취소표 재배정 순서 보장
// 매진 좌석의 취소표가 발생하면 예약 대기 순서대로 배정되는지 확인한다.
//   1) 보유자가 좌석을 선점·결제(확정)
//   2) 대기자 N명이 순서대로 예약 대기 등록
//   3) 보유자가 취소
//   4) 1번 대기자가 먼저 배정(ASSIGNED)되고, 이후 순서가 뒤집히지 않아야 함
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';
import http from 'k6/http';
import { sleep } from 'k6';
import {
  parseCsv, login, enterQueue, holdSeat, pay, cancelReservation, seatIdFor, headers, url, preflightHoldApi } from '../lib/helpers.js';
import { API, FIELDS, RUN_ID } from '../config.js';

const users = new SharedArray('users', () => parseCsv(open(__ENV.USERS_FILE || '../data/users.csv')));

const WAITERS = Number(__ENV.WAITERS || 10);

const orderViolations = new Counter('waitlist_order_violations');
const assignedCount = new Counter('waitlist_assigned');

export const options = {
  scenarios: {
    cancel_waitlist: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' },
  },
  thresholds: {
    waitlist_order_violations: ['count==0'],
    waitlist_assigned: ['count>=1'],
  },
};

// 시작 전에 서버·좌석 선점 API 상태를 점검한다 (없으면 이유를 알려 주고 중단)
export function setup() {
  preflightHoldApi();
}

export default function () {
  const holder = users[0];
  const hToken = login(holder);
  if (!hToken) return;
  const hq = enterQueue(hToken);
  if (!hq.ok) return;

  const seatId = seatIdFor('WAIT', Date.now() % 100000);
  const hold = holdSeat(hToken, hq.entryToken, seatId);
  if (hold.status !== 200 && hold.status !== 201) { console.error('보유자 선점 실패'); return; }
  const reservationId = hold.json(FIELDS.reservationId);
  pay(hToken, reservationId, { mockPg: 'success' });

  // 대기자는 반드시 순서대로 등록한다 (순서가 정답의 기준)
  const waiters = [];
  for (let i = 1; i <= WAITERS; i++) {
    const u = users[i];
    const t = login(u);
    if (!t) continue;
    const r = http.post(url(API.waitlistJoin), JSON.stringify({ runId: RUN_ID, seatId }), {
      headers: headers(t), tags: { name: 'waitlist_join' },
    });
    if (r.status === 200 || r.status === 201) waiters.push({ rank: waiters.length + 1, token: t });
    sleep(0.2);
  }
  console.log(`대기 등록 ${waiters.length}명`);

  cancelReservation(hToken, reservationId);

  // 1번 대기자가 배정될 때까지 모든 대기자의 상태를 관찰
  const firstAssignedAt = {};
  const deadline = Date.now() + Number(__ENV.WAIT_MS || 60000);
  while (Date.now() < deadline) {
    waiters.forEach((w) => {
      if (firstAssignedAt[w.rank]) return;
      const r = http.get(url(API.waitlistMe), { headers: headers(w.token), tags: { name: 'waitlist_me' } });
      if (r.status === 200 && r.json(FIELDS.waitlistStatus) === FIELDS.waitlistAssignedValue) {
        firstAssignedAt[w.rank] = Date.now();
      }
    });
    if (firstAssignedAt[1]) break;
    sleep(0.5);
  }

  const assigned = Object.keys(firstAssignedAt).map(Number).sort((a, b) => a - b);
  assignedCount.add(assigned.length);
  // 좌석은 1개이므로 배정된 사람은 1번뿐이어야 하고, 다른 순번이 먼저 배정되면 위반
  if (assigned.length > 0 && assigned[0] !== 1) orderViolations.add(1);
  if (assigned.length > 1) orderViolations.add(1); // 좌석 1개에 2명 배정
  console.log(`배정된 대기 순번: ${JSON.stringify(assigned)}`);
}
