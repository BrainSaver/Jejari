#!/usr/bin/env node
// 연습 봇 30종 + 합성 사람 — 규칙·모델 보강용 "연습 상대" (2026-11-23 강동균)
//
// 대회 봇(botb·botc)은 2차(11-26) 채점용으로 손대지 않고 남겨 둔다. 보강은 이 연습 봇으로 하고,
// 2차에서 대회 봇으로 처음 시험한다 (시험 문제를 보고 푸는 것을 막으려고).
// 수법은 botb·botc 코드를 보지 않고, 실제 예매 매크로가 흔히 하는 짓에서 골랐다.
//
//   node loadtest/contest/practice/run.mjs                 # 전부 (가입 → 구매)
//   PROFILES=botfast,botsteady HUMANS=0 node …            # 일부만
//   GEN=2 node …                                         # 2단계 9종 · GEN=3 녹화 꾸미기 9종 · GEN=h 사람 변형 10종(오탐) · GEN=all 전부
//   MODE=signup | buy | all (기본 all)
//
// 앱은 신뢰 프록시 모드로: --jejari.auth.trusted-proxies=127.0.0.1,0:0:0:0:0:0:0:1 (프로필마다 다른 IP를 X-Forwarded-For로 보냄.
//   안 그러면 30종이 한 IP에서 가입해 IP당 가입 제한에 전부 막힘 — 그 자체가 방어가 동작한 것)
// 환경 변수: BASE_URL(기본 http://localhost:8080) · RUN_ID(필수) · TAG(필수, 영문 소문자·숫자 7자)
//            ACCOUNTS(프로필당 명의, 기본 3, 최대 6) · HUMANS(합성 사람 수, 기본 12) · FROM · TO (기본 서울 → 부산)
// 평가: loadtest/tools/eval_contest.sh <TAG> <RUN_ID>  (종류 이름이 그대로 봇 이름. human = 합성 사람)
//
// 주의: "합성 사람"은 사람처럼 쉬고 둘러보게 만든 스크립트다. 진짜 사람의 오탐은 대회의 사람 참가자로만 잴 수 있다.

import crypto from 'node:crypto';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const RUN_ID = Number(process.env.RUN_ID);
const TAG = process.env.TAG || '';
const MODE = process.env.MODE || 'all';
const ACCOUNTS = Math.min(6, Math.max(1, Number(process.env.ACCOUNTS || 3)));
const HUMANS = Math.max(0, Number(process.env.HUMANS ?? 12));
const FROM = process.env.FROM || '3900023';
const TO = process.env.TO || '3900114';
const DOMAIN = 'loadtest.example.com';
if (!RUN_ID || !/^[a-z0-9]{1,7}$/.test(TAG)) {
  console.error('RUN_ID와 TAG(영문 소문자·숫자 7자 이내)가 필요합니다');
  process.exit(2);
}

// ── 시간 ───────────────────────────────────────────────────
const sleep = (ms) => new Promise((r) => setTimeout(r, Math.max(0, ms)));
const rand = (a, b) => a + Math.random() * (b - a);
const logn = (medMs, sigma) => medMs * Math.exp(sigma * gauss()); // 사람 반응 시간은 로그정규에 가깝다
function gauss() { let u = 0, v = 0; while (!u) u = Math.random(); while (!v) v = Math.random(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v); }
const T = {
  none: () => 0,
  jitter: () => rand(50, 150),
  steady1: () => 1000,
  steady3: () => 3000,
  human: () => Math.min(20000, logn(2500, 0.6)),
  quick: () => rand(150, 400),
};

