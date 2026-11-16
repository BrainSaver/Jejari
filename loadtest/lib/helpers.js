import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  BASE_URL, API, FIELDS, HEADERS, RUN_ID, SEAT_PREFIX, AUTH_MODE,
} from '../config.js';

// ── CSV ─────────────────────────────────────────────────────
// open()은 시나리오(메인 스크립트)에서 호출하고 텍스트만 넘긴다.
export function parseCsv(text) {
  const lines = text.trim().split(/\r?\n/);
  const header = lines[0].split(',').map((s) => s.trim());
  return lines.slice(1).map((line) => {
    const cols = line.split(',');
    const row = {};
    header.forEach((h, i) => { row[h] = (cols[i] || '').trim(); });
    return row;
  });
}

// ── 공통 ────────────────────────────────────────────────────
export function url(path) {
  return `${BASE_URL}${path}`;
}

export function headers(accessToken, extra = {}) {
  const h = {
    'Content-Type': 'application/json',
    [HEADERS.loadTest]: '1',
  };
  if (accessToken) {
    // member-header: accessToken 자리에 회원 ID가 들어 있다 (개발 모드). jwt: 로그인으로 받은 토큰
    if (AUTH_MODE === 'jwt') h.Authorization = `Bearer ${accessToken}`;
    else h[HEADERS.memberId] = String(accessToken);
  }
  if (__ENV.LOCK_STRATEGY) h[HEADERS.lockStrategy] = __ENV.LOCK_STRATEGY;
  return Object.assign(h, extra);
}

export function seatIdFor(...parts) {
  return [SEAT_PREFIX, ...parts].join('-');
}

export function randBetween(min, max) {
  return min + Math.random() * (max - min);
}

export function think(minSec, maxSec) {
  sleep(randBetween(minSec, maxSec));
}

// ── 인증 ────────────────────────────────────────────────────
// 개발 모드(member-header)는 로그인 없이 회원 ID만 쓴다. data/users.csv 의 memberId 열(없으면 userId 숫자에 MEMBER_BASE를 더함).
const MEMBER_BASE = Number(__ENV.MEMBER_BASE || 5000000);

export function memberIdOf(user) {
  if (user.memberId) return String(user.memberId);
  const digits = String(user.userId || '').replace(/\D/g, '');
  return String(MEMBER_BASE + Number(digits || 0));
}

const tokenCache = {};

// 반환: member-header 모드는 회원 ID 문자열, jwt 모드는 액세스 토큰 (실패하면 null)
export function login(user) {
  if (AUTH_MODE !== 'jwt') return memberIdOf(user);
  if (tokenCache[user.loginId]) return tokenCache[user.loginId];
  const res = http.post(
    url(API.login),
    JSON.stringify({ loginId: user.loginId, password: user.password }),
    { headers: headers(), tags: { name: 'login' } }
  );
  const ok = check(res, { 'login 200': (r) => r.status === 200 });
  if (!ok) return null;
  const token = res.json(FIELDS.accessToken);
  tokenCache[user.loginId] = token;
  return token;
}

// ── 대기열 [실제 API] ───────────────────────────────────────
export function safeJson(res) {
  try {
    return res.json();
  } catch (e) {
    return {};
  }
}

// 진입 요청만 한다 (입장을 기다리지 않음). 많은 회원을 한꺼번에 줄 세운 뒤 waitUntilAdmitted로 기다릴 때 쓴다.
export function enterOnly(accessToken, runId = RUN_ID) {
  return http.post(url(API.queueEnter(runId)), null, {
    headers: headers(accessToken),
    tags: { name: 'queue_enter' },
  });
}

// 입장(ADMITTED)할 때까지 서버가 알려 준 간격(pollIntervalSec)으로 번호표를 조회한다.
// firstBody: 진입 응답 본문. 반환: { ok, entryToken, waitedSec, status, code }
export function waitUntilAdmitted(accessToken, firstBody, { maxWaitSec = 120, runId = RUN_ID, startedAt = Date.now() } = {}) {
  let body = firstBody || {};
  while (body[FIELDS.queueState] === FIELDS.waitingValue && (Date.now() - startedAt) / 1000 < maxWaitSec) {
    sleep(Math.max(0.2, Number(body[FIELDS.pollInterval]) || 1));
    const st = http.get(url(API.queueMe(runId)), {
      headers: headers(accessToken),
      tags: { name: 'queue_status' },
    });
    if (st.status !== 200) {
      return { ok: false, entryToken: null, waitedSec: (Date.now() - startedAt) / 1000, status: st.status, code: safeJson(st)[FIELDS.errorCode] };
    }
    body = safeJson(st);
  }
  const admitted = body[FIELDS.queueState] === FIELDS.admittedValue;
  return {
    ok: admitted,
    entryToken: admitted ? body[FIELDS.entryToken] : null,
    waitedSec: (Date.now() - startedAt) / 1000,
    status: 200,
    code: null,
  };
}

// 진입하고 입장할 때까지 기다린다.
export function enterQueue(accessToken, { maxWaitSec = 120, runId = RUN_ID } = {}) {
  const startedAt = Date.now();
  const enter = enterOnly(accessToken, runId);
  const body = safeJson(enter);
  if (enter.status !== 200) {
    return { ok: false, entryToken: null, waitedSec: 0, status: enter.status, code: body[FIELDS.errorCode] };
  }
  return waitUntilAdmitted(accessToken, body, { maxWaitSec, runId, startedAt });
}

