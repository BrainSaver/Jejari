// 시나리오 2: 같은 좌석 동시 선점 (중복 예약 0건, 락 방식별 TPS)
// N명이 동시에 동일 좌석을 요청한다. 성공은 정확히 1건이어야 한다.
//
// 락 방식 비교: 서버가 X-Lock-Strategy 헤더(또는 프로파일)로 전환을 지원하면
//   k6 run scenarios/02_same_seat_race.js -e LOCK_STRATEGY=pessimistic
//   k6 run scenarios/02_same_seat_race.js -e LOCK_STRATEGY=optimistic
//   k6 run scenarios/02_same_seat_race.js -e LOCK_STRATEGY=redis
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';
import { sleep } from 'k6';
import { parseCsv, login, enterQueue, holdSeat, seatIdFor, preflightHoldApi, enterOnly, waitUntilAdmitted, safeJson } from '../lib/helpers.js';

const users = new SharedArray('users', () => parseCsv(open(__ENV.USERS_FILE || '../data/users.csv')));

const VUS = Number(__ENV.RACE_VUS || 500);

const seatSuccess = new Counter('seat_success');
const seatConflict = new Counter('seat_conflict');
const seatOther = new Counter('seat_other');
const holdLatency = new Trend('hold_latency_ms', true);

export const options = {
  setupTimeout: '10m',
  scenarios: {
    race: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '1m',
    },
  },
  thresholds: {
    seat_success: ['count<=1'], // 중복 예약 0건 (성공은 최대 1건)
    seat_other: ['count==0'], // 5xx 등 예상 밖 응답 0건
  },
};

// 로그인·대기열 입장은 미리 끝내 두고, 좌석 요청만 같은 순간에 발사한다.
export function setup() {
  preflightHoldApi();   // 서버·좌석 선점 API 상태를 점검한다 (없으면 이유를 알려 주고 중단)
  // 먼저 모두 줄을 세운 뒤 한꺼번에 입장을 기다린다 (한 명씩 기다리면 입장 처리 주기만큼 오래 걸린다)
  const tokens = [];
  for (let i = 0; i < VUS; i++) tokens.push(login(users[i % users.length]));
  const entered = tokens.map((token) => (token ? enterOnly(token) : null));
  const prepared = tokens.map((token, i) => {
    if (!token || !entered[i] || entered[i].status !== 200) return null;
    const q = waitUntilAdmitted(token, safeJson(entered[i]), { maxWaitSec: 120 });
    return q.ok ? { token, entryToken: q.entryToken } : null;
  });
  // init 코드는 VU마다 따로 실행되므로, 좌석 ID는 setup에서 한 번만 정해 모두에게 전달한다.
  const targetSeat = __ENV.TARGET_SEAT || seatIdFor('RACE', Date.now() % 100000);
  const ready = prepared.filter(Boolean).length;
  console.log(`준비 완료 ${ready}/${VUS}명, 대상 좌석: ${targetSeat}`);
  return { prepared, targetSeat, startAt: Date.now() + 3000 };
}

export default function (data) {
  const me = data.prepared[__VU - 1];
  if (!me) return;

  // 모든 VU가 같은 시각까지 대기했다가 동시에 발사
  const wait = data.startAt - Date.now();
  if (wait > 0) sleep(wait / 1000);

  const res = holdSeat(me.token, me.entryToken, data.targetSeat);
  holdLatency.add(res.timings.duration);
  if (res.status === 200 || res.status === 201) seatSuccess.add(1);
  else if (res.status === 409) seatConflict.add(1);
  else seatOther.add(1);
}
