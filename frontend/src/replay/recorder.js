import { get, post, subscribeAuth, tokenClaims } from '../api/client.js';

/**
 * ADM-11 화면 녹화기 (회원 화면만). rrweb 같은 DOM 녹화가 아니라 "무엇을 어디서 눌렀나"만 가볍게 남긴다.
 *
 *   PAGE_VIEW  화면 이동 (주소의 숫자는 :id로 바꿔 화면 ID로)
 *   CLICK      클릭 위치(화면 비율 0~1000)와 요소 ID (data-rec > id > 태그.클래스)
 *   MOUSE_PATH 마우스 궤적 [[dt, x, y], …] (FULL 녹화일 때만. 사람은 곡선·가감속, 매크로는 직선·순간이동)
 *   SCROLL / VISIBILITY / INPUT(길이만. 입력값은 절대 보내지 않음)
 *
 * 서버가 정한 간격(replay.batch_sec)마다 묶어 POST /api/v1/replay/events. 실패하면 버린다(녹화 때문에 화면이 느려지면 안 됨).
 * 세션은 로그인 세션(토큰의 sid)이라 새로고침해도 이어진다. seq는 "지금 시각 × 1000"부터 늘려 새로고침 뒤에도 겹치지 않게.
 */
const MAX_BUFFER = 500;
const PATH_SAMPLE_MS = 50;

let started = false;
let config = null; // {enabled, mode, batchSec, maxPathPoints}
let sid = null;
let buffer = [];
let lastSeq = 0;
let timer = null;
let path = [];
let pathStart = 0;
let lastMove = 0;
let lastScroll = 0;
let lastScreen = null;

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
    push({ type: 'PAGE_VIEW' });
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

function onClick(ev) {
  push({ type: 'CLICK', x: nx(ev.clientX), y: ny(ev.clientY), target: target(ev.target) });
}

function onMove(ev) {
  if (!recording() || config.mode !== 'FULL') return;
  const now = performance.now();
  if (now - lastMove < PATH_SAMPLE_MS) return;
  lastMove = now;
  if (!path.length) pathStart = now;
  path.push([Math.round(now - pathStart), nx(ev.clientX), ny(ev.clientY)]);
  if (path.length >= (config.maxPathPoints || 50)) flushPath();
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