export function leaveQueue(accessToken, runId = RUN_ID) {
  return http.del(url(API.queueMe(runId)), null, {
    headers: headers(accessToken),
    tags: { name: 'queue_leave' },
  });
}

// 입장 토큰으로 보호되는 좌석 배치도. 토큰이 없거나 틀리면 403 (QUEUE_TOKEN_REQUIRED / QUEUE_TOKEN_INVALID)
export function getSeatMap(accessToken, entryToken, { runId = RUN_ID, params = {} } = {}) {
  const h = entryToken ? { [HEADERS.entryToken]: entryToken } : {};
  return http.get(url(API.seatMap(runId)), Object.assign(
    { headers: headers(accessToken, h), tags: { name: 'seat_map' } }, params));
}

// ── 개발 모드 관리 API [실제, 개발 모드 전용] ──────────────────
// 시나리오 setup에서 호출한다. 개발 모드가 아니거나 mock이 아니면 404라서 사전 점검이 알려 준다.
export function devReset() {
  return http.post(url(API.devReset), null, { headers: headers(), tags: { name: 'dev_admin' } });
}

export function devSetRiskForMembers(from, count, score) {
  return http.put(url(`${API.devRiskMembers}?from=${from}&count=${count}&score=${score}`), null,
    { headers: headers(), tags: { name: 'dev_admin' } });
}

export function devSetPolicy(key, value) {
  return http.put(url(API.devPolicy(key)), JSON.stringify({ value: String(value) }),
    { headers: headers(), tags: { name: 'dev_admin' } });
}

// 시작 전에 앱·Redis 상태를 점검한다. 실패하면 이유를 알려 주며 중단한다 (setup에서 호출).
// "실패율 100%" 표만 남으면 원인을 오해하기 쉬워서 넣었다.
export function preflight(runId = RUN_ID) {
  const who = AUTH_MODE === 'jwt' ? null : '99999999';
  const res = http.post(url(API.queueEnter(runId)), null, { headers: headers(who), tags: { name: 'preflight' } });
  if (res.status === 200) {
    http.del(url(API.queueMe(runId)), null, { headers: headers(who), tags: { name: 'preflight' } });
    return;
  }
  const hints = {
    0: `${BASE_URL}에 연결하지 못했습니다. 서버가 떠 있는지, 주소·포트가 맞는지 확인하세요 (${res.error || '연결 오류'}).`,
    401: AUTH_MODE === 'jwt' ? '로그인이 필요합니다.' : '서버가 개발 모드가 아닙니다. --jejari.dev-mode=true 로 다시 띄우세요.',
    404: '다른 서버가 이 포트를 쓰고 있을 수 있습니다. 포트를 확인하세요 (Windows: netstat -ano | findstr :8080).',
    409: `운행 ${runId}이 판매 중이 아닙니다. 서버를 --jejari.queue.dev-on-sale-runs=${runId} 로 띄우거나 RUN_ID를 맞추세요.`,
    500: '서버 오류입니다. 서버 콘솔의 오류 메시지를 확인하세요.',
    503: 'Redis에 연결되지 않았습니다. Redis가 켜져 있는지 확인하세요 (docker start jejari-redis).',
  };
  throw new Error(`사전 점검 실패: 진입 요청이 ${res.status}를 돌려줬습니다. ${hints[res.status] || ''} 응답: ${String(res.body || '').slice(0, 200)}`);
}

// 좌석 선점·결제·취소표 시나리오(2~5, 7)용 사전 점검. 이 API들은 다른 파트가 구현하기 전이라 실제 앱에는 아직 없다.
export function preflightHoldApi(runId = RUN_ID) {
  preflight(runId);
  const who = AUTH_MODE === 'jwt' ? null : '99999999';
  const r = http.post(url(API.seatHold), JSON.stringify({ runId, seatId: 'preflight' }), {
    headers: headers(who), tags: { name: 'preflight' },
  });
  if (r.status === 404 || r.status === 0) {
    throw new Error(`이 서버에는 좌석 선점 API(${API.seatHold})가 아직 없습니다. 좌석·결제·취소표는 다른 파트가 구현하기 전이라, `
      + '환경 설정의 Mock 서버로 돌리거나 구현된 뒤에 config.js의 API 경로를 맞추세요.');
  }
}

// ── 좌석 / 결제 / 취소 ──────────────────────────────────────
export function holdSeat(accessToken, entryToken, seatId, extra = {}) {
  const h = entryToken ? { [HEADERS.entryToken]: entryToken } : {};
  return http.post(
    url(API.seatHold),
    JSON.stringify({ runId: RUN_ID, seatId }),
    { headers: headers(accessToken, Object.assign(h, extra)), tags: { name: 'seat_hold' } }
  );
}

export function pay(accessToken, reservationId, { idempotencyKey, mockPg } = {}) {
  const extra = {};
  if (idempotencyKey) extra[HEADERS.idempotency] = idempotencyKey;
  if (mockPg) extra[HEADERS.mockPg] = mockPg;
  return http.post(
    url(API.payment),
    JSON.stringify({ reservationId, amount: 10000 }),
    { headers: headers(accessToken, extra), tags: { name: 'payment' } }
  );
}

export function cancelReservation(accessToken, reservationId) {
  return http.post(url(API.cancel(reservationId)), null, {
    headers: headers(accessToken),
    tags: { name: 'cancel' },
  });
}

export function getSeatStatus(accessToken, seatId) {
  return http.get(url(API.seatStatus(RUN_ID, seatId)), {
    headers: headers(accessToken),
    tags: { name: 'seat_status' },
  });
}
