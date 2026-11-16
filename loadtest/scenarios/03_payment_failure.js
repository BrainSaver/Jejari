// 시나리오 3: 결제 실패·기한 초과 시 좌석 자동 복구 (복구율 100%)
// VU마다 서로 다른 좌석을 선점한 뒤 세 갈래로 나눈다.
//   fail     : mock PG 실패 → Saga가 좌석을 복구해야 함
//   abandon  : 결제하지 않고 방치 → 선점 만료(HOLD_TTL_SEC) 후 자동 반환되어야 함
//   success  : 정상 결제 → 좌석이 복구되면 안 됨(대조군)
//
// 서버의 mock PG가 X-Mock-PG: fail 헤더를 해석한다고 가정한다 (config.js에서 변경).
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';
import { sleep } from 'k6';
import {
  parseCsv, login, enterQueue, holdSeat, pay, getSeatStatus, seatIdFor, preflightHoldApi } from '../lib/helpers.js';
import { FIELDS, HOLD_TTL_SEC } from '../config.js';

const users = new SharedArray('users', () => parseCsv(open(__ENV.USERS_FILE || '../data/users.csv')));

const recovered = new Counter('seat_recovered');
const notRecovered = new Counter('seat_not_recovered'); // fail/abandon 인데 복구 안 됨
const wrongRelease = new Counter('seat_wrongly_released'); // success 인데 풀려버림
const unknown = new Counter('seat_check_unknown'); // 판정 불가(수동 집계 대상)

export const options = {
  scenarios: {
    payment_failure: {
      executor: 'per-vu-iterations',
      vus: Number(__ENV.VUS || 300),
      iterations: 1,
      maxDuration: `${HOLD_TTL_SEC * 4 + 180}s`,
    },
  },
  thresholds: {
    seat_not_recovered: ['count==0'],
    seat_wrongly_released: ['count==0'],
  },
};

// 시작 전에 서버·좌석 선점 API 상태를 점검한다 (없으면 이유를 알려 주고 중단)
export function setup() {
  preflightHoldApi();
}

function isAvailable(res) {
  if (res.status !== 200) return null;
  return res.json(FIELDS.seatStatus) === FIELDS.availableValue;
}

export default function () {
  const user = users[(__VU - 1) % users.length];
  const token = login(user);
  if (!token) return;
  const q = enterQueue(token, { maxWaitSec: 120 });
  if (!q.ok) return;

  const seatId = seatIdFor('PAY', __VU);
  const hold = holdSeat(token, q.entryToken, seatId);
  if (hold.status !== 200 && hold.status !== 201) return;
  const reservationId = hold.json(FIELDS.reservationId);

  const mode = ['fail', 'abandon', 'success'][__VU % 3];

  if (mode === 'fail') {
    pay(token, reservationId, { mockPg: 'fail' });
    sleep(5); // Saga 보상 처리 시간
  } else if (mode === 'abandon') {
    sleep(HOLD_TTL_SEC + 10); // 만료 + 스케줄러 여유
  } else {
    pay(token, reservationId, { mockPg: 'success' });
    sleep(5);
  }

  const avail = isAvailable(getSeatStatus(token, seatId));
  if (avail === null) { unknown.add(1); return; }

  if (mode === 'success') {
    if (avail) wrongRelease.add(1);
  } else if (avail) {
    recovered.add(1);
  } else {
    notRecovered.add(1);
  }
}
