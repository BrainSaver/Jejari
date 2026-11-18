// 시나리오 11: 정상 사용자 전체 흐름 — 실제 앱 (DATA-03의 "정상 사용자" 쪽, PERF-01 기준 수치)
//
// 한 VU = 실제 회원 한 명. 가입된 계정으로 아래 흐름을 한 번 끝까지 간다.
//   로그인(JWT) → 열차 조회(역 목록·운행 상세) → 대기열 진입·입장 대기 → 좌석 배치도(입장 토큰)
//   → 좌석 선점(빈 좌석 중 무작위, 이미 잡혔으면 다른 좌석으로 최대 HOLD_RETRY번) → 결제(모의 PG 승인) → 대기열 나가기
//
// 무엇을 보나
//   - 사람 한 명이 예매를 끝내는 비율(journey_success)과 걸린 시간(journey_ms), 단계별 응답 시간(http_req_duration{name:…})
//   - 정상 사용자끼리 같은 좌석을 고른 횟수(seat_conflict): 좌석을 무작위로 골라도 생긴다. 오류가 아니라 경합 지표
//   - 같은 수치를 Grafana "제자리 · 앱 개요"와 함께 보면 어느 단계가 병목인지 찾을 수 있다 (개선 전후 비교의 기준)
//
// 준비 (실제 앱만, Mock 서버는 이 흐름을 흉내 내지 않음)
//   1) docker compose up -d (oracle healthy, 시드의 판매 중 운행 9001)
//   2) .env에 비밀값 3종 (JEJARI_HMAC_KEY·JEJARI_ENC_KEYS·JEJARI_PEPPER_KEYS)
//   3) 앱을 신뢰 프록시 옵션으로 실행 (계정을 만들 때 IP당 가입 제한을 피하려고 요청마다 다른 IP를 X-Forwarded-For로 보냄)
//        ./gradlew bootRun --args="--jejari.auth.trusted-proxies=127.0.0.1,0:0:0:0:0:0:0:1"
//   4) ./run.sh 11   (또는 ./run.sh journey -e USERS=100 -e SPREAD_SEC=30)
//
// 끝나면: Oracle에 @loadtest.example.com 회원과 결제된 예약(좌석 SOLD)이 남는다. 반복해서 돌리면 빈 좌석이 줄어든다.
// 깨끗이 다시 하려면 Oracle 볼륨을 초기화한다 (loadtest/README.md 시나리오 10의 "끝난 뒤").
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import { RUN_ID } from '../config.js';
import { identity, fakeIp, verify, signup, login, authSmokeTest, fail } from '../lib/auth.js';
import { browse, queueUntilAdmitted, queueLeave, emptySeats, hold, pay, pick, codeOf, uuid } from '../lib/journey.js';
import { safeJson } from '../lib/helpers.js';

const USERS = Math.min(2000, Math.max(1, Number(__ENV.USERS || 30)));
const SPREAD_SEC = Math.max(0, Number(__ENV.SPREAD_SEC || 20));     // 사용자가 이 시간 안에 흩어져 도착
const FROM = __ENV.FROM || '3900023';                                // 서울 (시드)
const TO = __ENV.TO || '3900114';                                    // 부산 (시드)
const PAY = (__ENV.PAY || 'yes') !== 'no';
const CARD = __ENV.CARD || '4111111111111111';                       // 끝자리 1 = 모의 PG 승인
const HOLD_RETRY = Math.max(1, Number(__ENV.HOLD_RETRY || 3));
const MAX_WAIT_SEC = Number(__ENV.MAX_WAIT_SEC || 120);
const P95_MS = Number(__ENV.P95_MS || 1000);                         // 로그인 외 단계의 p95 기준
const LOGIN_P95_MS = Number(__ENV.LOGIN_P95_MS || 2000);              // Argon2id는 일부러 느림
const SUCCESS_RATE = Number(__ENV.SUCCESS_RATE || 0.95);

// identity의 seq 영역 (0~9999). 시나리오 10과 같은 함수를 쓰지만 실행 태그가 달라 겹치지 않는다
const SEQ_SMOKE = 999;   // authSmokeTest가 쓰는 번호. 사용자 번호는 0부터, 999를 건너뜀
const seqOf = (i) => (i >= SEQ_SMOKE ? i + 1 : i);

const journeyOk = new Rate('journey_success');            // 예매를 끝까지 마친 비율
const journeyMs = new Trend('journey_ms', true);           // 로그인부터 결제까지 (도착 대기 제외)
const queueWait = new Trend('queue_wait_sec');             // 진입 → 입장까지
const seatConflict = new Counter('seat_conflict');         // 고른 좌석이 그 사이 잡힘 (정상 경합)
const failedAt = new Counter('journey_failed');            // 단계 태그(stage)로 어디서 멈췄는지
const soldOut = new Counter('journey_sold_out');           // 빈 좌석이 없음 (반복 실행으로 좌석이 다 팔린 경우)

export const options = {
  setupTimeout: '15m', // 계정 만들기 (Argon2id라 한 명에 수백 ms)
  scenarios: {
    journey: {
      executor: 'per-vu-iterations',
      vus: USERS,
      iterations: 1,
      maxDuration: `${SPREAD_SEC + MAX_WAIT_SEC + 120}s`,
    },
  },
  thresholds: {
    journey_success: [`rate>=${SUCCESS_RATE}`],
    'http_req_duration{name:login}': [`p(95)<${LOGIN_P95_MS}`],
    'http_req_duration{name:queue_enter}': [`p(95)<${P95_MS}`],
    'http_req_duration{name:seat_map}': [`p(95)<${P95_MS}`],
    'http_req_duration{name:hold}': [`p(95)<${P95_MS}`],
    'http_req_duration{name:pay}': [`p(95)<${P95_MS * 2}`],   // 모의 PG 호출 포함
  },
};