// ── 프로필 30종 ─────────────────────────────────────────────
// family: 어떤 성질로 사람과 다른가 (보강 규칙을 "봇 하나"가 아니라 "성질"에 맞추려고)
const P = (kind, family, desc, o = {}) => ({
  kind, family, desc,
  think: T.quick, searches: 1, seatMap: true, mapPolls: 0, party: 2, pick: 'random', retrySame: 0, pay: true,
  cancelAfterPayMs: null, holdNoPay: false, holdCancelLoops: 0, start: 'sync', device: 'own', card: 'own',
  sameCi: false, queueSpam: 0, ua: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0 Safari/537.36',
  runHop: 0, limitProbe: false, burst: false, gen: 1, step: null, ipEach: false, staggerMs: [20000, 40000], rec: null, count: null, ip: null, doubleClick: false, ...o,
});
const PROFILES = [
  // 속도
  P('botfast', '속도', '쉬지 않고 연달아', { think: T.none, party: 4, pick: 'front' }),
  P('botjitter', '속도', '50~150ms 무작위로 쉼 (사람처럼 보이려는 흔들기)', { think: T.jitter }),
  P('botsteady', '일정 간격', '정확히 1초 간격', { think: T.steady1 }),
  P('botslow', '일정 간격', '정확히 3초 간격 (느리지만 기계적)', { think: T.steady3 }),
  P('botburst', '속도', '오래 쉬었다 한꺼번에 몰아침', { think: T.none, burst: true }),
  // 화면 흐름 건너뛰기
  P('botnosearch', '흐름 건너뜀', '조회 없이 대기열로 바로', { searches: 0 }),
  P('botnomap', '흐름 건너뜀', '좌석표를 안 보고 좌석 번호를 찍어서 선점', { seatMap: false }),
  P('botpollmap', '흐름 건너뜀', '좌석표를 0.2초마다 20번 새로 받고 선점', { mapPolls: 20, think: T.none }),
  P('botdirect', '흐름 건너뜀', '조회·좌석표 없이 입장하자마자 선점', { searches: 0, seatMap: false, think: T.none }),
  // 좌석 다루기
  P('bothammer', '같은 좌석 반복', '뺏긴 좌석을 15번 다시 시도', { retrySame: 15, pick: 'front', think: T.none }),
  P('botspray', '좌석 흩뿌리기', '1석씩 여러 번 선점', { party: 1, think: T.jitter }),
  P('botsniper', '좌석 집착', '맨 앞 좌석만 노림', { pick: 'front', retrySame: 5 }),
  P('botbigparty', '대량', '항상 4석 붙여서', { party: 4 }),
  P('bothoarder', '선점만', '선점하고 결제 안 함 (좌석 묶어 두기)', { holdNoPay: true }),
  P('botholdloop', '선점만', '선점 → 결제 전 취소 → 다시 선점을 3번', { holdCancelLoops: 3 }),
  // 여러 계정
  P('botsync', '다계정', '명의들이 같은 순간에', { start: 'sync', think: T.none }),
  P('botstagger', '다계정', '명의들이 20~40초씩 간격을 두고', { start: 'stagger' }),
  P('botshareddev', '다계정', '명의들이 기기 하나를 같이 씀', { device: 'shared' }),
  P('botrotdev', '기기 위장', '요청마다 기기 ID를 바꿈', { device: 'rotate' }),
  P('botnodev', '기기 위장', '기기 ID를 안 보냄', { device: 'none' }),
  P('botsharedcard', '다계정', '명의들이 카드 하나를 같이 씀', { card: 'shared' }),
  P('botsameci', '다계정', '한 명의(CI)로 계정 여러 개', { sameCi: true }),
  // 되팔기·한도
  P('botresell', '되팔기', '결제하고 1~2분 뒤 취소', { cancelAfterPayMs: [60000, 120000] }),
  P('botlimit', '한도 탐색', '4석부터 매수 제한에 걸릴 때까지 줄여 가며', { party: 4, limitProbe: true }),
  // 대기열·운행
  P('botqueuespam', '대기열 남용', '대기열을 5번 들어갔다 나갔다', { queueSpam: 5 }),
  P('botrunhop', '대기열 남용', '다른 운행 대기열에 들렀다가', { runHop: 2 }),
  // 위장
  P('botua', '기기 위장', '스크립트 티가 나는 User-Agent', { ua: 'python-requests/2.31' }),
  P('botmimic', '사람 흉내', '사람처럼 쉬고 둘러봄 (날짜 여러 개 조회, 1~5초 생각)', { think: T.human, searches: 3 }),
  P('botmimicfast', '사람 흉내', '둘러보기는 사람처럼, 좌석 선점 순간만 빠르게', { think: T.human, searches: 3, burst: true }),
  P('botlate', '사람 흉내', '판매 열리고 1분 뒤 천천히', { think: T.human, start: 'late' }),
];
// ── 2단계: 1단계 평가에서 나온 규칙 제안(연습봇-평가와-규칙-제안.md 3절)을 피하도록 바꾼 봇 ──
// 규칙을 보강하면 실제 매크로도 이렇게 바뀐다. 제안 규칙이 어디까지 버티는지 미리 본다. GEN=2로 돌림
//   step: 좌석표 → 선점, 선점 → 결제 사이를 이만큼 쉼 (INHUMAN_STEP 0.5초 피하기)
//   ipEach: 명의마다 다른 IP (주거용 프록시, SHARED_IP 피하기)
const P2 = (kind, desc, o) => P(kind, '2단계', desc, { gen: 2, think: T.quick, ...o });
const GEN2 = [
  P2('botstep', '단계 사이만 0.6~0.9초 (나머지는 빠르게)', { step: [600, 900] }),
  P2('botstepjit', '단계 사이 0.6~2초 무작위', { step: [600, 2000] }),
  P2('botsearchfast', '조회 한 번 하고(조회 없이 진입 피하기) 단계 사이 0.6초', { step: [600, 800], think: T.none }),
  P2('botresi', '명의마다 다른 IP + 단계 사이 0.7초', { step: [700, 1000], ipEach: true }),
  P2('botpace', '전체를 사람보다 조금 빠른 속도로 (중앙 1.2초)', { think: () => Math.min(8000, logn(1200, 0.4)), step: [700, 1500] }),
  P2('botsmallbuy', '1석씩만, 단계 사이 0.7초 (대량 구매 피하기)', { party: 1, step: [700, 1100] }),
  P2('botretrydiff', '뺏기면 같은 좌석 대신 다른 좌석, 단계 사이 0.7초', { pick: 'front', step: [700, 1100] }),
  P2('botghostsync', '명의마다 IP·기기·카드 다름 + 조회 2번 + 단계 사이 0.7~1.5초, 동시에 출발', { searches: 2, step: [700, 1500], ipEach: true }),
  P2('botghost', '위와 같고 명의끼리 65~120초 간격 (다계정 동시 선점 60초 피하기)', { searches: 2, step: [700, 1500], ipEach: true, start: 'stagger', staggerMs: [65000, 120000] }),
];

