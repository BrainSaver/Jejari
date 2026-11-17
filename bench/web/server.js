#!/usr/bin/env node
// §17.1 락 비교 측정 러너 — 브라우저에서 버튼을 누르면 실제 k6를 돌린다.
//
// 정적 페이지가 아니다. 버튼 → k6 프로세스 실행 → stdout의 BENCH_RESULT 한 줄을 파싱 →
// 서버가 중앙값을 계산하고 중복 예약을 교차 검증 → SSE로 브라우저에 밀어 넣는다.
//
// 실행: node bench/web/server.js        (기본 http://localhost:8099)
// 필요: k6 (PATH 또는 K6_BIN), 개발 모드로 떠 있는 앱
//
// 의존성 없음 (Node 기본 모듈만). 팀원이 npm install 없이 바로 쓸 수 있어야 한다.

const http = require('http')
const fs = require('fs')
const path = require('path')
const { spawn } = require('child_process')

const PORT = Number(process.env.PORT || 8099)
const APP_URL = process.env.BASE_URL || 'http://localhost:8080'
const ROOT = path.resolve(__dirname, '..', '..')
const SCRIPT = path.join(ROOT, 'bench', 'k6', 'lock-compare.js')
const RESULTS_DIR = path.join(ROOT, 'bench', 'results')

// Python 측정(2026-11-16, bench/README.md)의 같은 시나리오 수치. 화면에서 나란히 비교한다
const SCENARIOS = [
  {
    id: 's1',
    title: '시나리오 1 · 같은 좌석 1개에 100명',
    note: '극한 경쟁',
    seatFrom: 5001, seatCount: 1, users: 100, vus: 50,
    expectOk: 1,
    python: { PESSIMISTIC: { tps: 255, avgMs: 146.9, p99Ms: 286.7, dbCalls: 201 },
              OPTIMISTIC: { tps: 1457, avgMs: 11.4, p99Ms: 16.3, dbCalls: 210 },
              REDIS: { tps: 2732, avgMs: 3.2, p99Ms: 8.2, dbCalls: 2 } },
  },
  {
    id: 's2',
    title: '시나리오 2 · 인기 운행 좌석 20개에 300명',
    note: '',
    seatFrom: 6001, seatCount: 20, users: 300, vus: 60,
    expectOk: 20,
    python: { PESSIMISTIC: { tps: 707, avgMs: 58.7, p99Ms: 125.0, dbCalls: 620 },
              OPTIMISTIC: { tps: 1825, avgMs: 15.8, p99Ms: 43.5, dbCalls: 626 },
              REDIS: { tps: 2083, avgMs: 10.5, p99Ms: 74.3, dbCalls: 40 } },
  },
  {
    id: 's3',
    title: '시나리오 3 · 좌석 500개에 500명',
    note: '경쟁 적음 — 예상과 달랐던 구간',
    seatFrom: 7001, seatCount: 500, users: 500, vus: 60,
    expectOk: null, // 좌석이 겹치는 만큼만 성공한다. 측정값을 그대로 쓴다
    python: { PESSIMISTIC: { tps: 787, avgMs: 66.3, p99Ms: 180.7, dbCalls: 1312 },
              OPTIMISTIC: { tps: 1256, avgMs: 35.5, p99Ms: 99.5, dbCalls: 1321 },
              REDIS: { tps: 756, avgMs: 65.7, p99Ms: 180.8, dbCalls: 624 } },
  },
]

const STRATEGIES = [
  { id: 'PESSIMISTIC', label: 'A 비관적 락', hint: 'SELECT … FOR UPDATE' },
  { id: 'OPTIMISTIC', label: 'B 낙관적 락', hint: 'version 조건부 UPDATE' },
  { id: 'REDIS', label: 'C 현재 설계', hint: 'Redis hold.lua + DB PK' },
]

// ───────────────────────────── 실행 상태 ─────────────────────────────

/** 지금 돌고 있는 측정. 한 번에 하나만 (동시에 두 개를 돌리면 수치가 서로를 망친다) */
let active = null
const clients = new Set()

function send(event, payload) {
  const line = `event: ${event}\ndata: ${JSON.stringify(payload)}\n\n`
  for (const res of clients) {
    try { res.write(line) } catch { clients.delete(res) }
  }
}

function log(text, kind) {
  send('log', { text, kind: kind || 'info', at: Date.now() })
}

// ───────────────────────────── 사전 점검 ─────────────────────────────

function k6Bin() {
  return process.env.K6_BIN || 'k6'
}