export function setup() {
  const tag = (__ENV.RUN_TAG || Math.random().toString(36).slice(2, 6)).toLowerCase();
  authSmokeTest(tag); // 가입 → 로그인 한 번 (비밀값·Oracle·IP 제한 문제를 한 줄 원인으로)

  // 사용자 계정 만들기 (본인인증 + 가입). 실패하면 이유와 함께 중단
  for (let i = 0; i < USERS; i++) {
    const id = identity(tag, seqOf(i));
    const ip = fakeIp(100 + i);
    const v = verify(id, { ip, tag: 'setup_verify' });
    if (!v.token) fail(`계정 ${i + 1}/${USERS} 본인인증`, v.res);
    const s = signup(v.token, id, { ip, tag: 'setup_signup' });
    if (s.status !== 201) fail(`계정 ${i + 1}/${USERS} 가입`, s);
  }

  // 대기열이 이 운행을 받는지 한 번 확인 (판매 중이 아니면 모든 VU가 같은 이유로 실패하므로 미리 알려 줌)
  const first = identity(tag, seqOf(0));
  const l = login(first.email, first.password, { ip: fakeIp(50000), tag: 'preflight' });
  if (l.status !== 200) fail('로그인', l);
  const token = (safeJson(l) || {}).accessToken;
  const q = queueUntilAdmitted(token, { maxWaitSec: 30 });
  if (!q.ok) {
    const why = {
      RUN_NOT_ON_SALE: `운행 ${RUN_ID}이 판매 중이 아닙니다. 시드(db/seed)의 ON_SALE 운행(9001·9002)인지, 판매 기간이 지나지 않았는지 확인하세요 (-e RUN_ID=...).`,
      QUEUE_WAIT_TIMEOUT: '30초 안에 입장하지 못했습니다. 다른 부하가 돌고 있지 않은지, 입장 스케줄러(jejari.queue.scheduler-enabled)가 켜져 있는지 확인하세요.',
    };
    throw new Error(`사전 점검 실패(대기열): ${q.status} ${q.code}. ${why[q.code] || ''}`);
  }
  const map = emptySeats(token, q.passToken, { from: FROM, to: TO });
  queueLeave(token);
  if (!map.seats) throw new Error(`사전 점검 실패(좌석 배치도): ${map.res.status} ${codeOf(map.res)}. FROM=${FROM}, TO=${TO}가 운행 ${RUN_ID}의 정차역인지 확인하세요.`);
  if (map.seats.length < USERS) {
    console.warn(`빈 좌석 ${map.seats.length}석 < 사용자 ${USERS}명: 일부는 매진(journey_sold_out)으로 끝납니다.`);
  }
  return { tag };
}

export default function (data) {
  const i = exec.vu.idInTest - 1;
  const id = identity(data.tag, seqOf(i));
  const ip = fakeIp(60000 + i);
  sleep(Math.random() * SPREAD_SEC); // 도착 시각을 흩뜨림

  const started = Date.now();
  const stop = (stage) => {
    failedAt.add(1, { stage });
    journeyOk.add(false);
  };

  // 1) 로그인
  const l = login(id.email, id.password, { ip, tag: 'login' });
  if (!check(l, { '로그인 200': (r) => r.status === 200 })) return stop('login');
  const token = (safeJson(l) || {}).accessToken;

  // 2) 열차 조회 (비회원 API)
  if (!browse(RUN_ID, FROM, TO)) return stop('browse');

  // 3) 대기열
  const q = queueUntilAdmitted(token, { maxWaitSec: MAX_WAIT_SEC });
  queueWait.add(q.waitedSec);
  if (!check(q, { '대기열 입장': (r) => r.ok })) return stop(`queue_${q.code || q.status}`);

  try {
    // 4) 좌석 배치도 → 5) 빈 좌석 중 무작위 선점 (잡혔으면 배치도를 다시 받아 다른 좌석)
    let reservationId = null;
    for (let attempt = 0; attempt < HOLD_RETRY && reservationId == null; attempt++) {
      const map = emptySeats(token, q.passToken, { from: FROM, to: TO });
      if (!map.seats) return stop(`seat_map_${map.res.status}`);
      if (map.seats.length === 0) {
        soldOut.add(1);
        return stop('sold_out');
      }
      const h = hold(token, q.passToken, { from: FROM, to: TO, seatIds: [pick(map.seats)] });
      if (h.status === 200 || h.status === 201) {
        reservationId = (safeJson(h) || {}).reservationId;
      } else if (codeOf(h) === 'SEAT_ALREADY_TAKEN') {
        seatConflict.add(1);
      } else {
        return stop(`hold_${codeOf(h) || h.status}`);
      }
    }
    if (reservationId == null) return stop('hold_retry_exhausted');

    // 6) 결제 (모의 PG). 새로 처리하면 201, 같은 멱등 키로 다시 보내면 200
    if (PAY) {
      const p = pay(token, reservationId, { cardNumber: CARD, idempotencyKey: uuid() });
      const body = safeJson(p) || {};
      if (!check(p, { '결제 승인': (r) => (r.status === 201 || r.status === 200) && body.paymentStatus === 'APPROVED' })) {
        return stop(`pay_${codeOf(p) || body.paymentStatus || p.status}`);
      }
    }

    journeyOk.add(true);
    journeyMs.add(Date.now() - started);
  } finally {
    queueLeave(token); // 입장 자리를 비워 다음 사람이 들어오게
  }
}
