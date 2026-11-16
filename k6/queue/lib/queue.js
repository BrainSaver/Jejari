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

export { parse };
