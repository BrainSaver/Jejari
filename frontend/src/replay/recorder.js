import { get, post, subscribeAuth, tokenClaims } from '../api/client.js';

/**
 * ADM-11 화면 녹화기 (회원 화면만). rrweb 같은 DOM 녹화가 아니라 "무엇을 어디서 눌렀나"만 가볍게 남긴다.
 *
 *   PAGE_VIEW  화면 이동 (주소의 숫자는 :id로 바꿔 화면 ID로)
 *   CLICK      클릭 위치(화면 비율 0~1000)와 요소 ID (data-rec > id > 태그.클래스), 입력 장치(pointer: m·t·p·k)
 *   MOUSE_PATH 마우스 궤적 [[dt, x, y], …] (사람은 곡선·가감속, 매크로는 직선·순간이동).
 *              FULL은 0.05초마다, 평소(SAMPLED)는 0.15초마다 — 간격·점 수는 서버가 정해 준다(config)
 *   SCROLL / VISIBILITY / INPUT(길이만. 입력값은 절대 보내지 않음)
 *   LAYOUT     그 순간 화면의 뼈대: 버튼·입력 칸·탭·제목·좌석 상자의 위치·종류(+좌석 상태). 재생 배경이 된다.
 *              화면 글자는 담지 않고, 버튼 문구만 "숫자 없는 짧은 글자"일 때 담는다 (결제하기, 열차 찾기)
 *
 * 서버가 정한 간격(replay.batch_sec)마다 묶어 POST /api/v1/replay/events. 실패하면 버린다(녹화 때문에 화면이 느려지면 안 됨).
 * 세션은 로그인 세션(토큰의 sid)이라 새로고침해도 이어진다. seq는 "지금 시각 × 1000"부터 늘려 새로고침 뒤에도 겹치지 않게.
 */
const MAX_BUFFER = 500;
const PATH_SAMPLE_MS = 150; // 서버 설정이 오기 전 기본값

let started = false;
let config = null; // {enabled, mode, batchSec, maxPathPoints, pathSampleMs}
let sid = null;
let buffer = [];
let lastSeq = 0;
let timer = null;
let path = [];
let pathStart = 0;
let lastMove = 0;
let lastScroll = 0;
let lastScreen = null;
let layoutTimer = null;
let lastLayout = '';
let lastLayoutAt = 0;
const MAX_BOXES = 400;

export function startRecorder() {
  if (started || typeof window === 'undefined') return;
  started = true;
  subscribeAuth(onAuth);
  onAuth(tokenClaims());
  document.addEventListener('click', onClick, true);
  document.addEventListener('mousemove', onMove, { passive: true });
  document.addEventListener('scroll', onScroll, { passive: true, capture: true });
  document.addEventListener('input', onInput, true);
  document.addEventListener('visibilitychange', onVisibility);
  setInterval(checkScreen, 500); // 라우터 이동 감지 (history API를 건드리지 않고)
  // 화면이 바뀌면(좌석 상태·목록 도착) 뼈대를 다시 잰다. 1초 모아서 한 번
  new MutationObserver(() => scheduleLayout(1000)).observe(document.body, {
    subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'disabled', 'aria-selected'],
  });
  window.addEventListener('resize', () => scheduleLayout(800));
}

function onAuth(claims) {
  const member = claims?.role === 'MEMBER' ? claims : null;
  const next = member ? member.sid || String(member.sub) : null;
  if (next === sid) return;
  flush();
  sid = next;
  config = null;
  clearInterval(timer);
  if (!sid) return;
  get('/api/v1/replay/config')
    .then((c) => {
      if (!c.enabled || sid !== next) return;
      config = c;
      lastScreen = null;
      checkScreen();
      timer = setInterval(flush, Math.max(1, c.batchSec) * 1000);
    })
    .catch(() => {});
}

const recording = () => config && !location.pathname.startsWith('/admin');

function push(e) {
  if (!recording()) return;
  const now = Date.now();
  lastSeq = Math.max(lastSeq + 1, now * 1000);
  if (buffer.length >= MAX_BUFFER) buffer.shift();
  buffer.push({ seq: lastSeq, ts: now, screen: screen(), runId: runId(), ...e });
}

function flush() {
  flushPath();
  if (!buffer.length || !config) return;
  const events = buffer;
  buffer = [];
  post('/api/v1/replay/events', { events }).catch(() => {});
}

/** /runs/9001/seats → runs/:id/seats (숫자 ID는 녹화에 남기지 않는다) */
function screen() {
  const p = location.pathname.replace(/\/\d+/g, '/:id').replace(/^\/|\/$/g, '');
  return (p || 'home').slice(0, 64);
}

function runId() {
  const m = /\/(?:runs|queue)\/(\d+)/.exec(location.pathname);
  return m ? Number(m[1]) : undefined;
}

function checkScreen() {
  if (!recording()) return;
  const s = screen();
  if (s !== lastScreen) {
    lastScreen = s;
    lastLayout = '';
    push({ type: 'PAGE_VIEW' });
    scheduleLayout(400); // 그려진 뒤에
  }
}

const nx = (x) => Math.round((x / Math.max(1, window.innerWidth)) * 1000);
const ny = (y) => Math.round((y / Math.max(1, window.innerHeight)) * 1000);

