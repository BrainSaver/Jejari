// 대기열 k6 시나리오 공용 함수. 개발 모드(X-Member-Id 헤더 인증)의 queue 앱을 대상으로 한다.
import http from 'k6/http';
import { sleep } from 'k6';

export const BASE = __ENV.BASE_URL || 'http://localhost:8080';
export const RUN = __ENV.RUN_ID || '9001';

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
    503: 'Redis에 연결되지 않았습니다. Redis가 켜져 있는지(docker compose up -d redis) 확인하세요.',
  };
  const body = String(res.body || '').slice(0, 200);
  throw new Error(`사전 점검 실패: 진입 요청이 ${res.status}를 돌려줬습니다. ${hints[res.status] || ''} 응답: ${body}`);
}

/**
 * 입장 토큰으로 좌석 배치도를 한 번 불러 본다 (좌석 배치도를 호출하는 시나리오의 setup에서 preflight() 다음에 부른다).
 *
 * 좌석 배치도(GET /api/v1/runs/{runId}/seats)는 개발용 대용품이 아니라 실제 API(SeatMapController)라서
 * Oracle의 운행·좌석 시드를 읽는다. 시드에 없는 운행이거나 Oracle에 문제가 있으면 "정상 토큰인데 200이 아님"이
 * 대기열 문제처럼 보이므로, 시작 전에 따로 확인해 원인을 알려 준다.
 */
export function preflightSeatMap(run = RUN) {
  const id = 99999998;
  const first = enter(id, run);
  if (first.status !== 200) {
    throw new Error(`사전 점검 실패(좌석 배치도): 진입 요청이 ${first.status}를 돌려줬습니다. 응답: ${String(first.body || '').slice(0, 160)}`);
  }
  const w = waitUntilAdmitted(id, first, 20000, run);
  if (!w.admitted) {
    leave(id, run);
    throw new Error('사전 점검 실패(좌석 배치도): 20초 안에 입장하지 못했습니다. 입장 속도(queue.admit_per_sec)가 0이거나 입장 스케줄러가 꺼져 있는지 확인하세요.');
  }
  const res = seats(id, w.token, run);
  leave(id, run);
  if (res.status === 200) return;
  const hints = {
    404: `운행 ${run}을 Oracle에서 찾지 못했습니다. 좌석 배치도는 실제 API라 시드(db/seed)에 있는 운행이어야 합니다 (판매 중 운행은 9001·9002). RUN_ID를 맞추거나 Oracle 시드가 적재됐는지 확인하세요: docker compose logs oracle | findstr /c:"jejari seed loaded"`,
    500: '서버 오류입니다. Oracle 연결·스키마를 확인하세요 (docker compose ps에서 oracle이 healthy인지, 앱 콘솔의 오류 메시지).',
    503: 'Redis·Oracle에 연결되지 않았습니다. docker compose ps로 확인하세요.',
  };
  throw new Error(`사전 점검 실패(좌석 배치도): 정상 토큰으로 ${res.status}를 받았습니다. ${hints[res.status] || ''} 좌석 배치도 호출을 빼고 대기열만 재려면 -e SEAT_CALL=no 로 실행하세요.`);
}

/** 좌석 배치도를 호출할지. no로 하면 대기열 비용만 잰다 (좌석 배치도는 Oracle을 읽고 응답이 커서 대기열 수치를 흐린다) */
export const SEAT_CALL = (__ENV.SEAT_CALL || 'yes') !== 'no';

export { parse };
