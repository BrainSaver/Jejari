// 시나리오 14: 장애 주입 시연용 부하 — "Redis(또는 Kafka)가 죽으면 어떻게 되나"를 부하 아래에서 보여 준다
//
// 혼자 돌리지 않고 loadtest/tools/fault_demo.sh가 띄운다. 이 스크립트는 일정한 부하만 걸고,
// 장애 주입(docker compose kill/start)과 시간대별 분석·정합성 점검은 fault_demo.sh가 한다.
//
// 한 VU = 실제 회원 한 명 (JWT). DURATION 동안 아래를 반복한다 (명절 예매 한 번과 같은 길).
//   열차 조회 → 대기열 진입·입장 → 좌석 배치도 → 화면 녹화 한 묶음 → 좌석 1석 선점
//   → (이 VU가 PAY_MAX건 미만 샀으면) 결제 / (넘으면) 결제 전 취소 → 대기열 나가기 → 쉬기
// 결제를 PAY_MAX건으로 묶는 이유: 같은 회원이 계속 사면 열차당 10매 한도에 걸려 장애와 상관없는 거절이 섞인다.
//
// 요청 하나하나의 시각·상태 코드는 k6 --out csv로 남기고(fault_demo.sh가 붙임), 분석은 fault_report.py가 한다.
// 503(서비스 잠시 불가)은 "실패"로 센다 — 장애 동안 몇 건이 503이었나가 시연의 핵심 숫자다.
//
// 준비: 앱을 신뢰 프록시 옵션으로 (계정을 만들 때 요청마다 다른 IP를 X-Forwarded-For로 보냄)
//   ./gradlew bootRun --args="--jejari.auth.trusted-proxies=127.0.0.1,0:0:0:0:0:0:0:1"
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';
import { RUN_ID, BASE_URL } from '../config.js';
import { identity, fakeIp, verify, signup, login, authSmokeTest, fail } from '../lib/auth.js';
import { browse, queueUntilAdmitted, queueLeave, emptySeats, hold, pay, cancel, recordScreen, pick, codeOf } from '../lib/journey.js';
import { safeJson } from '../lib/helpers.js';

const USERS = Math.min(500, Math.max(1, Number(__ENV.USERS || 30)));
const DURATION_SEC = Math.max(30, Number(__ENV.DURATION_SEC || 240));
const THINK_SEC = Math.max(0, Number(__ENV.THINK_SEC || 2));
const PAY_MAX = Math.max(0, Number(__ENV.PAY_MAX || 3));
const QUEUE_WAIT_SEC = Math.max(5, Number(__ENV.QUEUE_WAIT_SEC || 30));
const FROM = __ENV.FROM || '3900023';
const TO = __ENV.TO || '3900114';
const CARD = __ENV.CARD || ''; // 비우면 VU마다 다른 시험 카드 (lib/journey.js vuCard, 카드 명의 상한을 켠 서버에서도 결제가 막히지 않게)

const SEQ_SMOKE = 999;
const seqOf = (i) => (i >= SEQ_SMOKE ? i + 1 : i);

// 4xx는 서버가 판단해 돌려준 것(경합 409, 한도 422 …)이라 실패가 아니다. 5xx·응답 없음만 실패
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));
const roundOk = new Rate('round_ok');
const roundFail = new Counter('round_failed');
const booked = new Counter('booked');      // 결제까지 끝낸 표
const released = new Counter('released');  // 잡았다가 결제 전 취소

export const options = {
  setupTimeout: '20m',
  scenarios: {
    load: { executor: 'constant-vus', vus: USERS, duration: `${DURATION_SEC}s`, gracefulStop: '15s' },
  },
  thresholds: { round_ok: ['rate>=0'] },
  summaryTrendStats: ['avg', 'p(95)', 'max'],
};

