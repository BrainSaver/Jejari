// 시나리오 2. 입장 토큰 차단율 (기획서 성과 목표: 토큰 없이 좌석 API 호출 시 차단율 100%, 무효 토큰 재사용 차단 100%).
// 막아야 할 요청은 전부 403이어야 하고, 정상 토큰은 전부 200이어야 한다. 하나라도 어긋나면 실패.
//
// 실행: k6 run -e VUS=50 -e ITERATIONS=500 token-guard.js
import http from 'k6/http';
import exec from 'k6/execution';
import { Rate, Counter } from 'k6/metrics';
import { enter, leave, seats, waitUntilAdmitted, preflight } from './lib/queue.js';

const GUARD_BASE = Number(__ENV.MEMBER_BASE || 2000000);
const OTHER_RUN = __ENV.OTHER_RUN_ID || '2002';

const blocked = new Rate('guard_blocked');   // 막혀야 할 요청 중 403으로 막힌 비율 (목표 100%)
const allowed = new Rate('guard_allowed');   // 열려야 할 요청 중 200인 비율 (목표 100%)
const attempts = new Counter('guard_blocked_attempts');

export const options = {
  scenarios: {
    guard: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 20),
      iterations: Number(__ENV.ITERATIONS || 200),
      maxDuration: __ENV.MAX_DURATION || '5m',
    },
  },
  thresholds: {
    guard_blocked: ['rate==1'],
    guard_allowed: ['rate==1'],
  },
};

// 시작 전에 앱·Redis 상태를 점검한다 (실패하면 이유를 알려 주고 중단)
export function setup() {
  preflight();
}

// 403이 정상 응답인 요청들이라 실패로 세지 않게 한다
const expect403 = { responseCallback: http.expectedStatuses(403) };

function expectBlocked(res, code) {
  const body = res.status === 403 ? res.json() : {};
  attempts.add(1);
  blocked.add(res.status === 403 && body.code === code);
}

export default function () {
  const a = GUARD_BASE + exec.scenario.iterationInTest * 2;
  const b = a + 1;

  const ea = enter(a);
  const eb = enter(b);
  const wa = waitUntilAdmitted(a, ea, 60000);
  const wb = waitUntilAdmitted(b, eb, 60000);
  if (!wa.admitted || !wb.admitted) {
    // 입장하지 못한 것은 차단 판정과 무관하다. 대기열 쪽 문제라 처리량 시나리오에서 본다.
    leave(a);
    leave(b);
    return;
  }

  expectBlocked(seats(a, null, undefined, expect403), 'QUEUE_TOKEN_REQUIRED');                  // 토큰 없음
  expectBlocked(seats(a, 'garbage-token', undefined, expect403), 'QUEUE_TOKEN_INVALID');        // 가짜 토큰
  expectBlocked(seats(a, wb.token, undefined, expect403), 'QUEUE_TOKEN_INVALID');               // 남의 토큰
  expectBlocked(seats(a, wa.token, OTHER_RUN, expect403), 'QUEUE_TOKEN_INVALID');               // 다른 운행에 사용

  allowed.add(seats(a, wa.token).status === 200);                                               // 정상 토큰
  allowed.add(seats(b, wb.token).status === 200);

  leave(a);                                                                                     // 이탈하면 즉시 무효
  expectBlocked(seats(a, wa.token, undefined, expect403), 'QUEUE_TOKEN_INVALID');
  allowed.add(seats(b, wb.token).status === 200);                                               // 다른 사람 토큰은 그대로

  leave(b);
}
