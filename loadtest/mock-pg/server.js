// 가짜 PG 서버 — 실제 PG처럼 "느리고, 몰리면 더 느려지고, 가끔 답이 없는" 결제대행사 (외부 의존성 없음, Node 18+)
//
// 앱의 MockPgClient는 같은 JVM 안에서 0초 만에 답한다. 그래서 "PG가 느릴 때 우리 서버가 버티나"를 잴 수 없다.
// 이 서버를 띄우고 앱을 jejari.payment.pg.mode=http 로 켜면, 앱은 실제 PG처럼 네트워크(HTTP)로 이 서버를 부른다.
//
// 결과 규칙은 MockPgClient와 같다 (카드번호 끝자리):
//   0 거절 · 8 승인되지만 응답이 늦게 옴(앱은 시간 초과 → 결과 모름 → 스캐너가 조회해 확정) · 9 결과 모름(응답 없음, 조회해도 모름)
//   그 외 승인
//
// 실제 PG처럼 보이게 하는 것 (PUT /_config 로 실행 중에 바꿀 수 있음)
//   capacity      PG가 동시에 처리하는 승인 수. 넘치면 줄을 서서 기다린다 → 몰릴수록 응답이 느려짐 (기본 50)
//   latencyMinMs  승인 한 건 처리 시간 최소 (기본 0)
//   latencyMaxMs  최대 (기본 0). 이 사이에서 무작위
//   hangRate      승인 응답을 hangMs 동안 붙잡아 두는 비율 0~1 (PG는 처리했는데 응답이 안 오는 경우, 기본 0)
//   hangMs        붙잡는 시간 (기본 30000)
//   queueMax      줄 길이 상한. 넘으면 바로 503 (PG 과부하 거절, 기본 10000)
//   inquiryMs     조회·주문 막기 응답 시간 (기본 30)
//
// 실행:   node loadtest/mock-pg/server.js                      (포트 8090, PG_PORT로 바꿈)
//         PG_LATENCY=500-1500 PG_CAPACITY=50 node loadtest/mock-pg/server.js
//         docker compose --profile pg up -d mock-pg            (compose.yaml)
// 상태:   curl localhost:8090/_stats       설정: curl -X PUT localhost:8090/_config -d '{"latencyMinMs":2000,"latencyMaxMs":4000}'
//         curl -X POST localhost:8090/_stats/reset  (통계만 비움, 주문은 그대로)
//
// 주문은 메모리에만 있다. 다시 켜면 조회 결과가 NOT_FOUND가 되고 앱 스캐너는 주문을 막은 뒤 실패로 정리한다 (§6.2 "없음").
'use strict';
const http = require('http');
const crypto = require('crypto');

const port = Number(process.env.PG_PORT || 8090);
const host = process.env.PG_HOST || '127.0.0.1';
const [envMin, envMax] = (process.env.PG_LATENCY || '0-0').split('-').map(Number);

const config = {
  capacity: Number(process.env.PG_CAPACITY || 50),
  latencyMinMs: envMin || 0,
  latencyMaxMs: envMax || envMin || 0,
  hangRate: Number(process.env.PG_HANG_RATE || 0),
  hangMs: Number(process.env.PG_HANG_MS || 30000),
  queueMax: Number(process.env.PG_QUEUE_MAX || 10000),
  inquiryMs: Number(process.env.PG_INQUIRY_MS || 30),
};

const orders = new Map();   // orderId → { state: APPROVED|FAILED|UNKNOWN|BLOCKED, pgTxId, reason }
const refunds = new Map();  // refundKey → { done, refundTxId }

// ── 처리 칸(capacity)과 줄 ─────────────────────────────
let inflight = 0;
const waiting = [];
function acquire() {
  if (inflight < config.capacity) {
    inflight++;
    return Promise.resolve();
  }
  if (waiting.length >= config.queueMax) return null;
  return new Promise((resolve) => waiting.push(resolve));
}
function release() {
  const next = waiting.shift();
  if (next) next(); // 칸을 그대로 넘겨줌 (inflight 그대로)
  else inflight--;
}

// ── 통계 ───────────────────────────────────────────────
let stats;
function resetStats() {
  stats = { since: new Date().toISOString(), approve: 0, approved: 0, declined: 0, unknown: 0, hung: 0, overloaded: 0,
    inquire: 0, block: 0, refund: 0, maxInflight: 0, maxWaiting: 0, totalMs: [] };
}
resetStats();
function pct(list, p) {
  if (list.length === 0) return 0;
  const s = [...list].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(s.length * p))];
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const lastDigit = (card) => (card ? String(card).slice(-1) : '');
const txId = () => `pg-${crypto.randomUUID()}`;

function decide(card) {
  switch (lastDigit(card)) {
    case '0': return { state: 'FAILED', pgTxId: null, reason: 'CARD_DECLINED' };
    case '9': return { state: 'UNKNOWN', pgTxId: null, reason: null };
    default: return { state: 'APPROVED', pgTxId: txId(), reason: null }; // 8도 PG 안에서는 승인
  }
}