// 3단계: 화면 녹화 규칙(HEADLESS_HOLD·LINEAR_MOUSE)과 INHUMAN_STEP이 케이스 규칙이 된 뒤의 매크로. GEN=3으로 돌림
// 모두 2단계의 피하기(단계 사이 0.6~1초, 조회 2번, 명의마다 IP)를 갖추고, 녹화를 꾸미는 방법만 다르다
const P3 = (kind, desc, o) => P(kind, '3단계', desc, { gen: 3, searches: 2, step: [600, 1000], ipEach: true, ...o });
const GEN3 = [
  P3('botrecone', '녹화 이벤트 하나만 보냄 (화면 없이 선점 피하기)', { rec: 'one' }),
  P3('botrecclick', '클릭만 꾸며 보내고 마우스 궤적은 없음', { rec: 'clickonly' }),
  P3('botrectouch', '직선 궤적인데 클릭을 터치라고 신고 (마우스 검사 제외 노림)', { rec: 'touch' }),
  P3('botreclinear', '직선 등속 궤적 + 마우스 클릭 (자동화 도구 기본, 기준선)', { rec: 'linear' }),
  P3('botrecbezier', '휜 곡선 궤적, 등간격·손떨림 없음·도착 즉시 클릭', { rec: 'bezier' }),
  P3('botrectemplate', '사람 같은 궤적 하나를 모든 계정이 똑같이 재사용', { rec: 'template' }),
];

// 사람 변형 (오탐 시험): 진짜 사람이 하는데 규칙에 걸리기 쉬운 행동. GEN=h로 돌림. 탐지되면 오탐
//   진짜 사람이 아닌 스크립트라 "이 행동이면 오탐이 나는지"만 본다. 오탐률 자체는 2차 대회 사람으로
const MOBILE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 Version/18.1 Mobile/15E148 Safari/604.1';
const PH = (kind, desc, o) => P(kind, '사람 변형', desc, { gen: 'h', think: T.human, searches: 2, rec: 'human', ...o });
const GENH = [
  PH('humanfast', '손 빠른 사람 (생각 0.7초 안팎, 단계 사이 0.55~0.9초 — INHUMAN_STEP 문턱 바로 위)', { think: () => Math.min(4000, logn(700, 0.35)), step: [550, 900], searches: 1 }),
  PH('humandouble', '선점 버튼을 두 번 누르는 사람 (0.06~0.14초 간격, 60%)', { doubleClick: true }),
  PH('humanretry', '오픈 직후 인기 좌석 경쟁: 뺏기면 같은 좌석 1번 더, 그다음 다른 좌석 (맨 앞 좌석 선호)', { pick: 'front', retrySame: 1, think: () => Math.min(6000, logn(1200, 0.4)) }),
  PH('humanmobile', '휴대폰 사용자 (터치만, 마우스 궤적 없음, 모바일 브라우저)', { rec: 'mobile', ua: MOBILE_UA }),
  PH('humankeyboard', '키보드로 쓰는 사람 (Tab·Enter, 클릭 좌표 0, 궤적 없음)', { rec: 'keyboard' }),
  PH('humanlink', '조회 없이 링크로 바로 줄 서기 (취소표 "예매하기"·공유 링크)', { searches: 0 }),
  PH('humanfamily', '가족 3명 표를 한 컴퓨터에서 차례로 (같은 기기·같은 IP, 명의는 각자, 20~40초 간격)', { device: 'shared', ip: 'shared', start: 'stagger', count: 3 }),
  PH('humannat', '회사·학교 같은 IP 뒤 6명이 오픈 순간 동시에 (기기는 각자)', { ip: 'shared', count: 6 }),
  PH('humancancel', '결제 뒤 1~2분 만에 날짜를 잘못 고른 걸 알고 취소', { cancelAfterPayMs: [60000, 120000] }),
  PH('humanslow', '천천히 고르는 사람 (날짜 4번 조회, 생각 3~10초)', { searches: 4, think: () => Math.min(20000, logn(5000, 0.5)) }),
];

