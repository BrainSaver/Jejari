// 시나리오 10: 회원 인증 (회원가입·로그인) — 실제 Oracle을 쓰는 API
//
// 대상: member 모듈의 /api/v1/auth/** (본인인증 → 가입 → 로그인). 가입은 Oracle에 member·member_credential·
// identity_verification 세 행을 한 트랜잭션으로 쓰고, 로그인은 Oracle을 읽어 Argon2id로 비밀번호를 확인한다.
// 다른 시나리오(대기열)는 Redis만 쓰지만 이 시나리오는 Oracle 연결 풀·시퀀스·UNIQUE 제약·해시 CPU 비용까지 본다.
//
// 네 단계를 차례로 돈다 (시간 순서 = 아래 순서)
//   1) 동시 가입 경합  같은 이메일로 RACE_VUS명이 동시에 가입 → 정확히 1명만 성공(201), 나머지는 409여야 한다 (ROUNDS번 반복)
//   2) 가입 처리량     SIGNUP_RATE건/초로 SIGNUP_SEC초 동안 서로 다른 사람이 가입 (본인인증 + 가입)
//   3) 로그인 처리량   미리 만든 계정 LOGIN_USERS개로 LOGIN_RATE건/초, LOGIN_SEC초 동안 로그인
//   4) 잠금 확인       한 계정에 틀린 비밀번호를 반복 → 기준 횟수에서 423(잠금)이 되고, 맞는 비밀번호도 막히는지
//
// 준비 (실제 앱): docker compose up -d (oracle healthy) + .env에 비밀값 3종 + 앱을 신뢰 프록시 옵션으로 실행
//   ./gradlew bootRun --args="--jejari.auth.trusted-proxies=127.0.0.1,0:0:0:0:0:0:0:1"
//   (요청마다 다른 IP로 보내기 위함. 이 옵션이 없으면 IP당 시도 제한에 걸려 429가 나온다. SPOOF_IP=no로 끌 수 있음)
// 끝나면 Oracle에 가입된 계정이 남는다 (이메일이 @loadtest.example.com). 정리는 loadtest/README.md 참고.
//
// 서버 한도(429)·과부하(503)는 "실패"와 따로 센다: auth_rate_limited는 설정 문제(0이어야 함), auth_overloaded는
// 해시 동시 처리 한도(auth.max_concurrent_hash)를 넘었다는 뜻이라 부하가 센 구간을 알려 준다.
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { identity, fakeIp, verify, signup, login, classify, authSmokeTest, fail, EMAIL_DOMAIN } from '../lib/auth.js';

const RACE_VUS = Math.min(100, Math.max(2, Number(__ENV.RACE_VUS || 20)));
const ROUNDS = Math.min(10, Math.max(1, Number(__ENV.RACE_ROUNDS || 3)));
const SIGNUP_RATE = Number(__ENV.SIGNUP_RATE || 10);
const SIGNUP_SEC = Number(__ENV.SIGNUP_SEC || 20);
const LOGIN_USERS = Math.min(500, Math.max(1, Number(__ENV.LOGIN_USERS || 30)));
const LOGIN_RATE = Number(__ENV.LOGIN_RATE || 20);
const LOGIN_SEC = Number(__ENV.LOGIN_SEC || 20);
const P95_MS = Number(__ENV.P95_MS || 2000);        // Argon2id가 의도적으로 느려서 일반 API보다 넉넉히 둔다
const MAX_VUS = Number(__ENV.MAX_VUS || 300);
const ROUND_GAP_SEC = 4;

// 시퀀스 번호 영역 (identity의 seq는 0~9999, 실행 안에서 겹치면 안 된다)
const SEQ_LOGIN = 0;      // 0..LOGIN_USERS-1 (최대 500)
const SEQ_LOCK = 900;     // 잠금 확인용 계정
const SEQ_SMOKE = 999;    // 사전 점검 (lib/auth.js의 authSmokeTest가 사용)
const SEQ_RACE = 1000;    // 1000 + 라운드×100 + 번호
const SEQ_SIGNUP = 2000;  // 2000 + 반복 번호 (최대 7999건)