async function approve(body) {
  const started = Date.now();
  stats.approve++;
  const slot = acquire();
  if (slot === null) {
    stats.overloaded++;
    return [503, { error: 'PG_OVERLOADED' }];
  }
  await slot;
  let order;
  let late;
  try {
    stats.maxInflight = Math.max(stats.maxInflight, inflight);
    stats.maxWaiting = Math.max(stats.maxWaiting, waiting.length);
    const span = Math.max(0, config.latencyMaxMs - config.latencyMinMs);
    await sleep(config.latencyMinMs + Math.random() * span);
    const id = String(body.orderId);
    order = orders.get(id);
    if (!order) {
      order = decide(body.cardNumber);
      orders.set(id, order);
    }
    // 같은 주문은 한 번만 처리 (실제 PG도 주문 번호 중복을 막음). 막힌 주문(BLOCKED)에 늦게 온 승인은 거절
    late = lastDigit(body.cardNumber) === '8' && order.state === 'APPROVED';
  } finally {
    release(); // 처리는 끝남. 아래 "응답이 늦게 감"은 PG 처리 칸을 차지하지 않는다 (네트워크·응답 쪽 지연)
  }
  const hang = late || order.state === 'UNKNOWN' || Math.random() < config.hangRate;
  if (hang) {
    stats.hung++;
    await sleep(config.hangMs); // 앱은 보통 이 전에 시간 초과로 끊는다
  }
  if (order.state === 'APPROVED') stats.approved++;
  else if (order.state === 'UNKNOWN') stats.unknown++;
  else stats.declined++;
  stats.totalMs.push(Date.now() - started);
  if (stats.totalMs.length > 200000) stats.totalMs.splice(0, 100000);
  const outcome = order.state === 'APPROVED' ? 'APPROVED' : order.state === 'UNKNOWN' ? 'UNKNOWN' : 'DECLINED';
  return [200, { outcome, pgTxId: order.pgTxId, reason: order.reason }];
}

async function inquire(orderId) {
  stats.inquire++;
  await sleep(config.inquiryMs);
  const o = orders.get(String(orderId));
  if (!o) return [200, { status: 'NOT_FOUND' }];
  const status = o.state === 'APPROVED' ? 'APPROVED' : o.state === 'UNKNOWN' ? 'UNKNOWN' : 'FAILED';
  return [200, { status, pgTxId: o.pgTxId, reason: o.reason }];
}

async function block(orderId) {
  stats.block++;
  await sleep(config.inquiryMs);
  const id = String(orderId);
  if (!orders.has(id)) orders.set(id, { state: 'BLOCKED', pgTxId: null, reason: 'PG_NOT_RECEIVED' });
  const s = orders.get(id).state;
  return [200, { blocked: s === 'BLOCKED' || s === 'FAILED' }];
}

async function refund(body) {
  stats.refund++;
  await sleep(config.inquiryMs);
  const done = refunds.get(body.refundKey);
  if (done) return [200, done];
  if (!body.pgTxId || Number(body.amount) < 0) return [200, { done: false, refundTxId: null, reason: 'NO_APPROVED_TX' }];
  const r = { done: true, refundTxId: `pg-refund-${crypto.randomUUID()}`, reason: null };
  refunds.set(body.refundKey, r); // 성공만 기억 (실패를 기억하면 재시도가 영원히 같은 실패를 받음)
  return [200, r];
}

function snapshot() {
  return { config, inflight, waiting: waiting.length, orders: orders.size, ...stats, totalMs: undefined,
    approveP50Ms: pct(stats.totalMs, 0.5), approveP95Ms: pct(stats.totalMs, 0.95), approveMaxMs: pct(stats.totalMs, 1) };
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => { data += c; if (data.length > 1e5) req.destroy(); });
    req.on('end', () => { try { resolve(data ? JSON.parse(data) : {}); } catch { resolve(null); } });
  });
}

const server = http.createServer(async (req, res) => {
  const send = ([status, body]) => {
    if (res.destroyed) return; // 앱이 시간 초과로 먼저 끊음
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };
  try {
    const url = new URL(req.url, 'http://pg');
    const p = url.pathname;
    const body = req.method === 'GET' ? {} : await readBody(req);
    if (body === null) return send([400, { error: 'BAD_JSON' }]);
    let m;
    if (req.method === 'POST' && p === '/v1/payments/approve') return send(await approve(body));
    if (req.method === 'GET' && (m = p.match(/^\/v1\/payments\/(\d+)$/))) return send(await inquire(m[1]));
    if (req.method === 'POST' && (m = p.match(/^\/v1\/payments\/(\d+)\/block$/))) return send(await block(m[1]));
    if (req.method === 'POST' && p === '/v1/refunds') return send(await refund(body));
    if (req.method === 'GET' && p === '/_stats') return send([200, snapshot()]);
    if (req.method === 'POST' && p === '/_stats/reset') { resetStats(); return send([200, snapshot()]); }
    if (req.method === 'GET' && p === '/_config') return send([200, config]);
    if (req.method === 'PUT' && p === '/_config') {
      for (const k of Object.keys(config)) if (body[k] !== undefined && Number.isFinite(Number(body[k]))) config[k] = Number(body[k]);
      if (config.latencyMaxMs < config.latencyMinMs) config.latencyMaxMs = config.latencyMinMs;
      config.capacity = Math.max(1, config.capacity);
      while (inflight < config.capacity && waiting.length) { inflight++; waiting.shift()(); }
      console.log(`[mock-pg] 설정 바꿈 ${JSON.stringify(config)}`);
      return send([200, config]);
    }
    if (req.method === 'GET' && p === '/health') return send([200, { ok: true }]);
    return send([404, { error: 'NOT_FOUND' }]);
  } catch (e) {
    console.error(e);
    return send([500, { error: 'INTERNAL' }]);
  }
});
server.keepAliveTimeout = 65000;
server.headersTimeout = 66000;
server.requestTimeout = 0; // hang을 흉내 내려면 서버가 먼저 끊지 않아야 함
server.listen(port, host, () => console.log(`[mock-pg] http://${host}:${port}  설정 ${JSON.stringify(config)}`));