function run(cmd, args, opts) {
  return new Promise((resolve) => {
    let out = ''
    let err = ''
    let p
    try {
      p = spawn(cmd, args, { ...opts, shell: false })
    } catch (e) {
      return resolve({ code: -1, out: '', err: String(e.message) })
    }
    p.on('error', (e) => resolve({ code: -1, out, err: err + String(e.message) }))
    p.stdout.on('data', (d) => { out += d })
    p.stderr.on('data', (d) => { err += d })
    p.on('close', (code) => resolve({ code, out, err }))
  })
}

function appGet(urlPath) {
  return new Promise((resolve) => {
    const req = http.get(APP_URL + urlPath, { timeout: 4000 }, (res) => {
      let body = ''
      res.on('data', (d) => { body += d })
      res.on('end', () => resolve({ status: res.statusCode, body }))
    })
    req.on('timeout', () => { req.destroy(); resolve({ status: 0, body: '시간 초과' }) })
    req.on('error', (e) => resolve({ status: 0, body: e.message }))
  })
}

async function preflight() {
  const checks = []

  const v = await run(k6Bin(), ['version'])
  checks.push({
    name: 'k6',
    ok: v.code === 0,
    detail: v.code === 0 ? v.out.trim().split('\n')[0]
      : 'k6를 찾을 수 없습니다. winget install GrafanaLabs.k6 또는 K6_BIN에 경로를 지정하세요.',
  })

  const health = await appGet('/actuator/health')
  checks.push({
    name: '앱',
    ok: health.status === 200,
    detail: health.status === 200 ? `${APP_URL} 응답 정상`
      : `${APP_URL} 에 닿지 않습니다 (${health.status || '연결 실패'}). ` +
        './gradlew bootRun --args="--jejari.dev-mode=true" 로 띄우세요.',
  })

  // 개발 모드가 아니면 벤치 엔드포인트가 아예 없다 (404). 그걸로 판별한다
  const bench = await appGet('/api/v1/dev/bench/verify?runId=9001')
  checks.push({
    name: '개발 모드 · 벤치 API',
    ok: bench.status === 200,
    detail: bench.status === 200 ? '실험 테이블 접근 가능'
      : bench.status === 404
        ? '앱이 개발 모드가 아닙니다 (--jejari.dev-mode=true 가 필요합니다).'
        : `확인 실패 (${bench.status}): ${bench.body.slice(0, 160)}`,
  })

  checks.push({ name: '스크립트', ok: fs.existsSync(SCRIPT), detail: SCRIPT })
  return checks
}

// ───────────────────────────── 측정 ─────────────────────────────

function median(xs) {
  const a = xs.filter((x) => typeof x === 'number' && !Number.isNaN(x)).sort((x, y) => x - y)
  if (!a.length) return null
  const m = a.length >> 1
  return a.length % 2 ? a[m] : (a[m - 1] + a[m]) / 2
}

/** 회차 편차. cv = 표준편차 / 평균 (%). 10% 아래면 안정, 30% 넘으면 그 칸의 중앙값도 조심해서 읽는다 */
function spread(xs) {
  const a = xs.filter((x) => typeof x === 'number' && !Number.isNaN(x))
  if (!a.length) return null
  const mean = a.reduce((s, x) => s + x, 0) / a.length
  const sd = Math.sqrt(a.reduce((s, x) => s + (x - mean) ** 2, 0) / (a.length > 1 ? a.length - 1 : 1))
  return {
    min: Math.min(...a),
    max: Math.max(...a),
    cvPct: mean ? Math.round((sd / mean) * 1000) / 10 : 0,
    values: a,
  }
}

async function oneRound(scenario, strategy, seed) {
  const args = ['run', '--quiet', '--no-color',
    '-e', `BASE_URL=${APP_URL}`,
    '-e', `STRATEGY=${strategy}`,
    '-e', `SEAT_FROM=${scenario.seatFrom}`,
    '-e', `SEAT_COUNT=${scenario.seatCount}`,
    '-e', `USERS=${scenario.users}`,
    '-e', `VUS=${scenario.vus}`,
    '-e', `SEED=${seed}`,
    SCRIPT]

  const r = await run(k6Bin(), args, { cwd: ROOT })
  const line = (r.out.split('\n').find((l) => l.includes('BENCH_RESULT')) || '').trim()
  if (!line) {
    const why = (r.err || r.out || '').trim().split('\n').slice(-6).join(' | ')
    throw new Error(`k6 결과를 읽지 못했습니다 (종료 ${r.code}). ${why.slice(0, 400)}`)
  }
  return JSON.parse(line.slice(line.indexOf('BENCH_RESULT') + 'BENCH_RESULT'.length))
}