const signupOk = new Counter('auth_signup_ok');
const signupFail = new Counter('auth_signup_fail');
const loginOk = new Counter('auth_login_ok');
const loginFail = new Counter('auth_login_fail');
const raceWinners = new Counter('auth_race_winners');       // 라운드마다 정확히 1이어야 한다
const raceDuplicate = new Counter('auth_race_duplicate');   // 409(이미 가입된 이메일)로 막힌 수
const raceOther = new Counter('auth_race_other');           // 201도 409도 아닌 응답 (0이어야 함, 한도·과부하는 제외)
const rateLimited = new Counter('auth_rate_limited');       // 429. 설정 문제(신뢰 프록시 옵션 등), 0이어야 한다
const overloaded = new Counter('auth_overloaded');          // 503. 해시 동시 처리 한도 초과 등 과부하
const lockoutOk = new Counter('auth_lockout_ok');           // 잠금이 걸림 + 맞는 비밀번호도 막힘 (1이어야 함)
const lockoutMissing = new Counter('auth_lockout_missing'); // 기준 횟수를 넘겨도 잠기지 않음 (0이어야 함)
const signupMs = new Trend('auth_signup_ms', true);
const loginMs = new Trend('auth_login_ms', true);

const raceEnd = ROUNDS * ROUND_GAP_SEC;
const signupStart = raceEnd + 2;
const loginStart = signupStart + SIGNUP_SEC + 3;
const lockStart = loginStart + LOGIN_SEC + 3;

const scenarios = {};
const thresholds = {
  auth_signup_fail: ['count==0'],
  auth_login_fail: ['count==0'],
  auth_race_other: ['count==0'],
  auth_rate_limited: ['count==0'],
  auth_lockout_missing: ['count==0'],
  auth_lockout_ok: ['count==1'],
  'http_req_duration{name:auth_signup}': [`p(95)<${P95_MS}`],
  'http_req_duration{name:auth_login}': [`p(95)<${P95_MS}`],
};
for (let r = 0; r < ROUNDS; r++) {
  scenarios[`race_${r}`] = {
    executor: 'per-vu-iterations',
    exec: 'race',
    vus: RACE_VUS,
    iterations: 1,
    startTime: `${r * ROUND_GAP_SEC}s`,
    maxDuration: `${ROUND_GAP_SEC + 20}s`,
    env: { ROUND: String(r) },
  };
  thresholds[`auth_race_winners{round:${r}}`] = ['count==1'];
}
scenarios.signup = {
  executor: 'constant-arrival-rate',
  exec: 'signupFlow',
  rate: SIGNUP_RATE,
  timeUnit: '1s',
  duration: `${SIGNUP_SEC}s`,
  startTime: `${signupStart}s`,
  preAllocatedVUs: Math.min(MAX_VUS, Math.max(20, SIGNUP_RATE * 2)),
  maxVUs: MAX_VUS,
};
scenarios.login = {
  executor: 'constant-arrival-rate',
  exec: 'loginFlow',
  rate: LOGIN_RATE,
  timeUnit: '1s',
  duration: `${LOGIN_SEC}s`,
  startTime: `${loginStart}s`,
  preAllocatedVUs: Math.min(MAX_VUS, Math.max(20, LOGIN_RATE * 2)),
  maxVUs: MAX_VUS,
};
scenarios.lockout = {
  executor: 'per-vu-iterations',
  exec: 'lockout',
  vus: 1,
  iterations: 1,
  startTime: `${lockStart}s`,
  maxDuration: '60s',
};

export const options = { scenarios, thresholds, setupTimeout: '300s' };

function nowTag() {
  return __ENV.RUN_TAG || Date.now().toString(36).slice(-4);
}

// 가입 한 건(본인인증 + 가입). 성공하면 응답 상태를 돌려준다
function createAccount(tag, seq, ip) {
  const id = identity(tag, seq);
  const v = verify(id, { ip, tag: 'setup_verify' });
  if (!v.token) fail('계정 준비·본인인증', v.res);
  const s = signup(v.token, id, { ip, tag: 'setup_signup' });
  if (s.status !== 201) fail('계정 준비·가입', s);
  return id;
}

