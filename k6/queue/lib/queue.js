// 대기열 k6 시나리오 공용 함수. 개발 모드(X-Member-Id 헤더 인증)의 queue 앱을 대상으로 한다.
import http from 'k6/http';
import { sleep } from 'k6';

export const BASE = __ENV.BASE_URL || 'http://localhost:8080';
export const RUN = __ENV.RUN_ID || '1001';

export function headers(memberId, token) {
  const h = { 'X-Member-Id': String(memberId) };
  if (token) h['X-Queue-Token'] = token;
  return h;
}

export function enter(memberId, run = RUN, params = {}) {
  return http.post(`${BASE}/api/v1/runs/${run}/queue`, null,
    { headers: headers(memberId), tags: { name: 'queue_enter' }, ...params });
}

export function me(memberId, run = RUN, params = {}) {
  return http.get(`${BASE}/api/v1/runs/${run}/queue/me`,
    { headers: headers(memberId), tags: { name: 'queue_me' }, ...params });
}

export function leave(memberId, run = RUN, params = {}) {
  return http.del(`${BASE}/api/v1/runs/${run}/queue/me`, null,
    { headers: headers(memberId), tags: { name: 'queue_leave' }, ...params });
}

export function seats(memberId, token, run = RUN, params = {}) {
  return http.get(`${BASE}/api/v1/runs/${run}/seats`,
    { headers: headers(memberId, token), tags: { name: 'seats' }, ...params });
}

function parse(res) {
  try {
    return res.json();
  } catch (e) {
    return {};
  }
}

/**
 * 입장할 때까지 서버가 알려 준 간격(pollIntervalSec)으로 번호표를 조회한다.
 * 반환: { admitted, token, body, waitedMs }
 */
export function waitUntilAdmitted(memberId, first, maxWaitMs, run = RUN) {
  const start = Date.now();
  let body = parse(first);
  while (body.state === 'WAITING' && Date.now() - start < maxWaitMs) {
    sleep(Math.max(0.2, body.pollIntervalSec || 1));
    const res = me(memberId, run);
    if (res.status !== 200) {
      return { admitted: false, token: null, body: parse(res), waitedMs: Date.now() - start };
    }
    body = parse(res);
  }
  return {
    admitted: body.state === 'ADMITTED',
    token: body.passToken || null,
    body,
    waitedMs: Date.now() - start,
  };
}

/**
 * 시나리오 시작 전에 진입 요청을 한 번 해 보고, 실패하면 이유를 알려 주며 중단한다 (각 시나리오의 setup에서 호출).
 * 앱이 안 떠 있거나 Redis가 꺼진 채로 부하를 걸면 "실패율 100%" 표만 남아 결과를 오해하기 쉽다.
 */
export function preflight(run = RUN) {
  const id = 99999999;
  const res = enter(id, run);
  if (res.status === 200) {
    leave(id, run);
    return;
  }
  const hints = {
    0: `${BASE}에 연결하지 못했습니다. 앱이 떠 있는지, 포트가 맞는지 확인하세요 (${res.error || '연결 오류'}).`,
    401: '앱이 개발 모드가 아닙니다. --jejari.dev-mode=true 로 다시 띄우세요.',
    404: '다른 서버가 이 포트를 쓰고 있을 수 있습니다. netstat -ano | findstr :8080 으로 확인하세요.',
    409: `운행 ${run}이 판매 중이 아닙니다. 앱을 --jejari.queue.dev-on-sale-runs=${run} 로 띄우세요.`,
    500: '서버 오류입니다. 앱 콘솔의 오류 메시지를 확인하세요.',
    503: 'Redis에 연결되지 않았습니다. Redis가 켜져 있는지(docker start jejari-redis) 확인하세요.',
  };
  const body = String(res.body || '').slice(0, 200);
  throw new Error(`사전 점검 실패: 진입 요청이 ${res.status}를 돌려줬습니다. ${hints[res.status] || ''} 응답: ${body}`);
}

export { parse };