/** 요소 ID: data-rec > id > 태그.첫클래스. 서버 형식(영문·숫자·_:-./ 64자)에 맞지 않으면 보내지 않음 */
function target(el) {
  const rec = el?.closest?.('[data-rec]')?.getAttribute('data-rec');
  const t = rec || el?.id || (el?.tagName ? el.tagName.toLowerCase() + (el.classList?.[0] ? '.' + el.classList[0] : '') : '');
  return /^[A-Za-z0-9_:\-./]{1,64}$/.test(t) ? t : undefined;
}

/** 버튼·탭·링크의 문구: 숫자 없는 짧은 글자만 (서버도 같은 규칙으로 다시 거름) */
const LABEL_RE = /^[가-힣A-Za-z ·]{1,20}$/;
function label(el) {
  const c = el?.closest?.('button, a, [role="tab"], label');
  if (!c) return undefined;
  const t = (c.getAttribute('aria-label') || c.textContent || '').replace(/\s+/g, ' ').trim();
  return LABEL_RE.test(t) ? t : undefined;
}

/**
 * 클릭을 만든 입력 장치: m 마우스 · t 터치 · p 펜 · k 키보드(Enter·스페이스, detail 0).
 * 서버의 마우스 궤적 규칙(LINEAR_MOUSE)은 마우스 클릭만 본다 — 휴대폰은 탭한 자리에 마우스 이동을 흉내 내서
 * 매크로의 "클릭 자리로 순간이동"과 똑같아 보이기 때문
 */
function pointerOf(ev) {
  const t = ev.pointerType;
  if (t === 'mouse' || t === 'touch' || t === 'pen') return t[0];
  return ev.detail === 0 ? 'k' : undefined;
}

function onClick(ev) {
  push({ type: 'CLICK', x: nx(ev.clientX), y: ny(ev.clientY), target: target(ev.target), label: label(ev.target), pointer: pointerOf(ev) });
}

// ------------------------------------------------------------ 화면 뼈대 (LAYOUT)

function scheduleLayout(ms) {
  if (!recording()) return;
  clearTimeout(layoutTimer);
  layoutTimer = setTimeout(captureLayout, ms);
}

const SEAT_STATE = ['mine', 'lost', 'hold', 'taken', 'free'];
function kindOf(el) {
  if (el.classList.contains('seat')) {
    const st = SEAT_STATE.find((c) => el.classList.contains(c)) || 'free';
    return 'seat-' + st;
  }
  const tag = el.tagName;
  if (el.getAttribute('role') === 'tab') return 'tab';
  if (tag === 'BUTTON') return 'button';
  if (tag === 'A') return 'link';
  if (tag === 'SELECT') return 'select';
  if (tag === 'INPUT' || tag === 'TEXTAREA') return el.type === 'radio' || el.type === 'checkbox' ? 'button' : 'input';
  if (tag === 'H1' || tag === 'H2') return 'heading';
  return null;
}

function captureLayout() {
  if (!recording()) return;
  const now = performance.now();
  if (now - lastLayoutAt < 1000) return scheduleLayout(1000 - (now - lastLayoutAt));
  const W = Math.max(1, window.innerWidth);
  const H = Math.max(1, window.innerHeight);
  const boxes = [];
  for (const el of document.querySelectorAll('button, a[href], input, select, textarea, h1, h2, [role="tab"], .seat')) {
    if (boxes.length >= MAX_BOXES) break;
    if (el.closest('.admin')) continue;
    const k = kindOf(el);
    if (!k) continue;
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2 || r.bottom < 0 || r.right < 0 || r.top > H || r.left > W) continue; // 화면 밖
    const b = { k, x: Math.round((r.left / W) * 1000), y: Math.round((r.top / H) * 1000),
      w: Math.round((r.width / W) * 1000), h: Math.round((r.height / H) * 1000) };
    const id = target(el);
    if (id && el.closest('[data-rec]')) b.id = id;
    if (k === 'button' || k === 'tab' || k === 'link' || k === 'heading') {
      const t = (el.getAttribute('aria-label') || el.textContent || '').replace(/\s+/g, ' ').trim();
      if (LABEL_RE.test(t)) b.label = t;
    }
    if (el.disabled) b.off = true;
    boxes.push(b);
  }
  if (!boxes.length) return;
  const sig = JSON.stringify(boxes);
  if (sig === lastLayout) return; // 바뀐 게 없으면 보내지 않음
  lastLayout = sig;
  lastLayoutAt = now;
  push({ type: 'LAYOUT', boxes });
}

function onMove(ev) {
  if (!recording()) return;
  const now = performance.now();
  if (now - lastMove < (config.pathSampleMs || PATH_SAMPLE_MS)) return;
  lastMove = now;
  if (!path.length) pathStart = now;
  path.push([Math.round(now - pathStart), nx(ev.clientX), ny(ev.clientY)]);
  if (path.length >= (config.maxPathPoints || 16)) flushPath();
}

function flushPath() {
  if (path.length) {
    push({ type: 'MOUSE_PATH', path });
    path = [];
  }
}

function onScroll() {
  const now = performance.now();
  if (now - lastScroll < 500) return;
  lastScroll = now;
  const max = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
  push({ type: 'SCROLL', y: Math.round((window.scrollY / max) * 1000) });
  scheduleLayout(700); // 스크롤이 멈추면 보이는 뼈대가 달라짐
}

function onInput(ev) {
  const el = ev.target;
  if (!el || typeof el.value !== 'string') return;
  push({ type: 'INPUT', target: target(el), inputLen: el.value.length }); // 길이만
}

function onVisibility() {
  push({ type: 'VISIBILITY', target: document.visibilityState });
  if (document.visibilityState === 'hidden') flush();
}