// 3단계 추가: 남은 빈틈 (마우스 궤적 보강 제안 5절)
GEN3.push(
  P3('botrecjump', '클릭 자리에 점 하나만 찍고 클릭 (순간이동인데 클릭 수가 적어 문턱 아래)', { rec: 'jump' }),
  P3('botrecmobile', '휴대폰인 척: 모바일 브라우저 + 터치 클릭만 (진짜 휴대폰 사용자와 녹화가 같음)', { rec: 'mobile', ua: MOBILE_UA }),
  P3('botrecreal', '사람과 같은 녹화(곡선·가감속·멈췄다 클릭) + 2단계 피하기 전부 = 가장 잘 숨는 봇 (어디까지 막히나 상한)', { rec: 'human' }),
);

const HUMAN = P('human', '합성 사람', '사람처럼 둘러보고 쉬고, 가끔 실패하면 다른 좌석. 화면 녹화도 보냄', { think: T.human, searches: 2, rec: 'human' });

// ── 화면 녹화 ──────────────────────────────────────────────
// 실제 화면은 녹화기(frontend/src/replay/recorder.js)가 클릭·마우스 궤적을 보낸다. 합성 사람도 보내야
// 서버의 "녹화 장애 보호"(다른 세션 녹화가 하나도 없으면 HEADLESS_HOLD를 세지 않음)가 풀려 대회와 같은 조건이 된다.
// 1·2단계 봇은 API만 부르므로 녹화가 없다. 3단계 봇은 녹화를 꾸며 보낸다 (rec: 아래 모드)
//   human     휜 곡선 + 빨라졌다 느려지는 속도 + 손떨림 + 도착 뒤 잠깐 멈췄다 클릭 (합성 사람)
//   one       로그인 직후 PAGE_VIEW 하나만 (녹화 0건 = HEADLESS_HOLD 피하기)
//   clickonly 클릭만 보내고 마우스 궤적은 없음
//   touch     직선 등속 궤적 + 클릭을 터치(t)라고 신고
//   linear    직선 등속 궤적 + 마우스 클릭 (자동화 도구 기본 이동. LINEAR_MOUSE가 잡아야 하는 기준선)
//   bezier    휜 곡선이지만 등간격(가감속 없음)·손떨림 없음·도착 즉시 클릭
//   template  사람처럼 보이는 궤적 하나를 모든 계정이 똑같이 재사용
//   jump      클릭 자리에 점 하나만 찍고 바로 클릭 (자동화 도구 "요소로 이동 후 클릭")
//   mobile    휴대폰: 터치(t) 클릭만, 마우스 궤적 없음 (진짜 휴대폰 사용자도 이렇다)
//   keyboard  키보드(Tab·Enter): 클릭 좌표 0, 궤적 없음 (접근성·키보드 사용자)
function seeded(seed) { let x = seed >>> 0 || 1; return () => ((x = (x * 1664525 + 1013904223) >>> 0) / 4294967296); }
async function recStart(ctx) {
  const c = (await call(ctx, 'GET', '/api/v1/replay/config')).data;
  ctx.rec = c?.enabled ? { c, seq: 0, n: 0, pos: [rand(300, 700), rand(300, 700)], buf: [] } : null;
  if (ctx.rec && ctx.p.rec === 'one') { recPush(ctx, { type: 'PAGE_VIEW', screen: 'home' }); await recFlush(ctx); }
}
function recPush(ctx, e) { ctx.rec.buf.push({ seq: ++ctx.rec.seq, ts: Date.now(), runId: RUN_ID, ...e }); }
async function recFlush(ctx) { const events = ctx.rec.buf.splice(0); if (events.length) await call(ctx, 'POST', '/api/v1/replay/events', { events }); }
function makePath(r, mode, x0, y0, x1, y1) {
  const R = mode === 'template' ? seeded(7919 * (r.n + 1)) : Math.random; // template: 클릭 순번마다 같은 궤적
  const rr = (a, b) => a + R() * (b - a);
  const step = r.c.pathSampleMs || 150, maxPts = (r.c.maxPathPoints || 16) - 1;
  const L = Math.hypot(x1 - x0, y1 - y0) || 1;
  const curved = mode !== 'linear' && mode !== 'touch';
  const bend = curved ? rr(0.2, 0.45) * (R() < 0.5 ? -1 : 1) : 0;
  const cx = (x0 + x1) / 2 - ((y1 - y0) / L) * L * bend, cy = (y0 + y1) / 2 + ((x1 - x0) / L) * L * bend;
  const ease = mode === 'human' || mode === 'template';
  const noise = ease ? 3 : 0;
  const pts = curved ? Math.min(maxPts, Math.max(5, Math.round((350 + 180 * Math.log2(L / 30 + 1) + rr(0, 250)) / step))) : maxPts;
  const path = [];
  for (let k = 1; k <= pts; k++) {
    const u = k / pts, s = ease ? u * u * u * (10 - 15 * u + 6 * u * u) : u;
    const bx = (1 - s) ** 2 * x0 + 2 * (1 - s) * s * cx + s * s * x1, by = (1 - s) ** 2 * y0 + 2 * (1 - s) * s * cy + s * s * y1;
    const dt = ease ? Math.round(step * rr(0.85, 1.15)) : step;
    path.push([dt, Math.round(bx + rr(-noise, noise)), Math.round(by + rr(-noise, noise))]);
  }
  if (ease) path[path.length - 1] = [step, x1 + Math.round(rr(-2, 2)), y1 + Math.round(rr(-2, 2))];
  return path;
}
async function recClick(ctx, screen, target) {
  const r = ctx.rec, mode = ctx.p.rec;
  if (!r || mode === 'one') return;
  recPush(ctx, { type: 'PAGE_VIEW', screen });
  const R = mode === 'template' ? seeded(104729 * (r.n + 1)) : Math.random;
  const [x0, y0] = r.pos;
  const x1 = Math.round(150 + R() * 700), y1 = Math.round(150 + R() * 700);
  const noPath = mode === 'clickonly' || mode === 'mobile' || mode === 'keyboard';
  if (mode === 'jump') recPush(ctx, { type: 'MOUSE_PATH', screen, path: [[r.c.pathSampleMs || 150, x1, y1]] });
  else if (!noPath) recPush(ctx, { type: 'MOUSE_PATH', screen, path: makePath(r, mode, x0, y0, x1, y1) });
  if (mode === 'human' || mode === 'template' || mode === 'mobile') await sleep(rand(120, 300)); // 도착(손가락 이동) 뒤 잠깐 멈췄다 누름
  const pointer = mode === 'touch' || mode === 'mobile' ? 't' : mode === 'keyboard' ? 'k' : 'm';
  recPush(ctx, { type: 'CLICK', screen, target, x: mode === 'keyboard' ? 0 : x1, y: mode === 'keyboard' ? 0 : y1, pointer });
  r.pos = [x1, y1];
  r.n++;
  await recFlush(ctx);
}

