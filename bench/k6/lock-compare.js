// §17.1 좌석 선점 락 방식 비교 — k6로 HTTP 수준 재측정
//
// 왜 다시 재나: 기존 측정(bench/lock_compare.py)은 Python 스레드로 부하를 줬고, bench/README.md가
// 그 한계를 "GIL 때문에 클라이언트 쪽 병목이 섞인다 — 뼈대가 올라오면 k6로 HTTP 레벨에서 다시
// 재야 한다"고 적어 뒀다. 고쳐야 할 것은 부하 생성기이므로 재는 대상(실험 테이블·락 방식)은
// 그대로 두고 부하만 k6로 바꾼다. 그래야 기존 수치와 사과 대 사과 비교가 된다.
//
// 한 번 실행 = 한 전략 × 한 시나리오 × 1회차. 회차 반복과 중앙값은 러너(bench/web/server.js)가 한다.
//
// 실행 (앱이 개발 모드로 떠 있어야 한다):
//   k6 run -e STRATEGY=REDIS -e SEAT_FROM=5001 -e SEAT_COUNT=1 -e USERS=100 -e VUS=50 \
//          bench/k6/lock-compare.js

import http from 'k6/http'
import exec from 'k6/execution'
import { Counter, Trend } from 'k6/metrics'

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080'
const STRATEGY = __ENV.STRATEGY || 'REDIS'
const RUN_ID = Number(__ENV.RUN_ID || 9001)
const SEAT_FROM = Number(__ENV.SEAT_FROM || 5001)
const SEAT_COUNT = Number(__ENV.SEAT_COUNT || 1)
const USERS = Number(__ENV.USERS || 100)
const VUS = Number(__ENV.VUS || 50)
const SEED = Number(__ENV.SEED || 42)

const latency = new Trend('hold_latency', true)
const lockWait = new Trend('hold_lock_wait', true)
// 경쟁이 실제로 벌어진 창을 재려고 요청 시각의 최솟값·최댓값을 모은다.
// k6의 iterations.rate는 setup()·teardown()까지 포함한 전체 시간으로 나눠서,
// 경쟁 자체가 수십 ms인 시나리오 1에서는 왜곡이 크다 (Python은 ThreadPool 구간만 쟀다).
const tStart = new Trend('hold_t_start')
const tEnd = new Trend('hold_t_end')
const okC = new Counter('hold_ok')
const lostC = new Counter('hold_lost')
const errC = new Counter('hold_err')
const dbC = new Counter('hold_db_calls')
const retryC = new Counter('hold_retries')

export const options = {
  scenarios: {
    race: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: USERS,
      maxDuration: '3m',
    },
  },
  // 측정이 목적이라 k6가 스스로 통과/실패를 매기지 않는다. 판정은 러너가 중복 0건·성공 수로 한다
  thresholds: {},
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: false,
}

/** 재현 가능한 난수 (전략 세 개가 똑같은 좌석 배정을 받아야 공정하다) */
function lcg(seed) {
  let s = seed >>> 0
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0
    return s / 4294967296
  }
}

export function setup() {
  const seatIds = []
  for (let i = 0; i < SEAT_COUNT; i++) seatIds.push(SEAT_FROM + i)

  const res = http.post(`${BASE_URL}/api/v1/dev/bench/reset`,
    JSON.stringify({ runId: RUN_ID, seatIds }),
    { headers: { 'Content-Type': 'application/json' } })
  if (res.status !== 200) {
    throw new Error(
      `준비 실패 (${res.status}). 앱이 개발 모드로 떠 있는지 확인하세요 — ` +
      `./gradlew bootRun --args="--jejari.dev-mode=true". 응답: ${String(res.body).slice(0, 200)}`)
  }

  // 요청 i가 고를 좌석. 전략을 바꿔도 SEED가 같으면 같은 배정이다
  const rand = lcg(SEED)
  const picks = []
  for (let i = 0; i < USERS; i++) picks.push(seatIds[Math.floor(rand() * seatIds.length)])
  return { picks }
}

export default function (data) {
  const i = exec.scenario.iterationInTest
  const seatId = data.picks[i]

  tStart.add(Date.now())
  const res = http.post(`${BASE_URL}/api/v1/dev/bench/hold`,
    JSON.stringify({ runId: RUN_ID, seatId, strategy: STRATEGY }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'hold' } })
  tEnd.add(Date.now())

  latency.add(res.timings.duration)

  if (res.status !== 200) {
    errC.add(1)
    return
  }
  let body
  try {
    body = res.json()
  } catch (e) {
    errC.add(1)
    return
  }
  if (body.result === 'ok') okC.add(1)
  else if (body.result === 'lost') lostC.add(1)
  else errC.add(1)

  dbC.add(body.dbCalls || 0)
  retryC.add(body.retries || 0)
  if (body.lockWaitMs) lockWait.add(body.lockWaitMs)
}

function num(metrics, name, field, fallback) {
  const m = metrics[name]
  if (!m || !m.values || m.values[field] === undefined) return fallback
  return m.values[field]
}

export function handleSummary(data) {
  const m = data.metrics
  const windowMs = num(m, 'hold_t_end', 'max', 0) - num(m, 'hold_t_start', 'min', 0)
  // Trend에는 count가 없다 (Counter·Rate에만 있다). 처리한 요청 수는 결과 카운터를 더해 센다
  const ok = num(m, 'hold_ok', 'count', 0)
  const lost = num(m, 'hold_lost', 'count', 0)
  const err = num(m, 'hold_err', 'count', 0)
  const done = ok + lost + err

  const out = {
    strategy: STRATEGY,
    users: USERS,
    vus: VUS,
    seatCount: SEAT_COUNT,
    // 경쟁 구간만으로 계산한다 (setup 제외). 창이 0이면 TPS를 내지 않는다
    windowMs: Math.round(windowMs),
    tps: windowMs > 0 ? +(done / (windowMs / 1000)).toFixed(0) : null,
    done,
    ok,
    lost,
    err,
    dbCalls: num(m, 'hold_db_calls', 'count', 0),
    retries: num(m, 'hold_retries', 'count', 0),
    avgMs: +num(m, 'hold_latency', 'avg', 0).toFixed(1),
    p95Ms: +num(m, 'hold_latency', 'p(95)', 0).toFixed(1),
    p99Ms: +num(m, 'hold_latency', 'p(99)', 0).toFixed(1),
    maxMs: +num(m, 'hold_latency', 'max', 0).toFixed(1),
    lockWaitAvgMs: m.hold_lock_wait ? +num(m, 'hold_lock_wait', 'avg', 0).toFixed(1) : null,
    lockWaitP99Ms: m.hold_lock_wait ? +num(m, 'hold_lock_wait', 'p(99)', 0).toFixed(1) : null,
    httpFailed: num(m, 'http_req_failed', 'passes', 0),
    droppedIterations: num(m, 'dropped_iterations', 'count', 0),
  }

  // 러너가 stdout에서 이 한 줄만 집어 간다
  const line = `BENCH_RESULT ${JSON.stringify(out)}\n`
  const files = { stdout: line }
  if (__ENV.OUT) files[__ENV.OUT] = JSON.stringify(out, null, 2)
  return files
}