export function setup() {
  const tag = nowTag();
  authSmokeTest(tag);                                   // 가입 → 로그인 한 번 (실패하면 원인을 알려 주고 중단)

  // 로그인 단계에서 쓸 계정과 잠금 확인용 계정
  const accounts = [];
  for (let i = 0; i < LOGIN_USERS; i++) {
    const id = createAccount(tag, SEQ_LOGIN + i, fakeIp(100000 + i));
    accounts.push({ email: id.email, password: id.password });
  }
  const lockId = createAccount(tag, SEQ_LOCK, fakeIp(200000));

  // 동시 가입 경합용 본인인증 토큰: 라운드마다 사람이 다르고(전화번호 다름) 이메일은 같다
  const race = [];
  for (let r = 0; r < ROUNDS; r++) {
    const row = [];
    for (let i = 0; i < RACE_VUS; i++) {
      const id = identity(tag, SEQ_RACE + r * 100 + i);
      const v = verify(id, { ip: fakeIp(300000 + r * 1000 + i), tag: 'setup_verify' });
      if (!v.token) fail('경합 준비·본인인증', v.res);
      row.push({ token: v.token, password: id.password });
    }
    race.push(row);
  }
  return { tag, accounts, lock: { email: lockId.email, password: lockId.password }, race };
}

export function race(data) {
  const r = Number(__ENV.ROUND);
  const i = exec.scenario.iterationInTest;               // 0..RACE_VUS-1
  const mine = data.race[r][i];
  const email = `lt${data.tag}r${r}@${EMAIL_DOMAIN}`;   // 라운드 안의 모두가 같은 이메일
  const res = signup(mine.token, { email, password: mine.password }, { ip: fakeIp(400000 + r * 1000 + i), tag: 'auth_race' });
  const kind = classify(res);
  if (kind === 'rate_limited') rateLimited.add(1);
  else if (kind === 'overloaded') overloaded.add(1);
  else if (res.status === 201) raceWinners.add(1, { round: String(r) });
  else if (res.status === 409) raceDuplicate.add(1);
  else raceOther.add(1);
}

export function signupFlow(data) {
  const seq = SEQ_SIGNUP + exec.scenario.iterationInTest;
  const id = identity(data.tag, seq);
  const ip = fakeIp(500000 + exec.scenario.iterationInTest);
  const started = Date.now();
  const v = verify(id, { ip });
  let res = v.res;
  if (v.token) res = signup(v.token, id, { ip });
  signupMs.add(Date.now() - started);
  const kind = classify(res);
  if (kind === 'rate_limited') return void rateLimited.add(1);
  if (kind === 'overloaded') return void overloaded.add(1);
  const ok = check(res, { '가입 201': (r) => r.status === 201 });
  (ok ? signupOk : signupFail).add(1);
}

export function loginFlow(data) {
  const n = exec.scenario.iterationInTest;
  const acc = data.accounts[n % data.accounts.length];
  const started = Date.now();
  const res = login(acc.email, acc.password, { ip: fakeIp(600000 + n) });
  loginMs.add(Date.now() - started);
  const kind = classify(res);
  if (kind === 'rate_limited') return void rateLimited.add(1);
  if (kind === 'overloaded') return void overloaded.add(1);
  const ok = check(res, {
    '로그인 200': (r) => r.status === 200,
    '액세스 토큰 있음': (r) => !!(r.json && r.json('accessToken')),
  });
  (ok ? loginOk : loginFail).add(1);
}

export function lockout(data) {
  const ip = fakeIp(700000);
  let lockedAt = 0;
  // 잠금 기준(auth.lock_after_failures)은 정책으로 3~20회. 기본 5회라 여유 있게 25번까지 본다
  for (let k = 1; k <= 25; k++) {
    const res = login(data.lock.email, `Wrong${k}!pass#xyz`, { ip, tag: 'auth_lockout' });
    if (res.status === 423) { lockedAt = k; break; }
    if (res.status !== 401) break;                       // 429·503 등 다른 응답이면 판단하지 않는다
  }
  if (!lockedAt) { lockoutMissing.add(1); return; }
  // 잠긴 계정은 맞는 비밀번호로도 열리지 않아야 한다
  const right = login(data.lock.email, data.lock.password, { ip, tag: 'auth_lockout' });
  if (right.status === 423) lockoutOk.add(1);
  else lockoutMissing.add(1);
}