// ── HTTP ───────────────────────────────────────────────────
async function call(ctx, method, path, body, extra = {}) {
  const headers = { 'Content-Type': 'application/json', 'User-Agent': ctx.ua, ...extra };
  if (ctx.token) headers.Authorization = `Bearer ${ctx.token}`;
  const dev = ctx.deviceFor();
  if (dev) headers['X-Device-Id'] = dev;
  if (ctx.ip) headers['X-Forwarded-For'] = ctx.ip;
  const res = await fetch(BASE + path, { method, headers, body: body == null ? undefined : JSON.stringify(body) });
  let data = null;
  try { data = await res.json(); } catch { /* 본문 없음 */ }
  return { status: res.status, data };
}

function identity(kind, n, sameCi) {
  const seed = crypto.createHash('sha256').update(`${TAG}.${kind}.${sameCi ? 1 : n}`).digest();
  const digits = [...seed].map((b) => b % 10).join('').slice(0, 7);
  const phone = `010${digits}1`; // 끝자리 1 (모의 본인인증의 실패 번호 …0000 피하기)
  const name = '연습' + [...digits.slice(0, 3)].map((d) => '가나다라마바사아자차'[Number(d)]).join('');
  const group = kind.startsWith('human') ? n : 1;
  return { name, phone, email: `lt${TAG}.${kind}.g${group}.${n}@${DOMAIN}`, password: `Pr4c!${TAG}#${kind.length}x${n}Qz` };
}

