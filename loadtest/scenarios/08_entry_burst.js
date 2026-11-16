// 시나리오 8: 진입 API 처리 한계
// 줄에 선 뒤 입장을 기다리지 않으므로 VU가 묶이지 않아, k6가 목표 속도를 낼 수 있다.
// 초당 진입 수를 단계별로 올려 가며(PEAK_RATE ÷ STEPS씩) 어느 단계에서 느려지거나 실패하는지 찾는다.
//
//   예) PEAK_RATE=4000, STEPS=4 → 1000, 2000, 3000, 4000건/초를 각각 STEP_SEC초씩
//
// 회원은 단계마다 새로 만든다(회원 ID = MEMBER_BASE + 단계 × 100만 + 번호). data/users.csv 는 쓰지 않는다.
// 끝나면 Redis에 키가 수십만 개 남는다 → 앱의 개발용 초기화(POST /api/v1/dev/reset) 또는 mock 초기화로 비운다.
// (member-header 인증 전용. 단계 이름 step_1..step_N 은 앱의 핵심 지표 이름과 같다.)
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { url, headers, safeJson, preflight } from '../lib/helpers.js';
import { API, RUN_ID, FIELDS } from '../config.js';

const PEAK = Number(__ENV.PEAK_RATE || 4000);
const STEPS = Math.min(8, Math.max(1, Number(__ENV.STEPS || 4)));
const STEP_SEC = Number(__ENV.STEP_SEC || 10);
const GAP_SEC = Number(__ENV.GAP_SEC || 3);
const P95_MS = Number(__ENV.P95_MS || 500);        // 이 시간을 넘으면 "느려졌다"고 본다
const MEMBER_BASE = Number(__ENV.MEMBER_BASE || 4000000);

// 단계별 지표 (이름을 고정해 앱의 핵심 지표 표에 나오게 한다)
const okCounters = [];
const msTrends = [];
for (let i = 1; i <= STEPS; i++) {
  okCounters.push(new Counter(`entry_ok_step${i}`));     // 진입에 성공한 수 ÷ STEP_SEC = 실제 초당 진입
  msTrends.push(new Trend(`entry_ms_step${i}`, true));
}

const scenarios = {};
const thresholds = {};
for (let i = 1; i <= STEPS; i++) {
  const name = `step_${i}`;
  scenarios[name] = {
    executor: 'constant-arrival-rate',
    exec: 'entry',
    rate: Math.max(1, Math.round((PEAK * i) / STEPS)),
    timeUnit: '1s',
    duration: `${STEP_SEC}s`,
    startTime: `${(i - 1) * (STEP_SEC + GAP_SEC)}s`,
    preAllocatedVUs: Number(__ENV.PRE_VUS || 200),
    maxVUs: Number(__ENV.MAX_VUS || 1500),
    env: { STEP: String(i) },
  };
  // 통과 기준이면서 단계별 요약을 만들어 두는 항목
  thresholds[`http_req_duration{scenario:${name},name:queue_enter}`] = [`p(95)<${P95_MS}`];
  thresholds[`http_req_failed{scenario:${name},name:queue_enter}`] = ['rate<0.01'];
}

export const options = { scenarios, thresholds };

// 시작 전에 앱·Redis 상태를 점검한다 (실패하면 이유를 알려 주고 중단)
export function setup() {
  preflight();
}

export function entry() {
  const step = Number(__ENV.STEP);
  const id = MEMBER_BASE + step * 1000000 + exec.scenario.iterationInTest;
  const res = http.post(url(API.queueEnter(RUN_ID)), null, {
    headers: headers(String(id)),
    tags: { name: 'queue_enter' },
  });
  const ok = check(res, {
    '진입 200': (r) => r.status === 200,
    '순번 또는 토큰 존재': (r) => {
      const b = safeJson(r);
      return b.rank > 0 || !!b[FIELDS.entryToken];
    },
  });
  msTrends[step - 1].add(res.timings.duration);
  if (ok) okCounters[step - 1].add(1);
}
