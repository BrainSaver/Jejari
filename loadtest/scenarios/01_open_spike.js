// 시나리오 1: 예매 오픈 시각 폭주 (대기열 처리량)
// 오픈 순간 요청이 급증할 때 대기열이 입장 인원을 제한하며 버티는지 본다. 진입 → 번호표 조회 → 입장 전체 흐름.
// 입장까지 기다리는 만큼 VU가 묶이므로 진입 API 자체의 한계는 시나리오 8(08_entry_burst)로 잰다.
//
// 실행: k6 run scenarios/01_open_spike.js -e PEAK_RATE=1000
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { parseCsv, login, enterQueue, leaveQueue, preflight } from '../lib/helpers.js';

const users = new SharedArray('users', () => parseCsv(open(__ENV.USERS_FILE || '../data/users.csv')));

const queueWait = new Trend('queue_wait_sec');
const admitted = new Rate('queue_admitted');

const PEAK = Number(__ENV.PEAK_RATE || 1000); // 초당 신규 진입 수

export const options = {
  scenarios: {
    open_spike: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.PRE_VUS || 1000),
      maxVUs: Number(__ENV.MAX_VUS || 5000),
      stages: [
        { target: Math.max(10, Math.round(PEAK * 0.1)), duration: '30s' }, // 오픈 전 워밍업
        { target: PEAK, duration: '10s' }, // 오픈 순간 스파이크
        { target: PEAK, duration: __ENV.HOLD_DURATION || '2m' },
        { target: 0, duration: '30s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    'http_req_duration{name:queue_enter}': ['p(95)<2000'],
    queue_admitted: ['rate>0.5'],
    dropped_iterations: ['count<100'], // 부하 생성기 한계로 못 쏜 요청이 많으면 결과를 믿을 수 없다
  },
};

// 시작 전에 앱·Redis 상태를 점검한다 (실패하면 이유를 알려 주고 중단)
export function setup() {
  preflight();
}

export default function () {
  const user = users[(__VU - 1) % users.length];
  const token = login(user);
  if (!token) return;

  const q = enterQueue(token, { maxWaitSec: Number(__ENV.MAX_WAIT_SEC || 60) });
  admitted.add(q.ok);
  if (q.ok) queueWait.add(q.waitedSec);
  check(q, { '대기열 입장 성공': (r) => r.ok });
  // 입장한 사람은 줄에서 나간다 (같은 회원 ID가 다시 쓰일 때 이전 대기 기록 때문에 막히지 않게)
  if (__ENV.LEAVE_AFTER !== '0') leaveQueue(token);
}