function fakeIp(key) {
  const b = crypto.createHash('sha256').update(`${TAG}.${key}`).digest();
  return `10.${b[0]}.${b[1]}.${1 + (b[2] % 250)}`;
}

function cardFor(key) {
  const b = crypto.createHash('sha256').update(`${TAG}.card.${key}`).digest();
  return '4' + [...b].slice(0, 14).map((x) => x % 10).join('') + '1';
}

function makeCtx(p, n) {
  const ownDev = crypto.randomBytes(12).toString('hex');
  const shared = crypto.createHash('sha256').update(`${TAG}.${p.kind}.dev`).digest('hex').slice(0, 24);
  return {
    p, n, ua: p.ua, token: null, id: identity(p.kind, n, p.sameCi), seats: 0, errors: [],
    // IP: 봇은 프로필마다 하나(프록시 하나를 쓰는 운영자), 합성 사람은 사람마다 하나. 앱이 신뢰 프록시 모드일 때만 의미 있음
    ip: fakeIp(p.ip === 'shared' ? `${p.kind}.shared` : p.kind.startsWith('human') || p.ipEach ? `${p.kind}.${n}` : p.kind),
    // 카드: 계정마다 다르게 (프로필·번호로 만든 15자리 + 끝자리 1 = 모의 PG 승인). botsharedcard만 프로필 안에서 하나를 같이 씀
    card: cardFor(p.card === 'shared' ? `${p.kind}.shared` : `${p.kind}.${n}`),
    deviceFor() {
      if (p.device === 'none') return null;
      if (p.device === 'shared') return shared;
      if (p.device === 'rotate') return crypto.randomBytes(12).toString('hex');
      return ownDev;
    },
  };
}

// ── 단계 ───────────────────────────────────────────────────
async function signupOne(ctx) {
  const v = await call(ctx, 'POST', '/api/v1/auth/verify', { name: ctx.id.name, phone: ctx.id.phone, provider: 'MOCK_PASS' });
  if (v.status !== 200) throw new Error(`본인인증 ${v.status} ${v.data?.code || ''}`);
  const s = await call(ctx, 'POST', '/api/v1/auth/signup', { verificationToken: v.data.verificationToken, email: ctx.id.email, password: ctx.id.password });
  if (s.status !== 201 && s.status !== 200 && s.data?.code !== 'AUTH_EMAIL_DUPLICATED') throw new Error(`가입 ${s.status} ${s.data?.code || ''}`);
}

async function login(ctx) {
  for (let i = 0; i < 20; i++) {
    const r = await call(ctx, 'POST', '/api/v1/auth/login', { email: ctx.id.email, password: ctx.id.password });
    if (r.status === 200) { ctx.token = r.data.accessToken; return; }
    if (r.status === 429) { await sleep(1000 * Number(r.data?.retryAfterSec || 2)); continue; }
    throw new Error(`로그인 ${r.status} ${r.data?.code || ''}`);
  }
  throw new Error('로그인 대기 초과');
}

async function searchStep(ctx, runDate) {
  const p = ctx.p;
  for (let i = 0; i < p.searches; i++) {
    const d = i === p.searches - 1 ? runDate : shiftDate(runDate, i - p.searches + 1);
    if (ctx.rec) await recClick(ctx, 'search', 'search-submit');
    await call(ctx, 'GET', `/api/v1/runs?from=${FROM}&to=${TO}&date=${d}`);
    await sleep(p.think());
  }
}

