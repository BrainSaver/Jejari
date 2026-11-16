// 시나리오 4. 진입 API의 처리 한계. 줄에 선 뒤 입장을 기다리지 않으므로 VU가 묶이지 않아 k6가 목표 속도를 낼 수 있다.
// 초당 진입 수를 단계별로 올려 가며, 어느 단계에서 지연이 튀거나 실패가 생기는지 찾는다.
//
// 실행:  k6 run entry-burst.js
//        k6 run -e RATES=500,1000,2000,4000,8000 -e STEP_SEC=20 entry-burst.js
// 끝난 뒤에는 ./reset.sh로 쌓인 대기열을 지운다 (회원마다 줄을 서고 이탈하지 않으므로 Redis에 남는다).
//
// 한 단계 = 목표 속도로 STEP_SEC초 동안 새 회원이 한 번씩 진입. 단계 사이에는 GAP_SEC초 쉰다.
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { enter, leave, parse, preflight } from './lib/queue.js';

const RATES = (__ENV.RATES || '500,1000,2000,4000').split(',').map((s) => Number(s.trim()));
const STEP_SEC = Number(__ENV.STEP_SEC || 20);
const GAP_SEC = Number(__ENV.GAP_SEC || 5);
const P95_MS = Number(__ENV.P95_MS || 500);                // 이 시간을 넘으면 "느려졌다"고 본다
const MEMBER_BASE = Number(__ENV.MEMBER_BASE || 4000000);
const LEAVE = __ENV.LEAVE === '1';                         // 1이면 진입 직후 이탈 (Redis는 덜 쌓이지만 요청이 하나 더 든다)

const entryOk = new Counter('entry_ok');

const scenarios = {};
const thresholds = {};
RATES.forEach((rate, i) => {
  const name = `step_${rate}`;
  scenarios[name] = {
    executor: 'constant-arrival-rate',
    exec: 'entry',
    rate,
    timeUnit: '1s',
    duration: `${STEP_SEC}s`,
    startTime: `${i * (STEP_SEC + GAP_SEC)}s`,
    preAllocatedVUs: Number(__ENV.PRE_VUS || 200),
    maxVUs: Number(__ENV.MAX_VUS || 1000),
    env: { STEP: String(i) },
  };
  // 단계별 지표를 만들어 두기 위한 항목 (handleSummary에서 읽는다). 앞의 두 개는 실제 통과 기준.
  thresholds[`http_req_duration{scenario:${name},name:queue_enter}`] = [`p(95)<${P95_MS}`];
  thresholds[`http_req_failed{scenario:${name},name:queue_enter}`] = ['rate<0.01'];
  thresholds[`entry_ok{scenario:${name}}`] = ['count>=0'];
  thresholds[`dropped_iterations{scenario:${name}}`] = ['count>=0'];
});

export const options = {
  scenarios,
  thresholds,
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// 시작 전에 앱·Redis 상태를 점검한다 (실패하면 이유를 알려 주고 중단)
export function setup() {
  preflight();
}

export function entry() {
  // 단계마다 회원 ID 구간을 나눠 겹치지 않게 한다 (단계당 최대 100만 건)
  const id = MEMBER_BASE + Number(__ENV.STEP) * 1000000 + exec.scenario.iterationInTest;
  const res = enter(id);
  const ok = check(res, {
    '진입 200': (r) => r.status === 200,
    '번호표 발급': (r) => parse(r).ticketNo > 0,
  });
  if (ok) entryOk.add(1);
  if (LEAVE) leave(id);
}

export function handleSummary(data) {
  const val = (key, stat) => {
    const m = data.metrics[key];
    return m && m.values ? m.values[stat] : undefined;
  };
  const rows = RATES.map((rate) => {
    const sc = `step_${rate}`;
    const dur = `http_req_duration{scenario:${sc},name:queue_enter}`;
    const sent = val(`entry_ok{scenario:${sc}}`, 'count') || 0;
    const dropped = val(`dropped_iterations{scenario:${sc}}`, 'count') || 0;
    const failRate = val(`http_req_failed{scenario:${sc},name:queue_enter}`, 'rate') || 0;
    const p95 = val(dur, 'p(95)');
    const reasons = [];
    if (failRate >= 0.01) reasons.push('실패');
    if (p95 !== undefined && p95 >= P95_MS) reasons.push('느려짐');
    if (sent < rate * STEP_SEC * 0.95) reasons.push('목표 미달');
    return {
      rate,
      actual: sent / STEP_SEC,
      failRate,
      p95,
      p99: val(dur, 'p(99)'),
      max: val(dur, 'max'),
      dropped,
      verdict: reasons.length === 0 ? 'OK' : reasons.join('+'),
    };
  });

  const f = (v, d = 1) => (v === undefined ? '-' : v.toFixed(d));
  const lines = [
    '',
    `=== 진입 API 한계 (단계당 ${STEP_SEC}초, 기준: p95 < ${P95_MS}ms, 실패 < 1%, 목표의 95% 이상 진입) ===`,
    '목표(건/초)  실제(건/초)  실패율   p95(ms)  p99(ms)  최대(ms)  못 보낸  판정',
  ];
  for (const r of rows) {
    lines.push(
      [
        String(r.rate).padStart(10),
        f(r.actual).padStart(12),
        `${f(r.failRate * 100)}%`.padStart(7),
        f(r.p95).padStart(8),
        f(r.p99).padStart(8),
        f(r.max, 0).padStart(9),
        String(r.dropped).padStart(7),
        `  ${r.verdict}`,
      ].join('  '),
    );
  }
  const firstBad = rows.find((r) => r.verdict !== 'OK');
  lines.push(
    '',
    firstBad
      ? `처음 기준을 넘은 단계: 목표 ${firstBad.rate}건/초 (${firstBad.verdict})`
      : `모든 단계가 기준 안: 한계는 ${RATES[RATES.length - 1]}건/초보다 위 (RATES를 더 올려 볼 것)`,
    '못 보낸 요청은 응답이 느려져 k6의 VU가 모자란 경우에 생긴다 (MAX_VUS를 올려도 PC 한 대의 한계가 있음).',
    '이 PC에서 k6·앱·Redis가 같이 도는 값이라 서버만의 한계는 아니다.',
    '끝난 뒤 ./reset.sh 로 대기열을 비울 것.',
    '',
  );
  return { stdout: lines.join('\n') };
}