/** k6가 끝난 뒤 DB로 교차 검증한다 — 중복 예약은 앱이 아니라 데이터가 증명해야 한다 */
async function verify(runId) {
  const res = await appGet(`/api/v1/dev/bench/verify?runId=${runId}`)
  if (res.status !== 200) throw new Error(`검증 조회 실패 (${res.status})`)
  return JSON.parse(res.body)
}

async function measure(opts) {
  const { scenarioIds, strategyIds, rounds, seed } = opts
  const picked = SCENARIOS.filter((s) => scenarioIds.includes(s.id))
  const strats = STRATEGIES.filter((s) => strategyIds.includes(s.id))
  const total = picked.length * strats.length
  let done = 0

  log(`측정 시작 — 시나리오 ${picked.length}개 × 전략 ${strats.length}개 × ${rounds}회차 (워밍업 1회 제외)`)

  const results = []
  for (const sc of picked) {
    for (const st of strats) {
      if (active && active.cancelled) { log('사용자가 중지했습니다.', 'warn'); return results }
      send('cell-start', { scenario: sc.id, strategy: st.id })
      log(`${sc.title} / ${st.label} — 워밍업`)
      try {
        await oneRound(sc, st.id, seed)   // 워밍업은 버린다 (JIT·커넥션 풀 예열)

        const reps = []
        for (let i = 0; i < rounds; i++) {
          if (active && active.cancelled) { log('사용자가 중지했습니다.', 'warn'); return results }
          const r = await oneRound(sc, st.id, seed)
          reps.push(r)
          log(`  ${i + 1}/${rounds}회차 — TPS ${r.tps} · 성공 ${r.ok} · 거절 ${r.lost} · DB왕복 ${r.dbCalls}`)
          send('round', { scenario: sc.id, strategy: st.id, round: i + 1, rounds, result: r })
        }

        const v = await verify(9001)
        const cell = {
          scenario: sc.id,
          strategy: st.id,
          rounds,
          tps: median(reps.map((r) => r.tps)),
          avgMs: median(reps.map((r) => r.avgMs)),
          p95Ms: median(reps.map((r) => r.p95Ms)),
          p99Ms: median(reps.map((r) => r.p99Ms)),
          dbCalls: median(reps.map((r) => r.dbCalls)),
          ok: median(reps.map((r) => r.ok)),
          lost: median(reps.map((r) => r.lost)),
          err: reps.reduce((a, r) => a + r.err, 0),
          retries: median(reps.map((r) => r.retries)),
          lockWaitAvgMs: median(reps.map((r) => r.lockWaitAvgMs)),
          lockWaitP99Ms: median(reps.map((r) => r.lockWaitP99Ms)),
          tpsRange: [Math.min(...reps.map((r) => r.tps)), Math.max(...reps.map((r) => r.tps))],
          spread: {
            tps: spread(reps.map((r) => r.tps)),
            avgMs: spread(reps.map((r) => r.avgMs)),
            p99Ms: spread(reps.map((r) => r.p99Ms)),
          },
          duplicates: v.duplicates,
          python: sc.python[st.id] || null,
        }
        // 판정: 중복 0건 + 오류 0건 + (성공 수가 정해진 시나리오면) 그 수와 일치
        const okCount = cell.ok
        cell.checks = [
          { label: '중복 예약 0건', ok: cell.duplicates === 0, got: `${cell.duplicates}건` },
          { label: 'HTTP 오류 0건', ok: cell.err === 0, got: `${cell.err}건` },
          sc.expectOk === null
            ? { label: '성공 수 기록', ok: true, got: `${okCount}건 (좌석 겹침만큼)` }
            : { label: `성공 ${sc.expectOk}건`, ok: okCount === sc.expectOk, got: `${okCount}건` },
        ]
        cell.pass = cell.checks.every((c) => c.ok)
        results.push(cell)
        send('cell', cell)
        if (!cell.pass) {
          log(`  판정 미달: ${cell.checks.filter((c) => !c.ok).map((c) => c.label + ' → ' + c.got).join(', ')}`, 'error')
        }
      } catch (e) {
        log(`${sc.title} / ${st.label} 실패 — ${e.message}`, 'error')
        send('cell', { scenario: sc.id, strategy: st.id, error: e.message, pass: false })
      }
      done++
      send('progress', { done, total })
    }
  }
  return results
}

function saveResults(payload) {
  try {
    fs.mkdirSync(RESULTS_DIR, { recursive: true })
    const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
    const file = path.join(RESULTS_DIR, `lock-compare-${stamp}.json`)
    fs.writeFileSync(file, JSON.stringify(payload, null, 2), 'utf8')
    return file
  } catch (e) {
    log(`결과 저장 실패: ${e.message}`, 'warn')
    return null
  }
}