async function queueAdmit(ctx, runId) {
  const r = await call(ctx, 'POST', `/api/v1/runs/${runId}/queue`);
  if (r.status !== 200) throw new Error(`대기열 ${r.status} ${r.data?.code || ''}`);
  let s = r.data;
  const until = Date.now() + 180000;
  while (s?.state === 'WAITING' && Date.now() < until) {
    await sleep(1000 * Math.max(0.3, Number(s.pollIntervalSec) || 1));
    s = (await call(ctx, 'GET', `/api/v1/runs/${runId}/queue/me`)).data;
  }
  if (s?.state !== 'ADMITTED') throw new Error('대기열 입장 실패');
  return s.passToken;
}

async function seatMap(ctx, pass) {
  const r = await call(ctx, 'GET', `/api/v1/runs/${RUN_ID}/seats?from=${FROM}&to=${TO}`, null, { 'X-Queue-Token': pass });
  if (r.status !== 200) return [];
  return (r.data?.cars || []).flatMap((c) => (c.seats || []).filter((s) => s.state === 'EMPTY').map((s) => s.seatId));
}

function choose(ctx, empty, k) {
  if (!empty.length) return [];
  if (ctx.p.pick === 'front') return empty.slice(0, k);
  const start = Math.floor(Math.random() * Math.max(1, empty.length - k));
  return ctx.p.party > 1 && k > 1 ? empty.slice(start, start + k) : [empty[Math.floor(Math.random() * empty.length)]];
}

async function hold(ctx, pass, seatIds) {
  return call(ctx, 'POST', '/api/v1/reservations',
    { runId: RUN_ID, fromStationId: FROM, toStationId: TO, seats: seatIds.map((seatId) => ({ seatId, passengerType: 'ADULT' })), channel: 'WEB' },
    { 'X-Queue-Token': pass, 'Idempotency-Key': crypto.randomUUID() });
}
const pay = (ctx, id) => call(ctx, 'POST', `/api/v1/reservations/${id}/payments`, { cardNumber: ctx.card }, { 'Idempotency-Key': crypto.randomUUID() });
const cancel = (ctx, id) => call(ctx, 'POST', `/api/v1/reservations/${id}/cancel`);

async function buyOne(ctx, runDate) {
  const p = ctx.p;
  await login(ctx);
  if (p.rec) await recStart(ctx);
  if (p.burst) await sleep(rand(5000, 15000));
  await searchStep(ctx, runDate);
  for (let i = 0; i < p.runHop; i++) {
    try { await queueAdmit(ctx, RUN_ID + 2 * (i + 1)); } catch { /* 다른 운행은 실패해도 됨 */ }
    await call(ctx, 'DELETE', `/api/v1/runs/${RUN_ID + 2 * (i + 1)}/queue/me`);
  }
  for (let i = 0; i < p.queueSpam; i++) {
    await call(ctx, 'POST', `/api/v1/runs/${RUN_ID}/queue`);
    await sleep(p.think());
    await call(ctx, 'DELETE', `/api/v1/runs/${RUN_ID}/queue/me`);
  }
  const pass = await queueAdmit(ctx, RUN_ID);
  const burstThink = p.burst ? T.none : p.think;
  let empty = [];
  if (p.seatMap) {
    empty = await seatMap(ctx, pass);
    for (let i = 0; i < p.mapPolls; i++) { await sleep(200); empty = await seatMap(ctx, pass); }
  } else {
    // 좌석표를 안 보고 번호를 찍는다: 한 번만 몰래 받아 두고 그 뒤로는 안 봄 (번호 규칙을 아는 매크로 흉내)
    empty = await seatMap(ctx, pass);
  }
  await sleep(burstThink());

  let k = p.party;
  let loops = Math.max(1, p.holdCancelLoops + 1);
  for (let attempt = 0; attempt < 30 && loops > 0; attempt++) {
    const seats = choose(ctx, empty, k);
    if (!seats.length) break;
    if (p.step) await sleep(rand(...p.step)); // 좌석표 → 선점
    if (ctx.rec) await recClick(ctx, 'runs/:id/seats', 'hold-submit');
    if (p.doubleClick && Math.random() < 0.6) setTimeout(() => hold(ctx, pass, seats).catch(() => {}), rand(60, 140)); // 선점 버튼 두 번 누름
    const h = await hold(ctx, pass, seats);
    if (h.status === 201 || h.status === 200) {
      const rid = h.data?.reservationId;
      if (p.holdNoPay) { ctx.seats += 0; loops = 0; break; }
      if (p.holdCancelLoops && loops > 1) { await sleep(burstThink()); await cancel(ctx, rid); loops--; empty = await seatMap(ctx, pass); continue; }
      await sleep(burstThink());
      if (p.step) await sleep(rand(...p.step)); // 선점 → 결제
      if (ctx.rec) await recClick(ctx, 'reservations/:id/pay', 'pay-submit');
      const r = await pay(ctx, rid);
      if (r.status === 200 || r.status === 201) {
        ctx.seats += seats.length;
        if (p.cancelAfterPayMs) { await sleep(rand(...p.cancelAfterPayMs)); await cancel(ctx, rid); }
      } else ctx.errors.push(`결제 ${r.status} ${r.data?.code || ''}`);
      loops--;
      if (ctx.p.party === 1 && attempt < 3) { loops = 1; empty = await seatMap(ctx, pass); } // 흩뿌리기: 1석씩 몇 번 더
      continue;
    }
    const code = h.data?.code || '';
    if (code.startsWith('LIMIT') || code.includes('LIMIT')) {
      if (p.limitProbe && k > 1) { k--; continue; }
      ctx.errors.push(code); break;
    }
    if (code === 'SEAT_ALREADY_TAKEN' || h.status === 409) {
      if (p.retrySame > 0) { for (let r = 0; r < p.retrySame; r++) { await sleep(burstThink()); const again = await hold(ctx, pass, seats); if (again.status === 201 || again.status === 200) break; } }
      empty = p.seatMap ? await seatMap(ctx, pass) : empty.filter((s) => !seats.includes(s));
      await sleep(burstThink());
      continue;
    }
    ctx.errors.push(`선점 ${h.status} ${code}`);
    break;
  }
}

