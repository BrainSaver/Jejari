// 시나리오 1. 진입 → 번호표 폴링 → 입장 → 좌석 API → 이탈 전체 흐름. 동시 접속 처리량·응답 시간·대기 시간을 잰다.
//
// 실행: k6 run -e ARRIVAL_RATE=200 -e DURATION=30s flow.js
// 대기가 실제로 쌓이게 하려면 먼저 입장 속도를 낮춘다 (README 참고).
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { enter, leave, seats, waitUntilAdmitted, parse } from './lib/queue.js';

const ARRIVAL_RATE = Number(__ENV.ARRIVAL_RATE || 100);   // 초당 새로 줄 서는 사람 수
const DURATION = __ENV.DURATION || '30s';
const MAX_WAIT_MS = Number(__ENV.MAX_WAIT_MS || 120000);   // 이만큼 기다려도 입장 못하면 포기
const MEMBER_BASE = Number(__ENV.MEMBER_BASE || 1000000);

const admitWait = new Trend('queue_admit_wait_ms', true);   // 진입부터 입장까지
const enterOk = new Counter('queue_enter_ok');
const giveUp = new Counter('queue_gave_up');                // 시간 안에 입장하지 못한 사람

export const options = {
  scenarios: {
    flow: {
      executor: 'constant-arrival-rate',
      rate: ARRIVAL_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Number(__ENV.PRE_VUS || 200),
      maxVUs: Number(__ENV.MAX_VUS || 5000),
    },
  },
  // 목표값은 측정 후 정한다 (docs/김성원/contribution.md). 아래는 "크게 망가지지 않았는가"만 보는 느슨한 기준.
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'http_req_duration{name:queue_enter}': ['p(95)<500'],
    'http_req_duration{name:queue_me}': ['p(95)<300'],
    'checks': ['rate>0.99'],
  },
};

export default function () {
  const id = MEMBER_BASE + exec.scenario.iterationInTest;
  const t0 = Date.now();

  const res = enter(id);
  const ok = check(res, {
    '진입 200': (r) => r.status === 200,
    '순번 또는 토큰 존재': (r) => {
      const b = parse(r);
      return b.rank > 0 || !!b.passToken;
    },
  });
  if (!ok) return;
  enterOk.add(1);

  const w = waitUntilAdmitted(id, res, MAX_WAIT_MS);
  if (!w.admitted) {
    giveUp.add(1);
    leave(id);
    return;
  }
  admitWait.add(Date.now() - t0);

  const s = seats(id, w.token);
  check(s, { '입장 토큰으로 좌석 API 200': (r) => r.status === 200 });
  leave(id);
}
