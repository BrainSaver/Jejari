// 시나리오 6: 대기열 우회 차단 (차단율 100%)
// 입장 토큰으로 보호되는 좌석 배치도(GET /api/v1/runs/{runId}/seats)를 다음 경우에 호출한다.
//   막혀야 하는 것(401/403): a) 토큰 없음  b) 위조 토큰  c) 남의 토큰  d) 다른 운행에 쓴 토큰  e) 이탈한 뒤 재사용한 토큰
//   열려야 하는 것(200)   : 정상 토큰, 그리고 다른 사람이 이탈해도 내 토큰
// 하나라도 통과하면 bypass_allowed, 정상 토큰이 막히면 valid_rejected 가 늘고 둘 다 0이어야 한다.
import { SharedArray } from 'k6/data';
import { Counter, Rate } from 'k6/metrics';
import http from 'k6/http';
import {
  parseCsv, login, enterQueue, leaveQueue, getSeatMap, preflight,
} from '../lib/helpers.js';
import { RUN_ID } from '../config.js';

const users = new SharedArray('users', () => parseCsv(open(__ENV.USERS_FILE || '../data/users.csv')));

const bypassAllowed = new Counter('bypass_allowed');       // 막혀야 하는데 통과한 요청
const validRejected = new Counter('valid_rejected');       // 열려야 하는데 막힌 요청
const blockedRate = new Rate('bypass_blocked_rate');       // 막혀야 하는 요청 중 실제로 막힌 비율 (목표 100%)

export const options = {
  scenarios: {
    bypass: {
      executor: 'per-vu-iterations',
      vus: Number(__ENV.VUS || 100),
      iterations: 1,
      maxDuration: '5m',
    },
  },
  thresholds: {
    bypass_allowed: ['count==0'],
    valid_rejected: ['count==0'],
    bypass_blocked_rate: ['rate==1'],
  },
};

// 시작 전에 앱·Redis 상태를 점검한다 (실패하면 이유를 알려 주고 중단)
export function setup() {
  preflight();
}

// 막혀야 하는 요청이라 403은 정상 응답이다 (http_req_failed로 세지 않는다)
const expectBlocked403 = { responseCallback: http.expectedStatuses(401, 403) };

const isBlocked = (res) => res.status === 401 || res.status === 403;

function mustBlock(res) {
  const blocked = isBlocked(res);
  blockedRate.add(blocked);
  if (!blocked) bypassAllowed.add(1);
}

function mustAllow(res) {
  if (res.status !== 200) validRejected.add(1);
}

export default function () {
  // 회원 A, B: VU마다 서로 다른 두 사람 (같은 사람이 겹치지 않게 2명씩 쓴다)
  const a = login(users[((__VU - 1) * 2) % users.length]);
  const b = login(users[((__VU - 1) * 2 + 1) % users.length]);
  if (!a || !b) return;

  const qa = enterQueue(a, { maxWaitSec: 120 });
  const qb = enterQueue(b, { maxWaitSec: 120 });
  if (!qa.ok || !qb.ok) {
    // 입장하지 못한 것은 우회 차단과 무관하다 (대기열 처리량은 시나리오 1·8에서 본다)
    leaveQueue(a);
    leaveQueue(b);
    return;
  }

  mustBlock(getSeatMap(a, null, { params: expectBlocked403 }));                                   // a) 토큰 없음
  mustBlock(getSeatMap(a, `forged-${__VU}-${Date.now()}`, { params: expectBlocked403 }));          // b) 위조 토큰
  mustBlock(getSeatMap(a, qb.entryToken, { params: expectBlocked403 }));                           // c) 남의 토큰
  mustBlock(getSeatMap(a, qa.entryToken, { runId: `${RUN_ID}9`, params: expectBlocked403 }));      // d) 다른 운행

  mustAllow(getSeatMap(a, qa.entryToken));                                                         // 정상 토큰
  mustAllow(getSeatMap(b, qb.entryToken));

  leaveQueue(a);                                                                                   // e) 이탈하면 즉시 무효
  mustBlock(getSeatMap(a, qa.entryToken, { params: expectBlocked403 }));
  mustAllow(getSeatMap(b, qb.entryToken));                                                         // 다른 사람의 이탈은 내 토큰에 영향 없음

  leaveQueue(b);
}