export function setup() {
  const tag = (__ENV.RUN_TAG || Math.random().toString(36).slice(2, 6)).toLowerCase();
  authSmokeTest(tag);
  console.log(`장애 시연 부하: ${USERS}명 × ${DURATION_SEC}초, 운행 ${RUN_ID} → ${BASE_URL}. 계정 ${USERS}개 만드는 중 (태그 ${tag})…`);
  for (let i = 0; i < USERS; i++) {
    const id = identity(tag, seqOf(i));
    const ip = fakeIp(200 + i);
    const v = verify(id, { ip, tag: 'setup_verify' });
    if (!v.token) fail(`계정 ${i + 1}/${USERS} 본인인증`, v.res);
    const s = signup(v.token, id, { ip, tag: 'setup_signup' });
    if (s.status !== 201) fail(`계정 ${i + 1}/${USERS} 가입`, s);
  }
  // 로그인 토큰을 미리 받아 둔다: 장애 중에는 로그인도 막히므로(시도 제한이 Redis), 장애 전 토큰으로 계속 돈다
  const tokens = [];
  for (let i = 0; i < USERS; i++) {
    const id = identity(tag, seqOf(i));
    const l = login(id.email, id.password, { ip: fakeIp(60000 + i), tag: 'setup_login' });
    if (l.status !== 200) fail(`계정 ${i + 1}/${USERS} 로그인`, l);
    tokens.push((safeJson(l) || {}).accessToken);
  }
  const q = queueUntilAdmitted(tokens[0], { maxWaitSec: 30 });
  if (!q.ok) throw new Error(`사전 점검 실패(대기열): ${q.status} ${q.code}. 운행 ${RUN_ID}이 판매 중인지 확인하세요 (-e RUN_ID=…)`);
  queueLeave(tokens[0]);
  // fault_demo.sh가 이 줄을 기다렸다가 시계를 잰다 (부하 시작 시각)
  console.log(`FAULT_DEMO_LOAD_START ${Date.now()}`);
  return { tokens };
}

let paid = 0;

export default function (data) {
  const t = data.tokens[(exec.vu.idInTest - 1) % data.tokens.length];
  const done = (ok, stage) => {
    roundOk.add(ok);
    if (!ok) roundFail.add(1, { stage });
    sleep(THINK_SEC * (0.5 + Math.random()));
  };

  if (!browse(RUN_ID, FROM, TO)) return done(false, 'browse');
  const q = queueUntilAdmitted(t, { maxWaitSec: QUEUE_WAIT_SEC });
  if (!q.ok) {
    queueLeave(t);
    return done(false, `queue_${q.status}`);
  }
  try {
    const map = emptySeats(t, q.passToken, { from: FROM, to: TO });
    if (!map.seats) return done(false, `seat_map_${map.res.status}`);
    if (map.seats.length === 0) return done(true);
    recordScreen(t);
    const h = hold(t, q.passToken, { from: FROM, to: TO, seatIds: [pick(map.seats)] });
    const rid = (safeJson(h) || {}).reservationId;
    if (!rid) {
      // 409(같은 좌석을 남이 먼저)는 정상 경합. 그 밖은 실패
      return done(h.status === 409, `hold_${h.status}_${codeOf(h)}`);
    }
    if (paid < PAY_MAX) {
      const p = pay(t, rid, CARD ? { cardNumber: CARD } : {});
      if (!check(p, { '결제 승인': (r) => r.status === 200 || r.status === 201 })) {
        cancel(t, rid); // 결제를 못 했으면 좌석을 돌려 둔다 (안 되면 결제 기한에 스캐너가 돌려줌)
        return done(false, `pay_${p.status}`);
      }
      paid++;
      booked.add(1);
    } else {
      const c = cancel(t, rid);
      if (c.status !== 200) return done(false, `cancel_${c.status}`);
      released.add(1);
    }
    done(true);
  } finally {
    queueLeave(t);
  }
}

export function handleSummary(data) {
  const v = (k, s) => (data.metrics[k] && data.metrics[k].values ? data.metrics[k].values[s] : 0) || 0;
  const text = `\n장애 시연 부하 끝: 바퀴 성공 ${(v('round_ok', 'rate') * 100).toFixed(1)}% · 결제한 표 ${v('booked', 'count')} · `
    + `잡고 취소 ${v('released', 'count')} · 요청 ${v('http_reqs', 'count')}건 (시간대별 분석은 fault_demo.sh 출력)\n`;
  return { stdout: text };
}