function shiftDate(iso, days) {
  const d = new Date(`${iso}T00:00:00+09:00`);
  d.setDate(d.getDate() + days);
  return d.toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' });
}

// ── 실행 ───────────────────────────────────────────────────
async function runProfile(p, count, runDate) {
  const ctxs = Array.from({ length: count }, (_, i) => makeCtx(p, i + 1));
  const go = async (ctx, i) => {
    try {
      if (MODE !== 'buy') await signupOne(ctx);
      if (MODE === 'signup') return;
      if (p.start === 'stagger') await sleep(i * rand(...p.staggerMs));
      if (p.start === 'late') await sleep(60000 + rand(0, 20000));
      await buyOne(ctx, runDate);
    } catch (e) { ctx.errors.push(e.message); }
  };
  await Promise.all(ctxs.map(go));
  return ctxs;
}

const want = (process.env.PROFILES || '').split(',').map((s) => s.trim()).filter(Boolean);
// GEN: 1(기본, 1단계 30종) · 2(2단계) · 3(녹화 꾸미기) · h(사람 변형, 오탐 시험) · all. PROFILES로 이름을 주면 단계와 상관없이 그 봇만
const GEN = process.env.GEN || '1';
const pool = PROFILES.concat(GEN2, GEN3, GENH);
const chosen = want.length ? pool.filter((p) => want.includes(p.kind))
  : pool.filter((p) => GEN === 'all' || String(p.gen) === GEN);
const probe = { token: null, ua: 'practice', deviceFor: () => null };
const run = await call(probe, 'GET', `/api/v1/runs/${RUN_ID}?from=${FROM}&to=${TO}`);
const runDate = run.data?.runDate;
if (!runDate) { console.error(`운행 ${RUN_ID}을 찾지 못했습니다 (${run.status})`); process.exit(1); }
console.log(`연습 ${chosen.length}종 × 명의 ${ACCOUNTS} + 합성 사람 ${HUMANS} · 운행 ${RUN_ID} (${runDate}) · 태그 ${TAG} · ${MODE}`);

const t0 = Date.now();
const jobs = chosen.map((p) => runProfile(p, p.count || ACCOUNTS, runDate).then((c) => [p, c]));
if (HUMANS && (!want.length || want.includes('human'))) jobs.push(runProfile(HUMAN, HUMANS, runDate).then((c) => [HUMAN, c]));
const done = await Promise.all(jobs);

console.log(`\n끝 (${Math.round((Date.now() - t0) / 1000)}초)\n종류            성질          결제 좌석  오류`);
for (const [p, ctxs] of done) {
  const errs = ctxs.flatMap((c) => c.errors);
  console.log(`${p.kind.padEnd(15)} ${p.family.padEnd(12)} ${String(ctxs.reduce((a, c) => a + c.seats, 0)).padStart(5)}    ${errs.length ? [...new Set(errs)].slice(0, 2).join(' / ') : '-'}`);
}
console.log(`\n평가: loadtest/tools/eval_contest.sh ${TAG} ${RUN_ID}`);