function history() {
  try {
    return fs.readdirSync(RESULTS_DIR)
      .filter((f) => f.startsWith('lock-compare-') && f.endsWith('.json'))
      .sort().reverse().slice(0, 20)
  } catch {
    return []
  }
}

// ───────────────────────────── HTTP ─────────────────────────────

function json(res, code, body) {
  const s = JSON.stringify(body)
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(s) })
  res.end(s)
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let b = ''
    req.on('data', (d) => {
      b += d
      if (b.length > 1e6) reject(new Error('요청이 너무 큽니다'))
    })
    req.on('end', () => {
      try { resolve(b ? JSON.parse(b) : {}) } catch (e) { reject(e) }
    })
  })
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`)

  if (url.pathname === '/' || url.pathname === '/index.html') {
    const p = path.join(__dirname, 'public', 'index.html')
    return fs.readFile(p, (e, data) => {
      if (e) { res.writeHead(500); return res.end('index.html 없음: ' + p) }
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
      res.end(data)
    })
  }

  if (url.pathname === '/api/config') {
    return json(res, 200, {
      appUrl: APP_URL,
      scenarios: SCENARIOS.map(({ python, ...s }) => ({ ...s, python })),
      strategies: STRATEGIES,
      running: !!(active && !active.finished),
      history: history(),
    })
  }

  if (url.pathname === '/api/preflight') {
    return json(res, 200, { checks: await preflight() })
  }

  if (url.pathname === '/api/events') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    res.write(': 연결됨\n\n')
    clients.add(res)
    const ping = setInterval(() => { try { res.write(': ping\n\n') } catch {} }, 20000)
    req.on('close', () => { clearInterval(ping); clients.delete(res) })
    return
  }

  if (url.pathname === '/api/stop' && req.method === 'POST') {
    if (active && !active.finished) active.cancelled = true
    return json(res, 200, { ok: true })
  }

  if (url.pathname === '/api/run' && req.method === 'POST') {
    if (active && !active.finished) return json(res, 409, { error: '이미 측정이 돌고 있습니다.' })

    let body
    try { body = await readBody(req) } catch (e) { return json(res, 400, { error: e.message }) }

    const scenarioIds = Array.isArray(body.scenarios) && body.scenarios.length
      ? body.scenarios : SCENARIOS.map((s) => s.id)
    const strategyIds = Array.isArray(body.strategies) && body.strategies.length
      ? body.strategies : STRATEGIES.map((s) => s.id)
    const rounds = Math.max(1, Math.min(15, Number(body.rounds) || 3))
    const seed = Number(body.seed) || 42

    const checks = await preflight()
    const bad = checks.filter((c) => !c.ok)
    if (bad.length) {
      return json(res, 400, { error: '사전 점검 실패', checks: bad })
    }

    active = { cancelled: false, finished: false, startedAt: Date.now() }
    json(res, 200, { started: true, total: scenarioIds.length * strategyIds.length, rounds })

    ;(async () => {
      let results = []
      try {
        results = await measure({ scenarioIds, strategyIds, rounds, seed })
      } catch (e) {
        log(`측정 중 오류: ${e.message}`, 'error')
      }
      active.finished = true
      const payload = {
        measuredAt: new Date().toISOString(),
        appUrl: APP_URL,
        rounds, seed,
        // 측정 환경 (예: "앱 CPU 0-1 · Oracle·Redis CPU 2 · k6 CPU 3"). 회차 편차를 읽을 때 같이 봐야 한다
        layout: process.env.BENCH_LAYOUT || null,
        note: 'k6 HTTP 수준 재측정. 실험 테이블(exp_seat·exp_seat_inv) 기준이라 Python 측정과 같은 조건.',
        results,
      }
      const file = saveResults(payload)
      log(file ? `끝. 결과를 ${path.relative(ROOT, file)} 에 저장했습니다.` : '끝.', 'done')
      send('finished', { results, file: file ? path.relative(ROOT, file) : null })
    })()
    return
  }

  if (url.pathname.startsWith('/api/results/')) {
    const name = path.basename(url.pathname)
    const p = path.join(RESULTS_DIR, name)
    if (!p.startsWith(RESULTS_DIR) || !fs.existsSync(p)) return json(res, 404, { error: '없음' })
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
    return res.end(fs.readFileSync(p))
  }

  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
  res.end('없음')
})

server.listen(PORT, () => {
  console.log(`락 비교 측정 러너  →  http://localhost:${PORT}`)
  console.log(`측정 대상 앱       →  ${APP_URL}   (개발 모드로 떠 있어야 합니다)`)
  console.log(`k6                 →  ${k6Bin()}`)
})
