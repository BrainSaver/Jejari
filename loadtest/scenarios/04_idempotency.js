// 시나리오 4: 중복 요청 멱등성 (중복 결제 0건)
// 같은 Idempotency-Key로 결제 요청을 동시에 여러 번 보내도 결제는 1건이어야 한다.
import http from 'k6/http';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';
import {
  parseCsv, login, enterQueue, holdSeat, seatIdFor, headers, url, preflightHoldApi } from '../lib/helpers.js';
import { API, FIELDS, HEADERS } from '../config.js';

const users = new SharedArray('users', () => parseCsv(open(__ENV.USERS_FILE || '../data/users.csv')));

const DUP = Number(__ENV.DUP_REQUESTS || 5); // 같은 결제를 몇 번 동시에 보낼지

const duplicatePayments = new Counter('duplicate_payments');
const duplicateHolds = new Counter('duplicate_holds');
const checked = new Counter('idempotency_checked');

export const options = {
  scenarios: {
    idempotency: {
      executor: 'per-vu-iterations',
      vus: Number(__ENV.VUS || 200),
      iterations: 1,
      maxDuration: '5m',
    },
  },
  thresholds: {
    duplicate_payments: ['count==0'],
    duplicate_holds: ['count==0'],
  },
};

// 시작 전에 서버·좌석 선점 API 상태를 점검한다 (없으면 이유를 알려 주고 중단)
export function setup() {
  preflightHoldApi();
}

function distinct(responses, field) {
  const ids = new Set();
  responses.forEach((r) => {
    if (r.status === 200 || r.status === 201) {
      try { ids.add(String(r.json(field))); } catch (e) { /* 본문 없음 */ }
    }
  });
  return ids.size;
}

export default function () {
  const user = users[(__VU - 1) % users.length];
  const token = login(user);
  if (!token) return;
  const q = enterQueue(token, { maxWaitSec: 120 });
  if (!q.ok) return;

  // (a) 같은 좌석 선점 요청을 동시에 DUP번 → 예약은 1개여야 함
  const seatId = seatIdFor('IDEM', __VU);
  const holdReqs = [];
  for (let i = 0; i < DUP; i++) {
    holdReqs.push({
      method: 'POST',
      url: url(API.seatHold),
      body: JSON.stringify({ runId: __ENV.RUN_ID || '1', seatId }),
      params: {
        headers: headers(token, {
          [HEADERS.entryToken]: q.entryToken,
          [HEADERS.idempotency]: `hold-${__VU}`,
        }),
        tags: { name: 'seat_hold_dup' },
      },
    });
  }
  const holdRes = http.batch(holdReqs);
  if (distinct(holdRes, FIELDS.reservationId) > 1) duplicateHolds.add(1);

  const okHold = holdRes.find((r) => r.status === 200 || r.status === 201);
  if (!okHold) return;
  const reservationId = okHold.json(FIELDS.reservationId);

  // (b) 같은 결제 요청을 동시에 DUP번 → 결제는 1건이어야 함
  const key = `pay-${__VU}-${Date.now()}`;
  const payReqs = [];
  for (let i = 0; i < DUP; i++) {
    payReqs.push({
      method: 'POST',
      url: url(API.payment),
      body: JSON.stringify({ reservationId, amount: 10000 }),
      params: {
        headers: headers(token, { [HEADERS.idempotency]: key, [HEADERS.mockPg]: 'success' }),
        tags: { name: 'payment_dup' },
      },
    });
  }
  const payRes = http.batch(payReqs);
  checked.add(1);
  if (distinct(payRes, FIELDS.paymentId) > 1) duplicatePayments.add(1);
}
