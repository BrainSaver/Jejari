/* 제자리 설계 문서 사이트 (외부 라이브러리는 marked만, /vendor에 포함) */
(() => {
'use strict';

const DOCS = {
  plan: { path: 'README.md', title: '기획서', sub: '프로젝트 개요와 역할 분배' },
  func: { path: 'docs/기능설계서.md', title: '기능설계', sub: '기능 목록·상세·상태 전이·API·이벤트' },
  db: { path: 'docs/DB설계서.md', title: 'DB설계', sub: '저장소별 역할, 테이블 정의, 상태 코드' },
  conc: { path: 'docs/concurrency.md', title: '동시성', sub: '트랜잭션 순서·SQL·Redis Lua·락 순서 (구현의 원천)' },
};
const DOC_FILTERS = [
  ['', '모든 문서'], ['README.md', '기획서'], ['docs/기능설계서.md', '기능설계'], ['docs/DB설계서.md', 'DB설계'],
  ['docs/erd', 'ERD'], ['docs/ui', '화면설계'], ['docs/concurrency.md', '동시성'],
];
const ERD_PATH = 'docs/erd/jejari-erd.html';

const S = { me: null, ko: null, admin: false, members: {}, pins: {}, ai: false, ref: 'main', branches: null };
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const main = $('#main');

// ---------- 공통 ----------
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const store = {
  get(k, d) { try { const v = localStorage.getItem('jejari-site:' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('jejari-site:' + k, JSON.stringify(v)); } catch {} },
};
function kst(iso, withYear) {
  if (!iso) return '';
  const d = new Date(new Date(iso).getTime() + 9 * 3600 * 1000);
  const p = (n) => String(n).padStart(2, '0');
  return `${withYear ? d.getUTCFullYear() + '-' : ''}${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}`;
}
const colorOf = (name) => (S.members[name] || name === 'main') ? `var(--m-${name})` : 'var(--muted)';
const nameTag = (name) => `<span class="name"><span class="dot" style="background:${colorOf(name)}"></span> ${esc(name)}${S.members[name] ? ` <span class="muted">${esc(S.members[name])}</span>` : ''}</span>`;
const refTag = (r) => `<span class="ref-tag" style="background:${colorOf(r)}">${esc(r)}</span>`;
const firstLine = (m) => String(m || '').split('\n')[0];
const isMerge = (c) => (c.parents || []).length > 1 || /^Merge (branch|pull request|remote)/.test(c.message || '') || /^merge: main →/.test(c.message || '');

// 읽기(GET) 응답은 메모리에 두고 다시 열 때 바로 쓴다. 15초가 지난 것은 바로 보여 주면서 뒤에서 새로 받아 둔다.
// 저장·합치기 뒤에는 bump()가 비운다. t= 가 붙은 요청(폴링·기록)과 nocache는 늘 새로 받는다.
const CACHE = new Map(), INFLIGHT = new Map();
const CACHE_FRESH = 15000;
function apiGet(path) {
  if (INFLIGHT.has(path)) return INFLIGHT.get(path);
  const p = apiRaw(path).then((d) => { CACHE.set(path, { at: Date.now(), d }); return d; }).finally(() => INFLIGHT.delete(path));
  INFLIGHT.set(path, p);
  return p;
}
async function api(path, opts = {}) {
  if (!opts.body && !opts.method && !opts.nocache && !/[?&]t=/.test(path)) {
    const c = CACHE.get(path);
    if (c) { if (Date.now() - c.at > CACHE_FRESH) apiGet(path).catch(() => {}); return c.d; }
    return apiGet(path);
  }
  return apiRaw(path, opts);
}
async function apiRaw(path, opts = {}) {
  const init = { method: opts.method || 'GET', headers: {}, credentials: 'same-origin' };
  if (opts.body) { init.method = 'POST'; init.headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(opts.body); }
  const res = await fetch('/api' + path, init);
  let data = null;
  try { data = await res.json(); } catch { data = { error: 'BAD_RESPONSE', message: '서버 응답을 읽을 수 없어요.' }; }
  if (!res.ok) { const e = new Error(data.message || '요청 실패'); Object.assign(e, data, { status: res.status }); throw e; }
  return data;
}
const fresh = { n: 0 };
const qs = (o) => '?' + new URLSearchParams({ ...o, ...(fresh.n ? { fresh: String(fresh.n) } : {}) });
function bump() { fresh.n = Date.now(); CACHE.clear(); }
// 처음 열고 한가할 때 자주 가는 페이지 내용을 미리 받아 둔다 (메뉴를 눌렀을 때 로딩 없이)
function prefetch() {
  const go = async () => {
    const refs = S.ref === 'main' ? ['main'] : [S.ref, 'main'];
    const paths = [...Object.values(DOCS).map((d) => d.path), 'docs/ui/index.json', ERD_PATH];
    for (const r of refs) for (const path of paths) { try { await fetchFile(path, r); } catch {} }
    try { if (!RM.data) await rmLoad(); } catch {}
    try { await api('/commits' + qs({})); } catch {}
  };
  (window.requestIdleCallback || ((f) => setTimeout(f, 800)))(() => go());
}

let toastTimer;
function toast(msg, ms = 3500) {
  const t = $('#toast'); t.textContent = msg; t.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { t.hidden = true; }, ms);
}
function openModal(html, { wide } = {}) {
  const m = $('#modal'); m.className = 'modal' + (wide ? ' wide' : ''); m.innerHTML = html;
  $('#modalBack').hidden = false;
  const f = m.querySelector('[autofocus]'); if (f) setTimeout(() => f.focus(), 30);
  return m;
}
function closeModal() { $('#modalBack').hidden = true; $('#modal').innerHTML = ''; }
$('#modalBack').addEventListener('click', (e) => { if (e.target.id === 'modalBack') closeModal(); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !$('#modalBack').hidden) closeModal(); });

// 두 번 눌러야 실행되는 버튼
function twoStep(btn, label, run) {
  let armed = false; const orig = btn.textContent;
  btn.addEventListener('click', async () => {
    if (!armed) { armed = true; btn.textContent = label; setTimeout(() => { armed = false; btn.textContent = orig; }, 4000); return; }
    armed = false; btn.disabled = true; try { await run(); } finally { btn.disabled = false; btn.textContent = orig; }
  });
}

// ---------- 마크다운 ----------
marked.use({ gfm: true });
// 문서 안의 HTML은 안전한 태그·속성만 남긴다 (README의 details·summary·a id 등)
const SAFE_TAGS = new Set(['A','B','STRONG','I','EM','U','S','DEL','INS','MARK','SMALL','SUB','SUP','KBD','CODE','PRE','BR','HR','P','DIV','SPAN','DETAILS','SUMMARY','BLOCKQUOTE','UL','OL','LI','TABLE','THEAD','TBODY','TR','TH','TD','IMG','H1','H2','H3','H4','H5','H6','DL','DT','DD','INPUT','CENTER']);
const DROP_TAGS = new Set(['SCRIPT','STYLE','IFRAME','OBJECT','EMBED','FORM','TEXTAREA','SELECT','BUTTON','LINK','META','BASE','SVG','MATH','TEMPLATE','NOSCRIPT']);
const SAFE_ATTR = { '*': ['align','title','colspan','rowspan'], A: ['href','id','name'], IMG: ['src','alt','width','height'], DETAILS: ['open'], OL: ['start'], INPUT: ['type','checked','disabled'] };
function sanitize(root) {
  const walk = (node) => {
    [...node.children].forEach((el) => {
      if (DROP_TAGS.has(el.tagName)) { el.remove(); return; }
      if (!SAFE_TAGS.has(el.tagName)) { walk(el); el.replaceWith(...el.childNodes); return; }
      const ok = [...SAFE_ATTR['*'], ...(SAFE_ATTR[el.tagName] || [])];
      [...el.attributes].forEach((at) => { if (!ok.includes(at.name) && !(el.tagName === 'CODE' && at.name === 'class' && /^language-[\w-]+$/.test(at.value))) el.removeAttribute(at.name); });
      const href = el.getAttribute('href');
      if (el.tagName === 'A' && href != null && !/^(https?:|#|mailto:)/i.test(href.trim())) el.removeAttribute('href');
      if (el.tagName === 'A' && href && /^https?:/i.test(href)) { el.target = '_blank'; el.rel = 'noopener'; }
      if (el.tagName === 'IMG' && !/^https:/i.test(el.getAttribute('src') || '')) el.removeAttribute('src');
      if (el.tagName === 'INPUT' && el.getAttribute('type') !== 'checkbox') { el.remove(); return; }
      if (el.tagName === 'INPUT') el.setAttribute('disabled', '');
      ['id', 'name'].forEach((k) => { if (el.hasAttribute(k)) el.setAttribute(k, 'h-' + el.getAttribute(k)); });
      walk(el);
    });
  };
  walk(root);
}
const slug = (s) => String(s).trim().toLowerCase().replace(/<[^>]+>/g, '').replace(/[^\p{L}\p{N}\s_-]/gu, '').replace(/\s/g, '-');
function renderMd(text) {
  const div = document.createElement('div');
  div.className = 'md';
  const tpl = document.createElement('template');
  tpl.innerHTML = marked.parse(text || '');
  sanitize(tpl.content);
  div.appendChild(tpl.content);
  $$('h1,h2,h3,h4', div).forEach((h) => { h.id = 'h-' + slug(h.textContent); });
  $$('details > summary', div).forEach((sm) => { if (!sm.id) sm.id = 'h-' + slug(sm.textContent); });
  $$('table', div).forEach((t) => { if (!t.parentElement.classList.contains('tbl-wrap')) { const w = document.createElement('div'); w.className = 'tbl-wrap'; t.replaceWith(w); w.appendChild(t); } });
  $$('pre > code.language-mermaid', div).forEach((code) => {
    const box = document.createElement('div'); box.className = 'mermaid-box'; box.dataset.src = code.textContent;
    box.innerHTML = '<p class="muted">그림 그리는 중…</p>';
    code.parentElement.replaceWith(box);
  });
  if ($('.mermaid-box', div)) drawMermaid(div);
  return div;
}
// mermaid 그림 (2.5MB라 그림이 있는 문서에서만 불러온다)
let mermaidLoad = null;
const darkMode = () => document.documentElement.dataset.theme === 'dark' || (document.documentElement.dataset.theme !== 'light' && matchMedia('(prefers-color-scheme: dark)').matches);
function loadMermaid() {
  if (!mermaidLoad) mermaidLoad = new Promise((ok, no) => {
    const sc = document.createElement('script'); sc.src = '/vendor/mermaid.min.js';
    sc.onload = () => { window.mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: darkMode() ? 'dark' : 'default', fontFamily: 'IBM Plex Sans KR, sans-serif' }); ok(window.mermaid); };
    sc.onerror = () => { mermaidLoad = null; no(new Error('mermaid load')); };
    document.head.appendChild(sc);
  });
  return mermaidLoad;
}
let mermaidSeq = 0;
async function drawMermaid(root) {
  let m;
  try { m = await loadMermaid(); } catch { $$('.mermaid-box', root).forEach((b) => { b.innerHTML = `<pre><code>${esc(b.dataset.src)}</code></pre>`; }); return; }
  for (const b of $$('.mermaid-box', root)) {
    try { const { svg } = await m.render('mmd-' + (++mermaidSeq), b.dataset.src); b.innerHTML = svg; }
    catch (e) { b.innerHTML = `<p class="err">그림 문법 오류: ${esc(e.message || e)}</p><pre><code>${esc(b.dataset.src)}</code></pre>`; }
  }
}
document.addEventListener('click', (e) => {
  const a = e.target.closest && e.target.closest('.md a[href^="#"]');
  if (!a || a.getAttribute('href').startsWith('#/')) return;
  e.preventDefault();
  const id = 'h-' + slug(decodeURIComponent(a.getAttribute('href').slice(1)).replace(/-/g, ' '));
  const el = document.getElementById(id) || document.getElementById('h-' + decodeURIComponent(a.getAttribute('href').slice(1)));
  if (el) el.scrollIntoView({ behavior: 'smooth' });
});

// ## 기준으로 나누기. 이어 붙이면 원문과 똑같아야 한다.
function splitSections(text) {
  const lines = text.split('\n');
  const starts = [0];
  let off = 0, fence = null;
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i];
    const f = l.match(/^\s*(```|~~~)/);
    if (f) fence = fence ? (fence === f[1] ? null : fence) : f[1];
    else if (!fence && (/^## /.test(l) || /^<a id="sec-[\w-]+"><\/a>\s*$/.test(l)) && off > 0) starts.push(off);
    off += l.length + 1;
  }
  const out = [];
  for (let i = 0; i < starts.length; i++) {
    const t = text.slice(starts[i], i + 1 < starts.length ? starts[i + 1] : text.length);
    const fl = t.split('\n')[0];
    const sum = /^<a id=/.test(fl) ? (t.match(/<summary>(.*?)<\/summary>/) || [])[1] : null;
    out.push({ text: t, heading: /^## /.test(fl) ? fl : (sum != null ? fl + ' ' + sum.replace(/<[^>]+>/g, '') : '') });
  }
  return out.filter((s, i) => s.text.length || i === 0);
}
const headOf = (s) => s.heading || (s.text.match(/^# .*/m) || [''])[0] || '(머리말)';

// ---------- 패치(diff) ----------
function renderPatch(patch) {
  if (!patch) return '<div class="h">(내용 차이를 표시할 수 없는 파일이에요. 너무 크거나 이진 파일)</div>';
  return patch.split('\n').map((l) => {
    const c = l.startsWith('@@') ? 'h' : l.startsWith('+') ? 'a' : l.startsWith('-') ? 'd' : '';
    return `<div class="${c}">${esc(l) || ' '}</div>`;
  }).join('');
}
function renderFiles(files) {
  if (!files.length) return '<p class="empty">바뀐 파일이 없어요.</p>';
  return files.map((f) => `<div class="diff-file"><div class="diff-head"><b>${esc(f.filename)}</b>
    <span class="badge ok">+${f.additions}</span><span class="badge warn">-${f.deletions}</span><span class="muted">${esc(f.status)}</span></div>
    <div class="diff-body">${renderPatch(f.patch)}</div></div>`).join('');
}
// 두 글 사이의 줄 차이 (작은 LCS)
function lineDiff(a, b) {
  const A = a.split('\n'), B = b.split('\n');
  if (A.length * B.length > 4e6) return `@@ 너무 길어 비교를 줄였어요 @@\n` + B.map((l) => '+' + l).join('\n');
  const n = A.length, m = B.length, dp = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) dp[i][j] = A[i] === B[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
  const out = []; let i = 0, j = 0;
  while (i < n && j < m) { if (A[i] === B[j]) { out.push(' ' + A[i]); i++; j++; } else if (dp[i + 1][j] >= dp[i][j + 1]) out.push('-' + A[i++]); else out.push('+' + B[j++]); }
  while (i < n) out.push('-' + A[i++]); while (j < m) out.push('+' + B[j++]);
  return out.join('\n');
}

// ---------- 로그인 ----------
async function loadMe() {
  const d = await api('/me' + qs({}), { nocache: true });
  Object.assign(S, { me: d.name, ko: d.ko, admin: d.admin, members: d.members, pins: d.pins, ai: d.ai });
  renderTop();
}
async function loadBranches() {
  if (!S.me) { S.branches = null; renderTop(); return; }
  try { S.branches = await api('/branches' + qs({}), { nocache: true }); } catch { S.branches = null; }
  renderTop();
}
function myAhead() { const b = S.branches && S.branches.branches.find((x) => x.name === S.me); return b ? b.ahead : 0; }
function myBehind() { const b = S.branches && S.branches.branches.find((x) => x.name === S.me); return b ? b.behind : 0; }
function renderTop() {
  aiFabSync();
  document.body.classList.toggle('anon', !S.me);
  if (!S.me && S.ref !== 'main') S.ref = 'main';
  $('#sideFoot').textContent = S.me ? '수정은 섹션마다 "수정"을 눌러요. 저장하면 내 이름 브랜치에 커밋돼요.' : '팀원은 로그인하면 문서를 고치고 수정 기록·소스트리·브랜치별 내용을 볼 수 있어요.';
  const sel = $('#refSelect');
  const opts = [['main', 'main (합쳐진 최신)'], ...Object.keys(S.members).map((n) => {
    const b = S.branches && S.branches.branches.find((x) => x.name === n);
    return [n, `${n} ${S.members[n]}${b && b.ahead ? ` · +${b.ahead}` : ''}`];
  })];
  sel.innerHTML = opts.map(([v, l]) => `<option value="${v}"${v === S.ref ? ' selected' : ''}>${esc(l)}</option>`).join('');
  $('#loginBtn').hidden = !!S.me;
  $('#userBox').hidden = !S.me;
  if (S.me) $('#userChip').innerHTML = `<span class="dot" style="background:${colorOf(S.me)}"></span>${esc(S.me)} <span class="muted">${esc(S.ko || '')}</span>`;
  $('#resetMenu').hidden = !S.admin;
  $('#mergeBtn').hidden = !(S.me && myAhead() > 0);
  if (S.me) $('#mergeBtn').textContent = `main에 합치기 (${myAhead()})`;
  $('#syncBtn').hidden = !(S.me && myBehind() > 0);
  if (S.me) $('#syncBtn').textContent = `main 가져오기 (${myBehind()})`;
}
$('#refSelect').addEventListener('change', (e) => { setRef(e.target.value); });
function setRef(r) { S.ref = r; store.set('ref', r); renderTop(); route(); }

function loginModal(after) {
  let pick = null;
  const m = openModal(`<h2>로그인</h2>
    <p class="muted" style="margin:0 0 8px">이름은 브랜치 이름이라 바꿀 수 없어요. 처음이면 지금 정하는 숫자 4자리가 PIN이 돼요.</p>
    <div class="names">${Object.keys(S.members).map((n) => `<button data-n="${n}"><b>${n}</b><small>${esc(S.members[n])}${S.pins[n] ? '' : ' · PIN 설정 전'}</small></button>`).join('')}</div>
    <form id="pinForm" hidden>
      <div class="field"><label id="pinLabel">PIN</label><input class="pin" id="pin1" type="password" inputmode="numeric" pattern="\\d{4}" maxlength="4" autocomplete="off"></div>
      <div class="field" id="pin2Wrap" hidden><label>한 번 더</label><input class="pin" id="pin2" type="password" inputmode="numeric" maxlength="4" autocomplete="off"></div>
      <p class="err" id="pinErr"></p>
      <div class="row"><button type="button" class="btn plain" data-x>취소</button><button class="btn" id="pinGo">들어가기</button></div>
    </form>`);
  $$('.names button', m).forEach((b) => b.addEventListener('click', () => {
    pick = b.dataset.n; $$('.names button', m).forEach((x) => x.classList.toggle('on', x === b));
    const first = !S.pins[pick];
    $('#pinForm', m).hidden = false; $('#pin2Wrap', m).hidden = !first;
    $('#pinLabel', m).textContent = first ? `${pick}의 PIN 정하기 (숫자 4자리)` : `${pick}의 PIN`;
    $('#pinGo', m).textContent = first ? 'PIN 정하고 들어가기' : '들어가기';
    $('#pinErr', m).textContent = ''; $('#pin1', m).value = ''; $('#pin2', m).value = ''; $('#pin1', m).focus();
  }));
  $('[data-x]', m).addEventListener('click', closeModal);
  $('#pinForm', m).addEventListener('submit', async (e) => {
    e.preventDefault();
    const p1 = $('#pin1', m).value, p2 = $('#pin2', m).value, err = $('#pinErr', m);
    if (!/^\d{4}$/.test(p1)) { err.textContent = '숫자 4자리를 넣어 주세요.'; return; }
    if (!S.pins[pick] && p1 !== p2) { err.textContent = '두 번 넣은 PIN이 달라요.'; return; }
    try {
      const r = await api('/login', { body: { name: pick, pin: p1 } });
      closeModal(); await loadMe(); loadBranches();
      toast(r.firstSet ? `${pick}의 PIN을 정했어요. 다음부터 이 PIN으로 들어와요.` : `${pick}로 들어왔어요.`);
      if (after) after();
    } catch (x) { err.textContent = x.message; }
  });
}
$('#loginBtn').addEventListener('click', () => loginModal(() => route()));
$('#userChip').addEventListener('click', (e) => { e.stopPropagation(); $('#userMenu').hidden = !$('#userMenu').hidden; });
document.addEventListener('click', () => { $('#userMenu').hidden = true; });
$('#userMenu').addEventListener('click', async (e) => {
  const act = e.target.dataset.act; if (!act) return;
  if (act === 'logout') { await api('/logout', { body: {} }).catch(() => {}); S.ref = 'main'; store.set('ref', 'main'); await loadMe(); toast('로그아웃했어요.'); route(); }
  if (act === 'mine') setRef(S.me);
  if (act === 'reset') resetModal();
});
function resetModal() {
  const m = openModal(`<h2>PIN 초기화</h2><p class="muted" style="margin:0 0 10px">초기화한 이름은 다음에 들어올 때 PIN을 새로 정해요.</p>
    <table class="list-tbl">${Object.keys(S.members).map((n) => `<tr><td>${nameTag(n)}</td><td>${S.pins[n] ? '<span class="badge ok">설정됨</span>' : '<span class="badge">설정 전</span>'}</td>
    <td style="text-align:right">${S.pins[n] ? `<button class="btn sm danger" data-n="${n}">초기화</button>` : ''}</td></tr>`).join('')}</table>
    <div class="row"><button class="btn plain" data-x>닫기</button></div>`);
  $('[data-x]', m).addEventListener('click', closeModal);
  $$('button[data-n]', m).forEach((b) => twoStep(b, '한 번 더 누르면 초기화', async () => {
    try { await api('/admin/reset-pin', { body: { name: b.dataset.n } }); await loadMe(); toast(`${b.dataset.n}의 PIN을 지웠어요.`); resetModal(); }
    catch (x) { toast(x.message); }
  }));
}
function needLogin(then) { if (S.me) return true; loginModal(then); return false; }

// ---------- 커밋 메시지 ----------
// 저장 직전에 AI 메시지를 받아 보여주고, 고친 메시지로 run(message)를 부른다.
async function commitFlow({ path, heading, before, after, run, title }) {
  const m = openModal(`<h2>${esc(title || '저장')}</h2>
    <p class="muted" style="margin:0">${esc(S.me)} 브랜치에 커밋해요. 저장 전에 main의 최신 내용을 먼저 합쳐요.</p>
    <div class="field"><label>커밋 메시지 <span class="muted" id="aiNote">${S.ai ? 'AI가 쓰는 중…' : ''}</span></label>
    <input type="text" class="msg-input" id="msgInput" maxlength="200" disabled></div>
    <p class="err" id="msgErr"></p>
    <div class="row"><button class="btn plain" data-x>취소</button><button class="btn" id="msgGo" disabled>커밋</button></div>`);
  $('[data-x]', m).addEventListener('click', closeModal);
  let r;
  try { r = await api('/commit-message', { body: { path, heading, before, after } }); }
  catch { r = { message: `docs: ${path.split('/').pop()} 수정`, ai: false }; }
  if (!document.body.contains(m) || $('#modalBack').hidden) return;
  const inp = $('#msgInput', m); inp.value = r.message; inp.disabled = false; inp.focus(); inp.select();
  $('#aiNote', m).textContent = r.ai ? '(AI 작성, 고쳐도 돼요)' : (S.ai ? `(기본 형식 · ${r.reason || ''})` : '(기본 형식)');
  const go = $('#msgGo', m); go.disabled = false;
  const submit = async () => {
    const msg = inp.value.trim();
    if (msg.length < 3) { $('#msgErr', m).textContent = '메시지를 써 주세요.'; return; }
    go.disabled = true; go.textContent = '저장 중…';
    try { await run(msg); } catch (x) { go.disabled = false; go.textContent = '커밋'; $('#msgErr', m).textContent = x.message; }
  };
  go.addEventListener('click', submit);
  inp.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });
}
function conflictModal(x) {
  openModal(`<h2>main과 충돌</h2><p>${esc(x.message)}</p>
    ${x.files && x.files.length ? `<p class="muted">main과 내 브랜치가 둘 다 바꾼 파일</p><ul>${x.files.map((f) => `<li><code>${esc(f)}</code></li>`).join('')}</ul>` : ''}
    <p class="muted">GitHub에서 ${esc(S.me)} 브랜치에 main을 합치며 충돌을 풀면 다시 저장할 수 있어요.</p>
    <div class="row"><button class="btn" data-x>닫기</button></div>`);
  $('#modal [data-x]').addEventListener('click', closeModal);
}
function afterSave(msg) {
  closeModal(); bump(); toast(msg || `${S.me} 브랜치에 저장했어요. 다 고쳤으면 위의 "main에 합치기"를 눌러 주세요.`, 5000);
  if (S.ref !== S.me) { S.ref = S.me; store.set('ref', S.me); }
  loadBranches(); route();
}

// ---------- 문서 보기 + 섹션 편집 ----------
async function fetchFile(path, ref) {
  try { return await api('/file' + qs({ path, ref })); } catch (e) { if (e.status === 404) return null; throw e; }
}
function branchBanner(ref) {
  if (ref === 'main') return '';
  const b = S.branches && S.branches.branches.find((x) => x.name === ref);
  const st = b ? `main보다 ${b.ahead}개 앞, ${b.behind}개 뒤` : '';
  return `<div class="banner info">${nameTag(ref)} 브랜치를 보고 있어요. <span class="muted">${st}</span>
    <span class="acts">${ref === S.me && b && b.behind ? '<button class="btn sm" data-sync>main 가져오기</button>' : ''}<a class="btn sm ghost" href="#/compare/${ref}">main과 다른 부분</a><button class="btn sm plain" data-main>main 보기</button></span></div>`;
}
function bindBanner(root) {
  const b = $('[data-main]', root); if (b) b.addEventListener('click', () => setRef('main'));
  const s = $('[data-sync]', root); if (s) s.addEventListener('click', syncFromMain);
}
// main 가져오기: main에 새로 합쳐진 커밋을 내 브랜치로 받는다. 내 브랜치에만 있는 커밋이 없으면 빨리 감기, 있으면 합치기 커밋을 만든다.
async function syncFromMain() {
  if (!needLogin()) return;
  const btns = $$('#syncBtn, [data-sync]'); btns.forEach((b) => { b.disabled = true; });
  try {
    const r = await api('/sync', { body: {} });
    bump(); await loadBranches();
    toast(r.sync === 'uptodate' ? '이미 main의 최신 내용이 들어 있어요.' : `main의 커밋 ${r.commits}개를 ${S.me} 브랜치로 가져왔어요.`, 4000);
    route();
  } catch (x) {
    if (x.error === 'MAIN_CONFLICT') conflictModal(x); else toast(x.message);
  } finally { btns.forEach((b) => { b.disabled = false; }); }
}

async function docView(root, path, { title, sub, compact, withToc } = {}) {
  const ref = S.ref;
  root.innerHTML = '<p class="loading">불러오는 중…</p>';
  const [file, base] = await Promise.all([fetchFile(path, ref), ref !== 'main' ? fetchFile(path, 'main') : null]);
  if (!file) { root.innerHTML = `<p class="empty">${esc(ref)} 브랜치에 <code>${esc(path)}</code> 파일이 없어요.</p>`; return; }
  const secs = splitSections(file.content);
  const baseMap = new Map(); if (base) splitSections(base.content).forEach((s) => baseMap.set(headOf(s), s.text));
  const canEdit = !!S.me && (ref === 'main' || ref === S.me);
  const gh = `https://github.com/brainsaver/jejari/blob/${encodeURIComponent(ref)}/${path.split('/').map(encodeURIComponent).join('/')}`;
  root.innerHTML = `${compact ? '' : `<h1 class="page">${esc(title)}</h1><p class="page-sub">${esc(sub || '')} · <code>${esc(path)}</code> · <a href="${gh}" target="_blank" rel="noopener">GitHub</a> · <button class="linkish" data-copyall title="문서 전체를 ChatGPT·Claude 등에 붙여 넣기 좋은 형태로 복사">LLM용 전체 복사</button></p>`}
    ${branchBanner(ref)}
    
    ${S.me && ref !== 'main' && ref !== S.me ? `<div class="banner">다른 사람 브랜치는 볼 수만 있어요. 고치려면 main이나 ${esc(S.me)} 브랜치로 바꿔 주세요.</div>` : ''}
    <div class="${compact && !withToc ? '' : 'doc-grid'}"><div class="doc-body"></div>${compact && !withToc ? '' : '<nav class="toc"></nav>'}</div>`;
  bindBanner(root);
  const meta = { path, title: title || path.split('/').pop() };
  const all = $('[data-copyall]', root);
  if (all) all.addEventListener('click', () => copyText(llmBlock(meta, null, file.content), '문서 전체를 LLM용으로 복사했어요.'));
  const body = $('.doc-body', root), toc = $('.toc', root);
  body._meta = meta;
  secs.forEach((s, i) => {
    const el = document.createElement('section'); el.className = 'sec'; el.dataset.i = i;
    const differs = base && baseMap.get(headOf(s)) !== s.text;
    if (differs) el.classList.add('diff');
    const tools = document.createElement('div'); tools.className = 'sec-tools';
    if (differs) tools.innerHTML += '<span class="badge warn">main과 다름</span>';
    tools.innerHTML += '<button class="btn sm plain" data-copysec title="이 섹션을 LLM에 붙여 넣기 좋은 형태로 복사">LLM 복사</button>';
    if (canEdit) tools.innerHTML += '<button class="btn sm plain" data-edit>수정</button>';
    el._md = s.text; el._head = headOf(s);
    el.appendChild(tools); el.appendChild(renderMd(s.text));
    $('[data-copysec]', tools).addEventListener('click', () => copyText(llmBlock(meta, el._head, el._md), '이 섹션을 LLM용으로 복사했어요.'));
    body.appendChild(el);
    const ed = $('[data-edit]', tools); if (ed) ed.addEventListener('click', () => editSection(el, path, s, secs, i));
  });
  if (canEdit) {
    const add = document.createElement('button'); add.className = 'btn sm ghost'; add.textContent = '+ 맨 아래에 섹션 추가'; add.style.marginTop = '12px';
    add.addEventListener('click', () => { const last = secs[secs.length - 1]; const el = body.lastElementChild.previousElementSibling || body.lastElementChild; editSection($$('.sec', body).pop(), path, last, secs, secs.length - 1, true); });
    body.appendChild(add);
  }
  if (toc) toc.innerHTML = $$('h2,h3,details > summary', body).map((h) => `<a class="${h.tagName === 'H3' ? 'l3' : ''}" href="#${esc(h.id.slice(2))}">${esc(h.textContent)}</a>`).join('');
  if (toc) $$('a', toc).forEach((a) => a.addEventListener('click', (e) => { e.preventDefault(); const t = document.getElementById('h-' + a.getAttribute('href').slice(1)); if (t) t.scrollIntoView({ behavior: 'smooth' }); }));
  applyJump(body, path);
}

async function editSection(el, path, shown, shownSecs, idx, appendNew) {
  if (!needLogin()) return;
  el.innerHTML = '<p class="loading">내 브랜치 최신 내용을 여는 중…</p>';
  let prep;
  try { prep = await api('/prepare', { body: { path } }); }
  catch (x) { if (x.error === 'MAIN_CONFLICT') conflictModal(x); else toast(x.message); route(); return; }
  if (!prep.file) { toast('내 브랜치에 이 파일이 없어요.'); route(); return; }
  const mine = splitSections(prep.file.content);
  const key = headOf(shown);
  const hit = mine.find((s) => headOf(s) === key) || (appendNew ? mine[mine.length - 1] : null);
  if (!hit) { toast('내 브랜치에는 이 섹션이 없어요. 내 브랜치를 보고 고쳐 주세요.'); setRef(S.me); return; }
  const notice = hit.text !== shown.text ? `<span class="badge warn">내 브랜치 내용으로 열었어요</span>` : '';
  const startText = appendNew ? hit.text.replace(/\n*$/, '\n\n') + '## 새 섹션\n\n내용을 쓰세요.\n' : hit.text;
  el.innerHTML = `<div class="editor"><div class="editor-bar"><b>${esc(key.replace(/^#+\s*/, ''))}</b> ${notice}<span class="grow"></span>
    <span class="muted">${prep.sync === 'merged' ? 'main 최신 내용을 합쳤어요 · ' : ''}${esc(S.me)} 브랜치</span>
    <button class="btn sm plain" data-cancel>취소</button><button class="btn sm" data-save>저장</button></div>
    <div class="editor-body"><textarea class="code" spellcheck="false"></textarea><div class="preview"></div></div></div>
    <div class="keep"></div>`;
  const ta = $('textarea', el), pv = $('.preview', el);
  ta.value = startText;
  const draw = () => { pv.innerHTML = ''; pv.appendChild(renderMd(ta.value)); };
  let t; ta.addEventListener('input', () => { clearTimeout(t); t = setTimeout(draw, 150); }); draw();
  ta.focus();
  $('[data-cancel]', el).addEventListener('click', () => route());
  $('[data-save]', el).addEventListener('click', () => {
    const newText = ta.value.endsWith('\n') || !hit.text.endsWith('\n') ? ta.value : ta.value + '\n';
    if (newText === hit.text) { toast('바뀐 내용이 없어요.'); return; }
    commitFlow({
      path, heading: key, before: hit.text, after: newText,
      run: async (message) => {
        try {
          await api('/save', { body: { path, mode: 'section', oldText: hit.text, newText, message, heading: key } });
          afterSave();
        } catch (x) {
          if (x.error === 'MAIN_CONFLICT') { conflictModal(x); return; }
          if (x.error === 'STALE') { staleModal(x, newText, path, key); return; }
          throw x;
        }
      },
    });
  });
}
function staleModal(x, myText, path, key) {
  const latest = x.current ? (splitSections(x.current).find((s) => headOf(s) === key) || { text: '' }).text : '';
  const m = openModal(`<h2>그 사이 바뀌었어요</h2><p>${esc(x.message)}</p>
    <p class="muted">아래는 최신 내용(−)과 내가 쓴 내용(+)의 차이예요. 내 글은 복사해 두고 최신 내용으로 다시 열어 고쳐 주세요.</p>
    <div class="diff-file"><div class="diff-body">${renderPatch(lineDiff(latest, myText))}</div></div>
    <div class="field"><label>내가 쓴 내용</label><textarea class="code" rows="8" style="width:100%" readonly></textarea></div>
    <div class="row"><button class="btn plain" data-x>닫기</button><button class="btn" data-reopen>최신으로 다시 보기</button></div>`, { wide: true });
  $('textarea', m).value = myText;
  $('[data-x]', m).addEventListener('click', closeModal);
  $('[data-reopen]', m).addEventListener('click', () => { closeModal(); bump(); setRef(S.me); });
}

// 프로젝트 전체 문서 묶음 (main 기준)
async function projectBundle() {
  const docs = [['README.md', '기획서'], ['docs/기능설계서.md', '기능설계'], ['docs/concurrency.md', '동시성 절차'], ['docs/DB설계서.md', 'DB설계']];
  const files = await Promise.all(docs.map(([p]) => fetchFile(p, 'main').catch(() => null)));
  const list = await uiIndex().catch(() => []);
  const ui = await Promise.all(list.map((x) => fetchFile(`docs/ui/${x.id}.md`, 'main').catch(() => null)));
  const out = ['아래는 "제자리" 팀 프로젝트(명절 열차 공정 예매 시스템, 5명·3주, Spring Boot·Kafka·Redis·MongoDB·Oracle·Python)의 설계 문서 전체입니다.',
    '스키마의 원천은 ERD 편집기, 트랜잭션 순서·SQL·Redis 절차의 원천은 동시성 문서(§번호)입니다. 1차 구현 범위는 기능설계 1.1을 따릅니다.',
    '이 문서들을 모두 읽은 상태로 답해 주세요.', ''];
  files.forEach((f, i) => { if (f) out.push(`===== ${docs[i][1]} (${docs[i][0]}) =====`, f.content.trim(), ''); });
  const uiParts = ui.filter(Boolean).map((f) => f.content.trim());
  if (uiParts.length) out.push('===== 화면설계 설명 (docs/ui/*.md) =====', uiParts.join('\n\n---\n\n'), '');
  out.push('질문: ');
  return out.join('\n');
}

// ---------- 페이지 ----------
function pageHomeAnon() {
  main.innerHTML = `<div class="wrap"><h1 class="page">제자리 설계 문서</h1>
    <p class="page-sub">명절 열차 공정 예매 시스템의 설계 문서예요.${S.me ? '' : ' 팀원은 오른쪽 위에서 로그인하면 문서를 고칠 수 있어요.'}</p>
    <div class="cards">
      <a class="card" href="#/roadmap"><h3>로드맵</h3><p>기능 영역별 보드, A~E 파트별 했다·안 했다, 개발자에게 남길 말</p></a>
      <a class="card" href="#/doc/plan"><h3>기획서</h3><p>프로젝트 목표, 팀 역할, 일정, 기술 선택 이유</p></a>
      <a class="card" href="#/doc/func"><h3>기능설계</h3><p>기능 목록, 상세, 상태 전이, API, Kafka, 에러 코드</p></a>
      <a class="card" href="#/db"><h3>DB설계 · ERD</h3><p>Oracle·MongoDB·Redis·Kafka 역할, 테이블 정의, ERD</p></a>
      <a class="card" href="#/ui"><h3>화면설계</h3><p>사용자 11개, 관리자 7개 화면을 눌러 보는 시안과 설명</p></a>
      <a class="card" href="#/doc/conc"><h3>동시성</h3><p>트랜잭션 순서, SQL, Redis Lua, 락 순서, 테스트 목록</p></a>
      <a class="card" href="#/people"><h3>팀원 문서</h3><p>팀원별 개발일지, 기능 문서, 트러블슈팅, 기여 정리</p></a>
    </div>
    <div class="llm-all">
      <div><h3>프로젝트 전체를 LLM에 주기</h3>
      <p class="muted">기획서·기능설계·동시성·DB설계·화면설계 설명을 한 덩어리로 복사해요. Claude·ChatGPT처럼 긴 글을 받는 곳에 붙여 넣고 질문하면 프로젝트 전체를 아는 상태로 답해요. 문서마다 위쪽의 "LLM용 전체 복사", 섹션마다 "LLM 복사", 글을 드래그하면 "AI에게 물어보기"도 있어요.</p></div>
      <button class="btn" data-copyproject>프로젝트 전체 LLM용 복사</button>
    </div></div>`;
  $('[data-copyproject]', main).addEventListener('click', () => copyLater(projectBundle, '프로젝트 문서 전체를 LLM용으로 복사했어요.'));
}
function pageLocked() {
  main.innerHTML = `<div class="wrap"><h1 class="page">로그인이 필요해요</h1>
    <p class="page-sub">이 페이지는 팀원만 볼 수 있어요. 로그인하지 않으면 기획서·기능설계·DB설계·화면설계·동시성 문서만 보여요.</p>
    <p><button class="btn" id="lockLogin">로그인</button> <a class="btn plain" href="#/">처음으로</a></p></div>`;
  $('#lockLogin').addEventListener('click', () => loginModal(() => route()));
}
function pageHome() { pageHomeAnon(); }

function commitTable(list) {
  if (!list.length) return '<p class="empty">기록이 없어요.</p>';
  return `<div class="tbl-wrap"><table class="list-tbl"><tr><th>시각</th><th>이름</th><th>내용</th><th>브랜치</th></tr>
    ${list.map((c) => `<tr class="click" data-sha="${c.sha}"><td class="muted" style="white-space:nowrap">${kst(c.date)}</td><td>${nameTag(c.author)}</td>
    <td>${esc(firstLine(c.message))}</td><td>${(c.refs || []).filter((r) => r !== 'main' || c.refs.length === 1).slice(0, 3).map(refTag).join('')}</td></tr>`).join('')}</table></div>`;
}
function bindCommitRows(root) { $$('tr[data-sha]', root).forEach((r) => r.addEventListener('click', () => { location.hash = '#/commit/' + r.dataset.sha; })); }

async function pageDoc(key) {
  const d = DOCS[key];
  main.innerHTML = '<div class="wide"><div id="doc"></div></div>';
  await docView($('#doc'), d.path, d);
}

async function pageDb(tab) {
  tab = tab || 'doc';
  if (!S.me && tab === 'exp') return pageLocked();
  main.innerHTML = `<div class="wide"><h1 class="page">DB설계 · ERD</h1>
    <p class="page-sub">저장소별 역할과 테이블 정의는 DB설계서, 테이블 관계 그림과 스키마 원본은 ERD예요.</p>
    <div class="tabs"><button data-t="doc">DB설계서</button><button data-t="erd">ERD</button>${S.me ? '<button data-t="exp">ERD 수정 내보내기</button>' : ''}</div>
    <div id="tab"></div></div>`;
  $$('.tabs button', main).forEach((b) => { b.classList.toggle('on', b.dataset.t === tab); b.addEventListener('click', () => { location.hash = '#/db/' + b.dataset.t; }); });
  const root = $('#tab');
  if (tab === 'doc') return docView(root, DOCS.db.path, { ...DOCS.db, compact: true, withToc: true });
  if (tab === 'erd') return erdTab(root);
  return exportsTab(root);
}
async function erdTab(root) {
  root.innerHTML = `${branchBanner(S.ref)}${S.me ? `<div class="banner info" style="flex-wrap:wrap">ERD 편집기에서 고친 뒤
    <b>내 수정 저장</b>을 누르면 <code>docs/erd/exports/${esc(S.me)}-날짜.json</code>으로 내 브랜치에 커밋돼요. 스키마(HTML) 반영은 강동균이 패치로 해요.
    <button class="btn sm" id="erdSave">내 수정 저장</button></div>` : '<div class="banner info">테이블을 누르면 컬럼·설명이 나오고, 위쪽 "문서"에서 공통 규칙·흐름을 볼 수 있어요. 여기서 고친 내용은 내 브라우저에만 남아요.</div>'}
    <div class="erd-tools"><span class="muted">칸이 좁으면 전체화면으로 크게 보세요.</span><button class="btn sm plain" id="erdFs">⛶ 전체화면</button></div>
    <div class="erd-wrap"><iframe class="erd-frame" title="ERD 편집기"></iframe><button class="fs-close" type="button">닫기 (Esc)</button></div>`;
  bindBanner(root);
  const f = await fetchFile(ERD_PATH, S.ref);
  const frame = $('iframe', root);
  if (!f) { frame.replaceWith(Object.assign(document.createElement('p'), { className: 'empty', textContent: 'ERD 편집기 파일이 없어요.' })); return; }
  frame.srcdoc = f.content;
  // 전체화면: 칸을 화면 가득 키우고, 지원하는 브라우저면 진짜 전체화면도 켠다. Esc는 편집기 안에서 눌러도 닫힌다.
  const wrap = $('.erd-wrap', root);
  const fsOff = () => { wrap.classList.remove('fs'); document.body.classList.remove('no-scroll'); if (document.fullscreenElement) document.exitFullscreen().catch(() => {}); };
  $('#erdFs', root).addEventListener('click', () => { wrap.classList.add('fs'); document.body.classList.add('no-scroll'); if (wrap.requestFullscreen) wrap.requestFullscreen().catch(() => {}); });
  $('.fs-close', wrap).addEventListener('click', fsOff);
  wrap.addEventListener('fullscreenchange', () => { if (!document.fullscreenElement) { wrap.classList.remove('fs'); document.body.classList.remove('no-scroll'); } });
  const onEsc = (e) => { if (e.key === 'Escape' && wrap.classList.contains('fs')) fsOff(); };
  document.addEventListener('keydown', function esc(e) { if (!wrap.isConnected) { document.removeEventListener('keydown', esc); return; } onEsc(e); });
  frame.addEventListener('load', () => { try { frame.contentWindow.document.addEventListener('keydown', onEsc); } catch {} });
  if ($('#erdSave', root)) $('#erdSave', root).addEventListener('click', async () => {
    if (!needLogin()) return;
    let content;
    try { content = frame.contentWindow.exportJSON(); } catch { toast('ERD 편집기에서 내보내기를 읽지 못했어요.'); return; }
    const list = await api('/list' + qs({ dir: 'docs/erd/exports', ref: S.me })).catch(() => []);
    const prev = list.filter((x) => x.name.startsWith(S.me + '-')).map((x) => x.path).sort().pop();
    const before = prev ? ((await fetchFile(prev, S.me)) || {}).content || '' : '';
    commitFlow({
      title: 'ERD 수정 저장', path: `docs/erd/exports/${S.me}-00000000.json`, heading: 'ERD 편집기 내보내기', before, after: content,
      run: async (message) => {
        const r = await api('/erd-export', { body: { content, message } }).catch((x) => { if (x.error === 'MAIN_CONFLICT') { conflictModal(x); return null; } throw x; });
        if (r) afterSave(`${r.path}로 저장했어요. 강동균에게 반영을 요청하세요.`);
      },
    });
  });
}
async function exportsTab(root) {
  root.innerHTML = '<p class="loading">불러오는 중…</p>';
  const lists = await Promise.all(['main', ...Object.keys(S.members)].map((r) => api('/list' + qs({ dir: 'docs/erd/exports', ref: r })).then((l) => l.map((x) => ({ ...x, ref: r }))).catch(() => [])));
  const seen = new Map();
  lists.flat().forEach((x) => { const k = x.path + x.sha; if (!seen.has(k)) seen.set(k, x); else seen.get(k).ref += ', ' + x.ref; });
  const rows = [...seen.values()].filter((x) => x.name.endsWith('.json')).sort((a, b) => (a.name < b.name ? 1 : -1));
  root.innerHTML = rows.length ? `<p class="muted">팀원이 ERD 편집기에서 저장한 내보내기 파일이에요. 강동균이 <code>tools/erd-diff.js</code>로 패치를 만들어 반영해요.</p>
    <div class="tbl-wrap"><table class="list-tbl"><tr><th>파일</th><th>있는 브랜치</th><th></th></tr>${rows.map((x) => `<tr><td><code>${esc(x.name)}</code></td><td>${x.ref.split(', ').map(refTag).join('')}</td>
    <td style="text-align:right"><a target="_blank" rel="noopener" href="https://github.com/brainsaver/jejari/blob/${encodeURIComponent(x.ref.split(', ')[0])}/${x.path}">GitHub</a></td></tr>`).join('')}</table></div>`
    : '<p class="empty">아직 내보낸 파일이 없어요.</p>';
}

// 화면설계
let uiIndexCache = null;
async function uiIndex() {
  if (uiIndexCache && uiIndexCache.ref === S.ref && !fresh.n) return uiIndexCache.list;
  const f = await fetchFile('docs/ui/index.json', S.ref) || await fetchFile('docs/ui/index.json', 'main');
  const list = f ? JSON.parse(f.content) : [];
  uiIndexCache = { ref: S.ref, list };
  return list;
}
async function pageUi() {
  main.innerHTML = '<div class="wide"><p class="loading">불러오는 중…</p></div>';
  const list = await uiIndex();
  const group = (k, t) => `<div class="section-title">${t}</div><div class="ui-grid">${list.filter((x) => x.kind === k).map((x) => `<a class="card ui-card" href="#/ui/${x.id}">
    <div class="id">${x.id}</div><h3>${esc(x.name)}</h3><p>${esc(x.summary || '')}</p><p style="margin-top:6px">${esc((x.features || []).join(', '))} · ${esc(x.owner || '')}</p></a>`).join('')}</div>`;
  main.innerHTML = `<div class="wide"><h1 class="page">화면설계</h1>
    <p class="page-sub">화면마다 설명 문서(구성·동작·연결 API)와 눌러 볼 수 있는 HTML 시안이 있어요. 시안 안의 버튼을 누르면 다음 화면으로 넘어가요.</p>
    ${branchBanner(S.ref)}${group('user', '사용자 화면')}${group('admin', '관리자 화면')}</div>`;
  bindBanner(main);
}
function mockDoc(html) {
  // 시안 안에서 링크를 누르면 부모 사이트가 다음 화면으로 바꾼다. '누를 수 있는 곳 표시'를 켜면 링크에 테두리를 그린다.
  const inject = `<style>.jj-hint a[href]{outline:2px solid rgba(37,99,235,.85)!important;outline-offset:2px;background-color:rgba(37,99,235,.12)!important;transition:outline-color .2s}</style><script>(function(){function hint(on){document.documentElement.classList.toggle('jj-hint',on);}
document.addEventListener('click',function(e){var a=e.target.closest('a[href]');if(!a)return;var h=a.getAttribute('href');var m=h.match(/^([UA]-\\d{2})\\.html/);e.preventDefault();if(m)parent.postMessage({jejariGo:m[1]},'*');});
document.addEventListener('submit',function(e){e.preventDefault();});
document.addEventListener('keydown',function(e){if(e.key==='Escape')parent.postMessage({jejariEsc:1},'*');});
window.addEventListener('message',function(e){if(e.data&&e.data.jejariHint!==undefined)hint(!!e.data.jejariHint);});})();<\/script>`;
  return /<\/body>/i.test(html) ? html.replace(/<\/body>/i, inject + '</body>') : html + inject;
}
window.addEventListener('message', (e) => {
  if (e.data && e.data.jejariEsc) { const d = $('.device.fs'); if (d) $('.fs-close', d).click(); return; }
  if (!e.data || typeof e.data.jejariGo !== 'string' || !/^[UA]-\d{2}$/.test(e.data.jejariGo)) return;
  location.hash = '#/ui/' + e.data.jejariGo;
});

let protoHint = false;
const PC_W = 1280;
let protoSide = store.get('proto-side') !== 'off';
// PC 시안은 실제 PC 폭(1280px)으로 그리고, 보이는 칸에 맞게 줄인다. 칸이 더 넓으면(전체화면 등) 그대로 칸 폭에 맞춘다.
function protoFit(root) {
  const scr = $('.screen', root), fr = $('iframe', scr);
  if (!root.classList.contains('pc')) return;
  const fit = () => {
    const w = scr.clientWidth, h = scr.clientHeight; if (!w || !h) return;
    const k = Math.min(1, w / PC_W), fw = k < 1 ? PC_W : w;
    fr.style.width = fw + 'px'; fr.style.height = (h / k) + 'px'; fr.style.transform = k < 1 ? `scale(${k})` : '';
  };
  new ResizeObserver(fit).observe(scr); fit();
}
const protoDev = { user: store.get('dev-user') || 'mobile', admin: store.get('dev-admin') || 'pc' };
async function pageUiOne(id) {
  const list = await uiIndex();
  const i = list.findIndex((x) => x.id === id); const it = list[i] || { id, name: id, kind: id[0] === 'A' ? 'admin' : 'user' };
  const prev = list[i - 1], next = list[i + 1];
  const admin = it.kind === 'admin';
  // 같은 종류(사용자/관리자) 화면 사이를 오가면 틀은 그대로 두고 시안·설명만 바꾼다 (실제 사이트처럼 매끄럽게)
  let root = $('.proto', main);
  const dev = protoDev[it.kind];
  if (!root || root.dataset.kind !== it.kind || root.dataset.ref !== S.ref || root.dataset.dev !== dev) {
    main.innerHTML = `<div class="proto ${dev}${protoSide ? '' : ' no-side'}" data-kind="${it.kind}" data-ref="${esc(S.ref)}" data-dev="${dev}">
      <div class="proto-stage">
        <div class="proto-bar">
          <a class="btn sm plain" href="#/ui">← 화면 목록</a>
          <select class="proto-pick" aria-label="화면 고르기">${['user', 'admin'].map((k) => `<optgroup label="${k === 'user' ? '사용자 화면' : '관리자 화면'}">${list.filter((x) => x.kind === k).map((x) => `<option value="${x.id}">${x.id} ${esc(x.name)}</option>`).join('')}</optgroup>`).join('')}</select>
          <span class="seg" role="group" aria-label="화면 크기"><button data-dev="pc"${dev === 'pc' ? ' class="on"' : ''}>PC</button><button data-dev="mobile"${dev === 'mobile' ? ' class="on"' : ''}>모바일</button></span>
          <span class="grow"></span>
          <button class="btn sm plain" id="sideBtn">${protoSide ? '설명 숨기기' : '설명 보기'}</button>
          <button class="btn sm plain" id="fsBtn" title="전체화면 (Esc로 닫기)">⛶ 전체화면</button>
          <label class="proto-hint"><input type="checkbox"${protoHint ? ' checked' : ''}> 누를 수 있는 곳 표시</label>
        </div>
        <div class="device"><div class="device-top">${dev === 'pc' ? '<i></i><i></i><i></i><span class="url"></span>' : '<span class="notch"></span>'}</div>
          <div class="screen"><iframe sandbox="allow-scripts" title="화면 시안"></iframe></div>
          <button class="fs-close" type="button">닫기 (Esc)</button></div>
        <p class="proto-tip muted">시안 안의 버튼·링크를 누르면 실제 사이트처럼 다음 화면으로 넘어가요.${dev === 'pc' ? ' PC 화면은 폭 1280px 기준으로 그린 뒤 칸에 맞게 줄여 보여 줘요. 크게 보려면 설명 숨기기나 전체화면을 쓰세요.' : ''}</p>
      </div>
      <aside class="proto-side"><div class="proto-head"></div><div id="uiDoc"></div></aside></div>`;
    root = $('.proto', main);
    $$('.seg button', root).forEach((b) => b.addEventListener('click', () => { protoDev[it.kind] = b.dataset.dev; store.set('dev-' + it.kind, b.dataset.dev); route(); }));
    $('.proto-pick', root).addEventListener('change', (e) => { location.hash = '#/ui/' + e.target.value; });
    $('.proto-hint input', root).addEventListener('change', (e) => { protoHint = e.target.checked; $('.device iframe', root).contentWindow.postMessage({ jejariHint: protoHint }, '*'); });
    $('.device iframe', root).addEventListener('load', (e) => { if (protoHint) e.target.contentWindow.postMessage({ jejariHint: true }, '*'); });
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    protoFit(root);
    const device = $('.device', root);
    const fsOff = () => { device.classList.remove('fs'); document.body.classList.remove('no-scroll'); if (document.fullscreenElement) document.exitFullscreen().catch(() => {}); };
    $('#sideBtn', root).addEventListener('click', (e) => {
      protoSide = !protoSide; store.set('proto-side', protoSide ? 'on' : 'off');
      root.classList.toggle('no-side', !protoSide); e.target.textContent = protoSide ? '설명 숨기기' : '설명 보기';
    });
    $('#fsBtn', root).addEventListener('click', () => {
      device.classList.add('fs'); document.body.classList.add('no-scroll');
      if (device.requestFullscreen) device.requestFullscreen().catch(() => {});
    });
    $('.fs-close', device).addEventListener('click', fsOff);
    device.addEventListener('fullscreenchange', () => { if (!document.fullscreenElement) { device.classList.remove('fs'); document.body.classList.remove('no-scroll'); } });
    document.addEventListener('keydown', function esc(e) { if (!device.isConnected) { document.removeEventListener('keydown', esc); return; } if (e.key === 'Escape' && device.classList.contains('fs')) fsOff(); });
  }
  $('.proto-pick', root).value = id;
  const url = $('.device .url', root); if (url) url.textContent = `${admin ? 'admin.' : ''}jejari.dev/${id}`;
  const canEdit = S.me && (S.ref === 'main' || S.ref === S.me);
  $('.proto-head', root).innerHTML = `${branchBanner(S.ref)}
    <div class="proto-id">${esc(it.id)} · ${admin ? '관리자 화면' : '사용자 화면'}</div>
    <h1>${esc(it.name)}</h1>
    ${it.summary ? `<p class="proto-sum">${esc(it.summary)}</p>` : ''}
    <dl class="proto-meta">${(it.features || []).length ? `<dt>기능</dt><dd>${it.features.map((f) => `<code>${esc(f)}</code>`).join(' ')}</dd>` : ''}${it.owner ? `<dt>담당</dt><dd>${esc(it.owner)}</dd>` : ''}</dl>
    <div class="proto-nav">${prev ? `<a class="btn sm plain" href="#/ui/${prev.id}">← ${prev.id}</a>` : '<span></span>'}
      ${canEdit ? '<button class="btn sm plain" id="mockEdit">시안 수정</button>' : ''}
      ${next ? `<a class="btn sm plain" href="#/ui/${next.id}">${next.id} →</a>` : '<span></span>'}</div>`;
  bindBanner(root);
  const eb = $('#mockEdit'); if (eb) eb.addEventListener('click', () => editMock(id));
  const frame = $('.device iframe', root);
  const side = $('.proto-side', root); side.scrollTop = 0;
  $('#uiDoc').innerHTML = '<p class="loading">불러오는 중…</p>';
  const [html] = await Promise.all([fetchFile(`docs/ui/${id}.html`, S.ref), docView($('#uiDoc'), `docs/ui/${id}.md`, { compact: true })]);
  if (html) frame.srcdoc = mockDoc(html.content);
  else frame.srcdoc = '<p style="font-family:sans-serif;color:#888;padding:24px">시안 파일이 없어요.</p>';
}
async function editMock(id) {
  if (!needLogin()) return;
  const path = `docs/ui/${id}.html`;
  let prep;
  try { prep = await api('/prepare', { body: { path } }); } catch (x) { if (x.error === 'MAIN_CONFLICT') conflictModal(x); else toast(x.message); return; }
  const old = prep.file ? prep.file.content : null;
  main.innerHTML = `<div class="wide"><p style="margin:0 0 6px"><a href="#/ui/${id}">${id}</a> › 시안 수정</p>
    <div class="editor tall"><div class="editor-bar"><b>${esc(path)}</b><span class="grow"></span><span class="muted">${esc(S.me)} 브랜치</span>
    <button class="btn sm plain" data-cancel>취소</button><button class="btn sm" data-save>저장</button></div>
    <div class="editor-body"><textarea class="code" spellcheck="false"></textarea><iframe class="preview" sandbox="allow-scripts" title="미리보기"></iframe></div></div></div>`;
  const ta = $('textarea', main), pv = $('iframe', main);
  ta.value = old || '<!doctype html>\n<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>\n<body>\n</body></html>\n';
  const draw = () => { pv.srcdoc = mockDoc(ta.value); };
  let t; ta.addEventListener('input', () => { clearTimeout(t); t = setTimeout(draw, 300); }); draw();
  ta.addEventListener('keydown', (e) => { if (e.key === 'Tab') { e.preventDefault(); const s = ta.selectionStart; ta.setRangeText('  ', s, ta.selectionEnd, 'end'); } });
  $('[data-cancel]', main).addEventListener('click', () => route());
  $('[data-save]', main).addEventListener('click', () => {
    if (ta.value === old) { toast('바뀐 내용이 없어요.'); return; }
    commitFlow({
      path, heading: `${id} 시안`, before: old || '', after: ta.value,
      run: async (message) => {
        try { await api('/save', { body: { path, mode: 'file', oldText: old, newText: ta.value, message } }); afterSave(); location.hash = '#/ui/' + id; }
        catch (x) {
          if (x.error === 'MAIN_CONFLICT') { conflictModal(x); return; }
          if (x.error === 'STALE') { staleModal({ ...x, current: x.current }, ta.value, path, ''); return; }
          throw x;
        }
      },
    });
  });
}

// 수정 기록
async function pageHistory() {
  const f = store.get('histFilter', { who: '', path: '', merges: false });
  main.innerHTML = `<div class="wide"><h1 class="page">수정 기록</h1>
    <p class="page-sub">main과 이름 브랜치 5개의 커밋을 모았어요. 누르면 바뀐 줄을 볼 수 있고, 내 수정은 되돌릴 수 있어요.</p>
    <div class="filters">
      <select id="fWho"><option value="">모든 사람</option>${Object.keys(S.members).map((n) => `<option value="${n}">${n} ${esc(S.members[n])}</option>`).join('')}<option value="_other">그 밖 (BrainSaver 등)</option></select>
      <select id="fPath">${DOC_FILTERS.map(([v, l]) => `<option value="${v}">${l}</option>`).join('')}</select>
      <label><input type="checkbox" id="fMerge"> 합치기 커밋도 보기</label>
    </div><div id="hist" class="loading">불러오는 중…</div></div>`;
  $('#fWho').value = f.who; $('#fPath').value = f.path; $('#fMerge').checked = f.merges;
  const load = async () => {
    const nf = { who: $('#fWho').value, path: $('#fPath').value, merges: $('#fMerge').checked }; store.set('histFilter', nf);
    $('#hist').innerHTML = '<p class="loading">불러오는 중…</p>';
    try {
      let list = await api('/commits' + qs(nf.path ? { path: nf.path } : {}));
      if (!nf.merges) list = list.filter((c) => !isMerge(c));
      if (nf.who === '_other') list = list.filter((c) => !S.members[c.author]);
      else if (nf.who) list = list.filter((c) => c.author === nf.who);
      $('#hist').innerHTML = commitTable(list); bindCommitRows($('#hist'));
    } catch (x) { $('#hist').innerHTML = `<p class="empty">${esc(x.message)}</p>`; }
  };
  ['#fWho', '#fPath', '#fMerge'].forEach((s) => $(s).addEventListener('change', load));
  load();
}

async function pageCommit(sha) {
  main.innerHTML = '<div class="wide"><p class="loading">불러오는 중…</p></div>';
  let c;
  try { c = await api('/commit' + qs({ sha })); } catch (x) { main.innerHTML = `<div class="wide"><p class="empty">${esc(x.message)}</p></div>`; return; }
  const mine = S.me && c.author === S.me && c.parents.length === 1;
  const [title, ...rest] = c.message.split('\n');
  main.innerHTML = `<div class="wide"><p style="margin:0 0 6px"><a href="#/history">수정 기록</a> ›</p>
    <h1 class="page" style="font-size:22px">${esc(title)}</h1>
    <p class="page-sub">${nameTag(c.author)} · ${kst(c.date, true)} · <code>${c.sha.slice(0, 7)}</code> · <a href="${esc(c.url)}" target="_blank" rel="noopener">GitHub</a>
    ${mine ? ' · <button class="btn sm danger" id="revBtn">이 수정 되돌리기</button>' : ''}</p>
    ${rest.join('\n').trim() ? `<pre class="md" style="white-space:pre-wrap">${esc(rest.join('\n').trim())}</pre>` : ''}
    ${renderFiles(c.files)}</div>`;
  const rb = $('#revBtn');
  if (rb) twoStep(rb, '한 번 더 누르면 되돌리기', () => doRevert(sha, false));
}
async function doRevert(sha, force) {
  try {
    const r = await api('/revert', { body: { sha, force } });
    bump(); loadBranches(); toast(`${S.me} 브랜치에 되돌림 커밋을 만들었어요.`); location.hash = '#/commit/' + r.commit;
  } catch (x) {
    if (x.error === 'MAIN_CONFLICT') return conflictModal(x);
    if (x.error === 'LATER_CHANGES') {
      const m = openModal(`<h2>뒤에 또 바뀐 파일</h2><p>${esc(x.message)}</p><ul>${x.files.map((f) => `<li><code>${esc(f)}</code></li>`).join('')}</ul>
        <div class="row"><button class="btn plain" data-x>그만두기</button><button class="btn danger" data-go>그래도 되돌리기</button></div>`);
      $('[data-x]', m).addEventListener('click', closeModal);
      twoStep($('[data-go]', m), '한 번 더 누르면 되돌리기', async () => { closeModal(); await doRevert(sha, true); });
      return;
    }
    toast(x.message);
  }
}

async function pageCompare(branch) {
  main.innerHTML = '<div class="wide"><p class="loading">불러오는 중…</p></div>';
  let c;
  try { c = await api('/compare' + qs({ branch })); } catch (x) { main.innerHTML = `<div class="wide"><p class="empty">${esc(x.message)}</p></div>`; return; }
  main.innerHTML = `<div class="wide"><h1 class="page">${esc(branch)}와 main 비교</h1>
    <p class="page-sub">${nameTag(branch)} 브랜치가 main보다 ${c.ahead}개 앞, ${c.behind}개 뒤예요. 아래는 main에 합치면 들어갈 내용이에요.</p>
    ${S.me === branch && c.ahead ? '<p><button class="btn" id="cmpMerge">main에 합치기</button></p>' : ''}
    <div class="section-title">main에 없는 커밋</div>${commitTable(c.commits.map((x) => ({ ...x, refs: [branch] })))}
    <div class="section-title">바뀐 파일</div>${renderFiles(c.files)}</div>`;
  bindCommitRows(main);
  const mb = $('#cmpMerge'); if (mb) mb.addEventListener('click', mergeModal);
}

async function mergeModal() {
  if (!needLogin()) return;
  const m = openModal(`<h2>main에 합치기</h2><p class="loading">바뀐 내용을 확인하는 중…</p>`);
  let c;
  try { c = await api('/compare' + qs({ branch: S.me })); } catch (x) { m.innerHTML = `<h2>main에 합치기</h2><p class="err">${esc(x.message)}</p>`; return; }
  m.innerHTML = `<h2>main에 합치기</h2>
    <p>${esc(S.me)} 브랜치의 커밋 ${c.ahead}개를 main에 합쳐요. 커밋은 이름별로 그대로 남아요(squash 안 함).</p>
    <ul>${c.files.map((f) => `<li><code>${esc(f.filename)}</code> <span class="muted">+${f.additions} −${f.deletions}</span></li>`).join('')}</ul>
    <p class="err" id="mErr"></p>
    <div class="row"><a class="btn plain" href="#/compare/${S.me}" data-x>자세히 보기</a><button class="btn plain" data-x>취소</button><button class="btn" id="mGo">합치기</button></div>`;
  $$('[data-x]', m).forEach((b) => b.addEventListener('click', closeModal));
  $('#mGo', m).addEventListener('click', async () => {
    const b = $('#mGo', m); b.disabled = true; b.textContent = '합치는 중…';
    try {
      const r = await api('/merge', { body: {} });
      closeModal(); bump(); await loadBranches(); toast(`main에 합쳤어요 (커밋 ${r.commits}개).`, 5000); route();
    } catch (x) {
      b.disabled = false; b.textContent = '합치기';
      if (x.error === 'MAIN_CONFLICT') { conflictModal(x); return; }
      $('#mErr', m).textContent = x.message;
    }
  });
}
$('#mergeBtn').addEventListener('click', mergeModal);
$('#syncBtn').addEventListener('click', syncFromMain);

// 소스트리
function layoutGraph(commits, parentsOf) {
  const lanes = []; const pos = new Map(); let maxLane = 0;
  commits.forEach((c, row) => {
    let lane = lanes.indexOf(c.sha);
    if (lane === -1) { lane = lanes.indexOf(null); if (lane === -1) lane = lanes.length; }
    for (let k = 0; k < lanes.length; k++) if (lanes[k] === c.sha && k !== lane) lanes[k] = null;
    const ps = parentsOf(c);
    lanes[lane] = ps[0] || null;
    for (let k = 1; k < ps.length; k++) {
      if (!lanes.includes(ps[k])) { let f = lanes.indexOf(null); if (f === -1) f = lanes.length; lanes[f] = ps[k]; }
    }
    while (lanes.length && lanes[lanes.length - 1] === null) lanes.pop();
    pos.set(c.sha, { row, lane });
    maxLane = Math.max(maxLane, lane, lanes.length - 1);
  });
  return { pos, width: maxLane + 1 };
}
async function pageTree() {
  const docsOnly = store.get('treeDocs', false);
  main.innerHTML = `<div class="wide"><h1 class="page">소스트리</h1>
    <p class="page-sub">main과 이름 브랜치 5개의 최근 커밋 그래프예요. 커밋을 누르면 바뀐 파일과 줄을 볼 수 있어요.</p>
    <div class="tree-tools"><label><input type="checkbox" id="tDocs"${docsOnly ? ' checked' : ''}> 문서(docs/) 커밋만</label>
    <span class="muted">${['main', ...Object.keys(S.members)].map(refTag).join('')}</span></div>
    <div class="tree" id="tree"><p class="loading" style="padding:20px">불러오는 중…</p></div></div>`;
  $('#tDocs').addEventListener('change', (e) => { store.set('treeDocs', e.target.checked); pageTree(); });
  let g;
  try { g = await api('/graph' + qs({})); } catch (x) { $('#tree').innerHTML = `<p class="empty" style="padding:20px">${esc(x.message)}</p>`; return; }
  const all = new Map(g.commits.map((c) => [c.sha, c]));
  const docSet = new Set(g.docs);
  const visible = docsOnly ? g.commits.filter((c) => docSet.has(c.sha)) : g.commits;
  const vis = new Set(visible.map((c) => c.sha));
  const memo = new Map();
  const visParents = (c) => {
    if (!docsOnly) return c.parents.filter((p) => all.has(p));
    if (memo.has(c.sha)) return memo.get(c.sha);
    const out = []; const seen = new Set(); const stack = [...c.parents];
    while (stack.length) { const p = stack.shift(); if (seen.has(p) || !all.has(p)) continue; seen.add(p); if (vis.has(p)) { if (!out.includes(p)) out.push(p); } else stack.push(...all.get(p).parents); if (seen.size > 400) break; }
    memo.set(c.sha, out); return out;
  };
  const { pos, width } = layoutGraph(visible, visParents);
  const H = 30, W = 16, PAD = 14, gw = PAD * 2 + Math.max(1, width) * W;
  const headsBy = {}; Object.entries(g.heads).forEach(([r, s]) => { (headsBy[s] = headsBy[s] || []).push(r); });
  const laneColor = (l) => ['var(--m-main)', 'var(--m-DongHyeon)', 'var(--m-DongGyun)', 'var(--m-SeongWon)', 'var(--m-DaIn)', 'var(--m-YoungJun)'][l % 6];
  const x = (l) => PAD + l * W + W / 2, y = (r) => r * H + H / 2;
  let paths = '', dots = '';
  visible.forEach((c) => {
    const p = pos.get(c.sha);
    visParents(c).forEach((ps, k) => {
      const q = pos.get(ps); if (!q) return;
      const x1 = x(p.lane), y1 = y(p.row), x2 = x(q.lane), y2 = y(q.row);
      const lane = k === 0 && q.lane !== p.lane && q.lane > p.lane ? q.lane : (k === 0 ? p.lane : q.lane);
      const d = x1 === x2 ? `M${x1} ${y1}L${x2} ${y2}` : `M${x1} ${y1}C${x1} ${y1 + H * 0.8},${x2} ${y1 + H * 0.2},${x2} ${y1 + H}L${x2} ${y2}`;
      paths += `<path d="${d}" stroke="${laneColor(lane)}" stroke-width="2" fill="none" opacity=".85"/>`;
    });
    if (!visParents(c).length && c.parents.length) paths += `<path d="M${x(p.lane)} ${y(p.row)}L${x(p.lane)} ${y(p.row) + H / 2}" stroke="${laneColor(p.lane)}" stroke-width="2" stroke-dasharray="2 3"/>`;
    dots += `<circle cx="${x(p.lane)}" cy="${y(p.row)}" r="${c.parents.length > 1 ? 3.5 : 5}" fill="${laneColor(p.lane)}" stroke="var(--bg2)" stroke-width="2"/>`;
  });
  const rows = visible.map((c) => `<div class="tree-row" data-sha="${c.sha}" style="padding-left:${gw}px">
    <span class="msg">${(headsBy[c.sha] || []).map(refTag).join('')}${esc(firstLine(c.message))}</span>
    <span class="who">${nameTag(c.author).replace(/<span class="muted">.*?<\/span>/, '')}</span><span class="when">${kst(c.date)}</span></div>`).join('');
  $('#tree').innerHTML = visible.length ? `<div class="tree-body"><svg width="${gw}" height="${visible.length * H}">${paths}${dots}</svg>${rows}</div>` : '<p class="empty" style="padding:20px">커밋이 없어요.</p>';
  $$('.tree-row', main).forEach((r) => r.addEventListener('click', () => { location.hash = '#/commit/' + r.dataset.sha; }));
}

// ---------- 라우터 ----------
// ---------- 선택해서 AI에게 묻기 · LLM용 복사 ----------
const PROJECT_LINE = '명절 열차 공정 예매 시스템. 5명이 3주 동안 만드는 팀 프로젝트 (Spring Boot, Kafka, Redis, MongoDB, Oracle, Python)';
const cleanHead = (h) => String(h || '').replace(/^<a id=[^>]*><\/a>\s*/, '').replace(/^#+\s*/, '').trim();
function llmBlock(meta, heading, md, selection) {
  const lines = [`아래는 "제자리" 팀 프로젝트의 설계 문서${heading || selection ? ' 일부' : ''}입니다.`, '', `- 프로젝트: ${PROJECT_LINE}`, `- 문서: ${meta.title} (${meta.path})`];
  if (heading) lines.push(`- 섹션: ${cleanHead(heading)}`);
  if (selection) lines.push('', '[내가 고른 부분]', '<<<', selection, '>>>');
  lines.push('', selection ? '[그 부분이 들어 있는 섹션 원문]' : '[원문]', '<<<', String(md || '').trim(), '>>>', '', '질문: ');
  return lines.join('\n');
}
async function copyLater(makeText, msg) {
  const job = Promise.resolve().then(makeText);
  let done = false;
  try {
    if (window.ClipboardItem && navigator.clipboard && navigator.clipboard.write) {
      await navigator.clipboard.write([new ClipboardItem({ 'text/plain': job.then((t) => new Blob([t], { type: 'text/plain' })) })]);
      done = true;
    }
  } catch {}
  if (done) { toast(msg || '복사했어요.'); return; }
  return copyText(await job, msg);
}
// 맥락 포함 LLM용 글: 프로젝트 지도 + 전체 문서에서 찾은 관련 부분 + 지금 섹션 + 고른 부분
async function llmWithContext(meta, heading, md, selection, history) {
  let ctx = null;
  try { ctx = await api('/context', { body: { path: meta.path, heading: cleanHead(heading), selection, section: md } }); } catch {}
  const lines = [`아래는 "제자리" 팀 프로젝트의 설계 문서에서 고른 부분과, 이해에 필요한 프로젝트 맥락입니다.`, ''];
  if (ctx && ctx.brief) lines.push(ctx.brief, '');
  if (ctx && ctx.related && ctx.related.length) {
    lines.push('[관련 문서 조각 (전체 문서에서 자동 검색)]');
    ctx.related.forEach((r) => lines.push(`### ${r.doc} · ${r.heading || '(머리말)'}`, r.text, ''));
  }
  lines.push(`[지금 보고 있는 섹션: ${meta.title} (${meta.path})${heading ? ' · ' + cleanHead(heading) : ''}]`, '<<<', String(md || '').trim(), '>>>', '',
    '[내가 고른 부분]', '<<<', selection, '>>>');
  if (history && history.length) lines.push('', '[지금까지 나눈 대화]', history.map((h) => `${h.role === 'user' ? '나' : 'AI'}: ${h.content}`).join('\n\n'));
  lines.push('', '프로젝트 전체 맥락에서 이 부분이 어디에 쓰이고 무엇과 연결되는지까지 쉽게 설명해 주세요.', '질문: ');
  return lines.join('\n');
}
async function copyText(text, msg) {
  let done = false;
  try { await navigator.clipboard.writeText(text); done = true; } catch {}
  if (!done) {
    const ta = document.createElement('textarea'); ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
    document.body.appendChild(ta); ta.select();
    try { done = document.execCommand('copy'); } catch {}
    document.body.removeChild(ta);
  }
  toast(done ? (msg || '복사했어요.') : '복사하지 못했어요. 브라우저의 클립보드 권한을 확인해 주세요.');
}

const selPop = document.createElement('div');
selPop.className = 'sel-pop'; selPop.hidden = true;
selPop.innerHTML = '<button type="button" data-ask>AI에게 물어보기</button><button type="button" data-copysel>LLM용 복사</button>';
document.body.appendChild(selPop);
let selCtx = null;
function readSelection() {
  const sel = window.getSelection();
  if (!sel || sel.isCollapsed || !sel.rangeCount) return null;
  const text = sel.toString().trim();
  if (text.length < 2) return null;
  const range = sel.getRangeAt(0);
  const node = range.startContainer.nodeType === 1 ? range.startContainer : range.startContainer.parentElement;
  const ans = node && node.closest('#aiPanel .ask-msg.a');
  if (ans && ans._askMore) return { kind: 'answer', text: text.slice(0, 1000), box: ans, rect: range.getBoundingClientRect() };
  const sec = node && node.closest('.doc-body .sec');
  if (!sec || !main.contains(sec) || sec.querySelector('textarea')) return null;
  const body = sec.closest('.doc-body');
  return { text: text.slice(0, 6000), sec, meta: body._meta, rect: range.getBoundingClientRect() };
}
function placePop() {
  selCtx = readSelection();
  if (!selCtx) { selPop.hidden = true; return; }
  const inAnswer = selCtx.kind === 'answer';
  $('[data-ask]', selPop).hidden = !S.ai;
  $('[data-ask]', selPop).textContent = inAnswer ? '이 부분 더 물어보기' : 'AI에게 물어보기';
  $('[data-copysel]', selPop).hidden = inAnswer;
  selPop.hidden = false;
  if (window.innerWidth < 700) { selPop.classList.add('bottom'); selPop.style.left = ''; selPop.style.top = ''; return; }
  selPop.classList.remove('bottom');
  const r = selCtx.rect, w = selPop.offsetWidth, h = selPop.offsetHeight;
  const top = r.top - h - 8 > 64 ? r.top - h - 8 : r.bottom + 8;
  selPop.style.top = Math.round(top) + 'px';
  selPop.style.left = Math.round(Math.min(Math.max(8, r.left + r.width / 2 - w / 2), window.innerWidth - w - 8)) + 'px';
}
let selTimer;
const schedulePop = () => { clearTimeout(selTimer); selTimer = setTimeout(placePop, 120); };
document.addEventListener('mouseup', (e) => { if (!selPop.contains(e.target)) schedulePop(); });
document.addEventListener('keyup', (e) => { if (e.shiftKey || e.key === 'Shift') schedulePop(); });
document.addEventListener('selectionchange', () => { if (window.innerWidth < 700 || !selPop.hidden) schedulePop(); });
window.addEventListener('scroll', () => { if (!selPop.hidden && !selPop.classList.contains('bottom')) placePop(); }, { passive: true });
selPop.addEventListener('mousedown', (e) => e.preventDefault());
$('[data-copysel]', selPop).addEventListener('click', () => {
  if (!selCtx || selCtx.kind === 'answer') return;
  const c = selCtx;
  copyLater(() => llmWithContext(c.meta, c.sec._head, c.sec._md, c.text), '고른 부분을 프로젝트 맥락과 함께 LLM용으로 복사했어요.');
  selPop.hidden = true;
});
$('[data-ask]', selPop).addEventListener('click', () => {
  if (!selCtx) return;
  const c = selCtx; selPop.hidden = true; window.getSelection().removeAllRanges();
  if (c.kind === 'answer') { c.box._askMore(c.text); return; }
  askModal(c);
});

const ASK_CHIPS = ['쉽게 설명해줘', '왜 이렇게 설계했어?', '예시로 보여줘', '코드로는 어떻게 구현해?'];
const RM_CHIPS = ['내가 다음에 뭘 하면 돼?', '어떤 순서로 하면 돼?', '누가 누구를 기다리고 있어?', '이번 주에 끝내야 할 게 뭐야?'];
const FREE_CHIPS = ['제자리가 어떤 프로젝트야?', '좌석 중복 예약은 어떻게 막아?', '결제가 실패하면 어떻게 돼?', '1차 구현 범위가 뭐야?'];
const AI_LOG_MAX = 40;

// ---- 질문 기록: 로그인하면 계정(서버), 아니면 이 기기 ----
const localLog = {
  all() { const v = store.get('ai-log', []); return Array.isArray(v) ? v : []; },
  put(conv) {
    let out = [conv, ...this.all().filter((x) => x.id !== conv.id)].slice(0, AI_LOG_MAX);
    for (let i = 0; i < 5; i++) {
      try { localStorage.setItem('jejari-site:ai-log', JSON.stringify(out)); return; } catch {}
      out = out.slice(0, Math.max(1, Math.floor(out.length / 2)));
    }
  },
  remove(id) { store.set('ai-log', this.all().filter((x) => x.id !== id)); },
  clear() { store.set('ai-log', []); },
};
const aiStore = {
  async list() {
    if (!S.me) return localLog.all();
    const loc = localLog.all();
    if (loc.length) {
      try { const r = await api('/ai-log/merge', { body: { list: loc } }); localLog.clear(); toast('이 기기의 질문 기록을 계정으로 옮겼어요.'); return r.list; } catch {}
    }
    return (await api('/ai-log' + qs({ t: Date.now() }))).list;
  },
  async save(conv) {
    conv.at = Date.now();
    if (S.me) {
      try { await api('/ai-log/save', { body: { conv } }); return; } catch { toast('계정에 저장하지 못해 이 기기에 저장했어요.'); }
    }
    localLog.put(conv);
  },
  async remove(id) {
    localLog.remove(id);
    if (S.me) await api('/ai-log/delete', { body: { id } });
  },
  async clear() {
    localLog.clear();
    if (S.me) await api('/ai-log/delete', { body: { all: true } });
  },
};

// ---- 참고한 부분으로 이동 ----
const DOC_HASH = { 'README.md': '#/doc/plan', 'docs/기능설계서.md': '#/doc/func', 'docs/concurrency.md': '#/doc/conc', 'docs/DB설계서.md': '#/db/doc' };
function hashOfPath(path) {
  if (DOC_HASH[path]) return DOC_HASH[path];
  const m = String(path || '').match(/^docs\/ui\/([UA]-\d{2})\.md$/);
  return m ? '#/ui/' + m[1] : null;
}
let pendingJump = null;
const normHead = (t) => String(t || '').replace(/[`*]/g, '').replace(/\s+/g, ' ').trim();
function applyJump(root, path) {
  if (!pendingJump || pendingJump.path !== path) return;
  const parts = String(pendingJump.heading || '').split(' > ').map(normHead).filter(Boolean);
  pendingJump = null;
  const heads = $$('h1,h2,h3,h4,details > summary', root);
  let target = null;
  for (let i = parts.length - 1; i >= 0 && !target; i--) {
    target = heads.find((h) => normHead(h.textContent) === parts[i]) || heads.find((h) => normHead(h.textContent).startsWith(parts[i]));
  }
  const el = target || root;
  setTimeout(() => {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    const sec = el.closest('.sec') || el; sec.classList.add('flash'); setTimeout(() => sec.classList.remove('flash'), 2200);
  }, 60);
}
function jumpTo(path, heading) {
  const h = hashOfPath(path); if (!h) return;
  pendingJump = { path, heading };
  closeModal();
  if (window.innerWidth < 700) aiClose();
  if (location.hash === h) route(); else location.hash = h;
}

// ---- 오른쪽 아래 채팅 ----
const aiFab = document.createElement('button');
aiFab.className = 'ai-fab'; aiFab.type = 'button'; aiFab.hidden = true; aiFab.textContent = 'AI 질문';
const aiPanel = document.createElement('section');
aiPanel.className = 'ai-panel'; aiPanel.id = 'aiPanel'; aiPanel.hidden = true; aiPanel.setAttribute('aria-label', 'AI 질문');
aiPanel.innerHTML = `<header class="ai-head">
    <button type="button" class="btn sm plain" data-ai-list>목록</button>
    <b class="ai-title">AI 질문</b>
    <button type="button" class="btn sm" data-ai-new>새 채팅</button>
    <button type="button" class="ai-x" data-ai-x aria-label="닫기">✕</button>
  </header>
  <div class="ai-body"></div>`;
document.body.appendChild(aiFab); document.body.appendChild(aiPanel);
const aiBody = $('.ai-body', aiPanel);
const AI = { conv: null, view: 'chat', busy: false };

function newConv(c) {
  return {
    id: 'c' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6), at: Date.now(),
    doc: c ? c.meta.title : '', path: c ? c.meta.path : '', heading: c ? cleanHead(c.sec._head) : '',
    selection: c ? c.text : '', section: c ? String(c.sec._md || '').slice(0, 6000) : '', messages: [],
  };
}
function aiFabSync() {
  aiFab.hidden = !S.ai || !aiPanel.hidden;
  if (!aiPanel.hidden && AI.view === 'list') aiList();
}
function aiOpen() { aiPanel.hidden = false; aiFab.hidden = true; document.body.classList.add('ai-open'); }
function aiClose() { aiPanel.hidden = true; document.body.classList.remove('ai-open'); aiFabSync(); }
function askModal(c, firstQuestion) { aiOpen(); aiChat(newConv(c), firstQuestion == null ? '쉽게 설명해줘' : firstQuestion); }

aiFab.addEventListener('click', () => { aiOpen(); if (AI.conv && AI.view === 'chat') aiChat(AI.conv); else aiChat(newConv(null), ''); });
$('[data-ai-x]', aiPanel).addEventListener('click', aiClose);
$('[data-ai-new]', aiPanel).addEventListener('click', () => aiChat(newConv(null), ''));
$('[data-ai-list]', aiPanel).addEventListener('click', () => aiList());
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !aiPanel.hidden && $('#modalBack').hidden) aiClose(); });

const convTitle = (c) => ((c.messages.find((x) => x.role === 'user') || {}).content || c.selection || '새 채팅').slice(0, 80);

async function aiList() {
  AI.view = 'list';
  aiBody.innerHTML = '<p class="muted ai-pad">불러오는 중…</p>';
  let list = [];
  try { list = await aiStore.list(); } catch (x) { aiBody.innerHTML = `<p class="err ai-pad">${esc(x.message)}</p>`; return; }
  if (AI.view !== 'list') return;
  aiBody.innerHTML = `<p class="muted ai-pad ai-where-saved">${S.me ? `${esc(S.ko || S.me)} 계정에 저장돼요. 다른 기기에서 로그인해도 보여요.` : '이 기기에만 저장돼요. 로그인하면 계정에 저장돼 다른 기기에서도 볼 수 있어요.'} 최근 ${AI_LOG_MAX}개까지 남아요.</p>
    ${list.length ? `<div class="ai-list">${list.map((c) => {
      const n = c.messages.filter((x) => x.role === 'user').length;
      return `<div class="ai-item" data-id="${esc(c.id)}">
        <button type="button" class="ai-item-open"><b>${esc(convTitle(c))}</b>
          <span class="muted">${esc(kst(new Date(c.at).toISOString()))}${c.doc ? ' · ' + esc(c.doc) : ''}${n > 1 ? ` · 질문 ${n}개` : ''}</span></button>
        <button type="button" class="btn sm plain" data-del>삭제</button></div>`;
    }).join('')}</div>
    <p class="ai-pad"><button type="button" class="btn sm plain" data-clear>기록 모두 지우기</button></p>`
    : '<p class="empty ai-pad">아직 물어본 내용이 없어요. "새 채팅"으로 물어보거나, 문서에서 글을 드래그해 물어보세요.</p>'}`;
  $$('.ai-item', aiBody).forEach((row) => {
    const conv = list.find((x) => x.id === row.dataset.id);
    $('.ai-item-open', row).addEventListener('click', () => aiChat(conv));
    twoStep($('[data-del]', row), '한 번 더', async () => {
      try { await aiStore.remove(conv.id); } catch (x) { toast(x.message); }
      if (AI.conv && AI.conv.id === conv.id) AI.conv = null;
      aiList();
    });
  });
  const clr = $('[data-clear]', aiBody);
  if (clr) twoStep(clr, '한 번 더 누르면 모두 삭제', async () => { try { await aiStore.clear(); } catch (x) { toast(x.message); } AI.conv = null; aiList(); });
}

function aiChat(conv, prefill) {
  AI.view = 'chat'; AI.conv = conv;
  const free = !conv.selection;
  aiBody.innerHTML = `<div class="ai-chat">
      ${free ? '' : `<div class="ai-src"><a href="#" data-src>${esc(conv.doc)}${conv.heading ? ' · ' + esc(conv.heading) : ''}</a><blockquote class="ask-quote">${esc(conv.selection)}</blockquote></div>`}
      ${conv.messages.length ? '' : `<p class="muted ai-hello">${free ? '프로젝트에 대해 무엇이든 물어보세요. AI는 프로젝트 지도와 관련 문서를 찾아 보고 짧게 답해요. 문서에서 글을 드래그해 물어볼 수도 있어요.' : /^roadmap/.test(conv.path) ? '지금 로드맵의 할 일, 누가 했는지, 먼저 끝나야 할 일, 할 순서를 보고 답해요.' : '고른 부분에 대해 물어보세요.'}</p>`}
      <div class="ask-log"></div>
    </div>
    <div class="ai-foot">
      ${conv.messages.length ? '' : `<div class="ask-chips">${(/^roadmap/.test(conv.path) ? RM_CHIPS : free ? FREE_CHIPS : ASK_CHIPS).map((q) => `<button type="button" class="chip">${esc(q)}</button>`).join('')}</div>`}
      <form class="ask-row"><input type="text" maxlength="500" placeholder="${conv.messages.length ? '이어서 물어보기' : '질문 입력'}" aria-label="질문"><button class="btn" type="submit">묻기</button></form>
      <p class="muted ask-note">답 속 문장을 드래그하면 그 부분만 더 물어볼 수 있어요. AI는 틀릴 수 있으니 중요한 건 원문이나 담당 팀원에게 확인하세요.</p>
    </div>`;
  const log = $('.ask-log', aiBody), input = $('.ask-row input', aiBody), send = $('.ask-row button', aiBody);
  if (prefill) input.value = prefill;
  const scroller = $('.ai-chat', aiBody);
  const toBottom = () => { scroller.scrollTop = scroller.scrollHeight; };

  function drawQuestion(box, idx) {
    box.innerHTML = `<span>${esc(conv.messages[idx].content)}</span><button type="button" class="ai-qdel" title="이 질문과 답 삭제">삭제</button>`;
    twoStep($('.ai-qdel', box), '한 번 더', async () => {
      conv.messages.splice(idx, conv.messages[idx + 1] && conv.messages[idx + 1].role === 'assistant' ? 2 : 1);
      if (conv.messages.length) await aiStore.save(conv); else { try { await aiStore.remove(conv.id); } catch {} }
      aiChat(conv);
    });
  }
  function drawAnswer(box, msg) {
    box.innerHTML = '';
    box.appendChild(renderMd(msg.content));
    if (msg.followups && msg.followups.length) {
      const f = document.createElement('div'); f.className = 'ask-follow';
      f.innerHTML = '<span class="muted">이어서 물어보기</span>' + msg.followups.map((q) => `<button type="button" class="chip">${esc(q)}</button>`).join('');
      $$('button', f).forEach((b) => b.addEventListener('click', () => ask(b.textContent)));
      box.appendChild(f);
    }
    if (msg.refs && msg.refs.length) {
      const d = document.createElement('div'); d.className = 'ask-refs';
      d.innerHTML = `<span class="muted">참고한 부분</span>${msg.refs.map((x, i) => hashOfPath(x.path)
        ? `<a href="${hashOfPath(x.path)}" data-ref="${i}">${esc(x.doc)} · ${esc(x.heading || '문서 처음')}</a>`
        : `<span>${esc(x.doc)} · ${esc(x.heading || '문서 처음')}</span>`).join('')}`;
      $$('a[data-ref]', d).forEach((a) => a.addEventListener('click', (e) => { e.preventDefault(); const x = msg.refs[+a.dataset.ref]; jumpTo(x.path, x.heading); }));
      box.appendChild(d);
    }
    box._askMore = (text) => ask(`"${text.slice(0, 300)}" 이 부분을 좀 더 자세히 알려줘`);
  }
  const add = (cls) => { const d = document.createElement('div'); d.className = 'ask-msg ' + cls; log.appendChild(d); return d; };
  conv.messages.forEach((m, i) => { if (m.role === 'user') drawQuestion(add('q'), i); else drawAnswer(add('a'), m); });
  setTimeout(toBottom, 20);

  async function ask(q) {
    q = String(q || '').trim(); if (!q || AI.busy) return;
    AI.busy = true; send.disabled = true; input.value = '';
    $$('.ask-chips, .ai-hello', aiBody).forEach((e) => e.remove());
    const qBox = add('q'); qBox.innerHTML = `<span>${esc(q)}</span>`;
    const wait = add('a'); wait.innerHTML = '<p class="muted">생각하는 중…</p>';
    toBottom();
    const history = conv.messages.map((x) => ({ role: x.role, content: x.content }));
    try {
      const r = await api('/ask', { body: { doc: conv.doc, path: conv.path, heading: conv.heading, selection: conv.selection, section: conv.section, question: q, history: history.length ? history : undefined } });
      conv.messages.push({ role: 'user', content: q }, { role: 'assistant', content: r.answer, followups: r.followups || [], refs: r.refs || [], model: r.model });
      drawQuestion(qBox, conv.messages.length - 2);
      drawAnswer(wait, conv.messages[conv.messages.length - 1]);
      input.placeholder = '이어서 물어보기';
      aiStore.save(conv);
    } catch (x) {
      wait.innerHTML = `<p class="err">${esc(x.message)}</p>`;
    } finally {
      AI.busy = false; send.disabled = false;
      if (AI.conv === conv && document.body.contains(input)) { input.focus(); toBottom(); }
    }
  }
  $('.ask-row', aiBody).addEventListener('submit', (e) => { e.preventDefault(); ask(input.value); });
  $$('.ask-chips .chip', aiBody).forEach((b) => b.addEventListener('click', () => ask(b.textContent)));
  const src = $('[data-src]', aiBody);
  if (src) src.addEventListener('click', (e) => {
    e.preventDefault();
    if (/^roadmap/.test(conv.path)) { const k = conv.path.split(':')[1]; location.hash = '#/roadmap/flow'; if (k) setTimeout(() => { if (RM.data) rmArea(k); }, 80); return; }
    jumpTo(conv.path, conv.heading);
  });
  if (window.innerWidth >= 700) setTimeout(() => input.focus(), 30);
}

function setNav(key) { $$('.side a').forEach((a) => a.classList.toggle('on', a.dataset.nav === key)); $('#side').classList.remove('open'); }
// ---------- 팀원 문서 (docs/{한글이름}/: 개발일지·기능 문서·트러블슈팅·기여 정리) ----------
const PKINDS = [
  ['devlog', '개발일지', '한 일, 어떻게 했나, 왜 그렇게 했나', 'docs/templates/devlog.md'],
  ['features', '기능 문서', '내가 만든 기능을 1~2분 안에 이해하게', 'docs/templates/feature-note.md'],
  ['troubleshooting', '트러블슈팅', '문제 → 원인 → 해결 → 수치', 'docs/templates/troubleshooting.md'],
  ['contribution', '기여 정리', '담당 범위, 핵심 수치, 설계 결정', null],
];
const personDir = (name) => `docs/${S.members[name]}`;
const todayKst = () => new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 10);
const docTitle = (md, fallback) => { const m = /^#\s+(.+)$/m.exec(md || ''); return m ? m[1].trim() : fallback; };
const isPersonDoc = (n) => /\.(md|html)$/.test(n);
const isHtml = (n) => /\.html$/i.test(n || '');
const fileLabel = (f) => { const b = f.name.replace(/\.(md|html)$/, ''); const m = /^(\d{4})(\d{2})(\d{2})-(.*)$/.exec(b); return m ? `${m[1]}-${m[2]}-${m[3]} · ${m[4].replace(/-/g, ' ')}` : b.replace(/-/g, ' '); };

async function pagePeople() {
  const names = Object.keys(S.members);
  main.innerHTML = `<div class="wrap"><h1 class="page">팀원 문서</h1>
    <p class="page-sub">팀원마다 개발일지, 기능 문서, 트러블슈팅, 기여 정리가 있어요. 레포의 <code>docs/이름/</code> 폴더를 그대로 보여 줘요.${S.me ? ' 내 폴더는 여기서 바로 쓰고 고칠 수 있어요.' : ''}</p>
    ${branchBanner(S.ref)}
    <div class="cards">${names.map((n) => `<a class="card person-card" href="#/people/${n}"><h3>${esc(S.members[n])} <span class="muted" style="font-weight:500">${n}</span></h3>
      <p class="pc-stat" data-n="${n}">불러오는 중…</p></a>`).join('')}</div></div>`;
  bindBanner(main);
  await Promise.all(names.map(async (n) => {
    const counts = await Promise.all(['devlog', 'features', 'troubleshooting'].map((k) => api('/list' + qs({ dir: `${personDir(n)}/${k}`, ref: S.ref })).then((l) => l.filter((f) => isPersonDoc(f.name))).catch(() => [])));
    const el = $(`.pc-stat[data-n="${n}"]`); if (!el) return;
    const last = counts[0].map((f) => f.name).sort().pop();
    el.textContent = `개발일지 ${counts[0].length} · 기능 문서 ${counts[1].length} · 트러블슈팅 ${counts[2].length}${last && /^\d{8}/.test(last) ? ` · 최근 일지 ${last.slice(0, 4)}-${last.slice(4, 6)}-${last.slice(6, 8)}` : ''}`;
  }));
}

async function pagePerson(name, kind, file) {
  if (!S.members[name]) { main.innerHTML = '<div class="wrap"><p class="empty">없는 팀원이에요. <a href="#/people">팀원 문서</a></p></div>'; return; }
  kind = PKINDS.some((k) => k[0] === kind) ? kind : 'devlog';
  const mine = S.me === name && (S.ref === 'main' || S.ref === S.me);
  const kd = PKINDS.find((k) => k[0] === kind);
  main.innerHTML = `<div class="wrap"><p class="page-sub" style="margin:0 0 4px"><a href="#/people">팀원 문서</a> › <b>${esc(S.members[name])}</b> <span class="muted">${name}</span></p>
    <h1 class="page">${esc(S.members[name])}${file ? '' : ` · ${kd[1]}`}</h1>
    <div class="tabs">${PKINDS.map(([k, t]) => `<button data-k="${k}"${k === kind ? ' class="on"' : ''}>${t}</button>`).join('')}</div>
    <div id="pBody"></div></div>`;
  $$('.tabs button', main).forEach((b) => b.addEventListener('click', () => { location.hash = `#/people/${name}/${b.dataset.k}`; }));
  const root = $('#pBody');
  if (kind === 'contribution') return docView(root, `${personDir(name)}/contribution.md`, { compact: true, withToc: true });
  if (file) {
    const path = `${personDir(name)}/${kind}/${file}`;
    root.innerHTML = `<p style="margin:0 0 8px"><a href="#/people/${name}/${kind}">← ${kd[1]} 목록</a></p><div id="pDoc"></div>`;
    if (isHtml(file)) return htmlView($('#pDoc'), path, { owner: name, back: `#/people/${name}/${kind}` });
    return docView($('#pDoc'), path, { compact: true, withToc: true });
  }
  root.innerHTML = `${branchBanner(S.ref)}<div class="row" style="justify-content:space-between;margin:0 0 12px;align-items:center">
    <span class="muted">${esc(kd[2])} · <code>${esc(personDir(name))}/${kind}/</code></span>
    ${mine ? `<span class="row" style="gap:6px"><button class="btn plain" id="pHtml" title="보고서·시연 페이지 같은 HTML 파일을 올려요">+ HTML</button><button class="btn" id="pNew">+ 새 ${kd[1]}</button></span>` : (S.me && S.me !== name ? '' : (!S.me ? '<span class="muted">로그인하면 내 폴더에 쓸 수 있어요</span>' : ''))}</div>
    <div id="pList" class="loading">불러오는 중…</div>`;
  bindBanner(root);
  const nb = $('#pNew'); if (nb) nb.addEventListener('click', () => newPersonDoc(name, kind));
  const hb = $('#pHtml'); if (hb) hb.addEventListener('click', () => editHtml({ dir: `${personDir(name)}/${kind}`, back: `#/people/${name}/${kind}`, dated: kind !== 'features' }));
  let list = [];
  const box = $('#pList');
  try { list = (await api('/list' + qs({ dir: `${personDir(name)}/${kind}`, ref: S.ref }))).filter((f) => f.type === 'file' && isPersonDoc(f.name)); } catch (x) { if (box.isConnected) box.outerHTML = `<p class="empty">${esc(x.message)}</p>`; return; }
  if (!box.isConnected) return;
  list.sort((a, b) => (kind === 'devlog' || kind === 'troubleshooting' ? b.name.localeCompare(a.name) : a.name.localeCompare(b.name)));
  if (!list.length) { box.outerHTML = `<p class="empty">아직 ${kd[1]}가 없어요.${mine ? ' 오른쪽 위 버튼으로 첫 글을 써 보세요.' : ''}</p>`; return; }
  box.outerHTML = `<div class="plist">${list.map((f) => `<a class="pitem" href="#/people/${name}/${kind}/${encodeURIComponent(f.name)}"><b>${esc(fileLabel(f))}${isHtml(f.name) ? ' <span class="badge">HTML</span>' : ''}</b><span class="muted">${esc(f.name)}</span></a>`).join('')}</div>`;
}

// HTML 문서: 사이트와 분리된 iframe(sandbox, 같은 출처 아님)에 그대로 그린다. 스크립트는 돌지만 사이트 쿠키·API에는 닿지 못한다.
async function htmlView(root, path, { owner, back } = {}) {
  const ref = S.ref;
  root.innerHTML = '<p class="loading">불러오는 중…</p>';
  const file = await fetchFile(path, ref);
  if (!file) { root.innerHTML = `<p class="empty">${esc(ref)} 브랜치에 <code>${esc(path)}</code> 파일이 없어요.</p>`; return; }
  const canEdit = !!S.me && S.me === owner && (ref === 'main' || ref === S.me);
  const gh = `https://github.com/brainsaver/jejari/blob/${encodeURIComponent(ref)}/${path.split('/').map(encodeURIComponent).join('/')}`;
  root.innerHTML = `${branchBanner(ref)}<div class="row" style="justify-content:space-between;align-items:center;margin:0 0 8px;gap:8px;flex-wrap:wrap">
    <span class="muted"><code>${esc(path)}</code> · <a href="${gh}" target="_blank" rel="noopener">GitHub</a></span>
    <span class="row" style="gap:6px"><button class="btn sm plain" data-fs>전체 화면</button><button class="btn sm plain" data-dl>내려받기</button>${canEdit ? '<button class="btn sm" data-edit>HTML 수정</button>' : ''}</span></div>
    <div class="html-doc"><button class="btn sm plain fs-close" hidden>닫기 ✕</button><iframe sandbox="allow-scripts allow-popups allow-modals" referrerpolicy="no-referrer" title="${esc(path.split('/').pop())}"></iframe></div>`;
  bindBanner(root);
  const wrap = $('.html-doc', root), fr = $('iframe', wrap), close = $('.fs-close', wrap);
  fr.srcdoc = file.content;
  const fsOff = () => { wrap.classList.remove('fs'); close.hidden = true; document.body.classList.remove('no-scroll'); if (document.fullscreenElement) document.exitFullscreen().catch(() => {}); };
  $('[data-fs]', root).addEventListener('click', () => { wrap.classList.add('fs'); close.hidden = false; document.body.classList.add('no-scroll'); if (wrap.requestFullscreen) wrap.requestFullscreen().catch(() => {}); });
  close.addEventListener('click', fsOff);
  wrap.addEventListener('fullscreenchange', () => { if (!document.fullscreenElement) { wrap.classList.remove('fs'); close.hidden = true; document.body.classList.remove('no-scroll'); } });
  $('[data-dl]', root).addEventListener('click', () => {
    const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([file.content], { type: 'text/html' }));
    a.download = path.split('/').pop(); document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  });
  const eb = $('[data-edit]', root); if (eb) eb.addEventListener('click', () => editHtml({ path, old: file.content, back: location.hash }));
}
// HTML 쓰기·고치기: 왼쪽 원문(붙여 넣기·파일 열기), 오른쪽 실시간 미리보기. 새 파일이면 위에서 제목(=파일 이름)을 정한다.
function editHtml({ path, old = null, dir, back, dated }) {
  if (!needLogin()) return;
  const isNew = !path;
  main.innerHTML = `<div class="wide"><p style="margin:0 0 6px"><a href="${esc(back)}">← 돌아가기</a> › ${isNew ? '새 HTML' : 'HTML 수정'}</p>
    ${isNew ? `<div class="new-meta">${dated ? `<label>날짜 <input type="date" id="hDate" value="${todayKst()}"></label>` : ''}<label class="grow">제목 <input type="text" id="hTitle" maxlength="60" placeholder="예: 대기열 부하 테스트 결과"></label>
      <label class="btn sm plain" style="cursor:pointer">파일 열기<input type="file" id="hFile" accept=".html,.htm,text/html" hidden></label><span class="muted" id="hPath"></span></div>` : ''}
    <div class="editor tall"><div class="editor-bar"><b>${esc(path || 'HTML')}</b><span class="grow"></span><span class="muted">${esc(S.me)} 브랜치에 저장</span>
    <button class="btn sm plain" data-cancel>취소</button><button class="btn sm" data-save>저장</button></div>
    <div class="editor-body"><textarea class="code" spellcheck="false" placeholder="HTML을 붙여 넣거나 위의 '파일 열기'로 불러오세요"></textarea><iframe class="preview" sandbox="allow-scripts" title="미리보기"></iframe></div></div></div>`;
  const ta = $('textarea', main), pv = $('iframe', main), ti = $('#hTitle'), di = $('#hDate'), fi = $('#hFile');
  ta.value = old || '<!doctype html>\n<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>제목</title></head>\n<body>\n</body></html>\n';
  const slug = (t) => (t || '').trim().replace(/[\\/:*?"<>|#%&{}$!'@`=+^~.,\[\]()]/g, '').replace(/\s+/g, '-').replace(/-+/g, '-').slice(0, 40) || '제목';
  const pathOf = () => path || `${dir}/${di ? di.value.replace(/-/g, '') + '-' : ''}${slug(ti.value)}.html`;
  const draw = () => { pv.srcdoc = ta.value; const hp = $('#hPath'); if (hp) hp.textContent = pathOf(); };
  let t; ta.addEventListener('input', () => { clearTimeout(t); t = setTimeout(draw, 300); }); draw();
  if (ti) ti.addEventListener('input', draw); if (di) di.addEventListener('input', draw);
  if (fi) fi.addEventListener('change', async () => {
    const f = fi.files[0]; if (!f) return;
    if (f.size > 900 * 1024) { toast('900KB보다 큰 HTML은 올릴 수 없어요.'); return; }
    ta.value = await f.text();
    if (!ti.value.trim()) { const m = /<title>([^<]*)<\/title>/i.exec(ta.value); ti.value = (m && m[1].trim()) || f.name.replace(/\.html?$/i, ''); }
    draw();
  });
  ta.addEventListener('keydown', (e) => { if (e.key === 'Tab') { e.preventDefault(); ta.setRangeText('  ', ta.selectionStart, ta.selectionEnd, 'end'); } });
  if (ti) ti.focus();
  $('[data-cancel]', main).addEventListener('click', () => { location.hash = back; route(); });
  $('[data-save]', main).addEventListener('click', () => {
    if (isNew && !ti.value.trim()) { toast('제목을 써 주세요.'); ti.focus(); return; }
    if (ta.value === old) { toast('바뀐 내용이 없어요.'); return; }
    const p = pathOf(), after = ta.value.endsWith('\n') ? ta.value : ta.value + '\n';
    commitFlow({
      path: p, heading: `${p.split('/').pop()} HTML`, before: old || '', after, title: isNew ? '새 HTML 저장' : undefined,
      run: async (message) => {
        try {
          await api('/save', { body: { path: p, mode: 'file', oldText: old, newText: after, message } });
          closeModal(); bump(); toast(`${S.me} 브랜치에 저장했어요. 다른 사람에게도 보이게 하려면 위의 "main에 합치기"를 눌러 주세요.`, 5000);
          if (S.ref !== S.me) { S.ref = S.me; store.set('ref', S.me); }
          loadBranches();
          const [, ko, kind, fname] = p.split('/'); const who = Object.keys(S.members).find((n) => S.members[n] === ko);
          location.hash = `#/people/${who}/${kind}/${encodeURIComponent(fname)}`; if (!isNew) route();
        } catch (x) {
          if (x.error === 'MAIN_CONFLICT') { conflictModal(x); return; }
          if (x.error === 'STALE') { if (isNew) throw new Error('같은 이름의 파일이 이미 있어요. 제목을 바꿔 주세요.'); staleModal({ ...x, current: x.current }, ta.value, p, ''); return; }
          throw x;
        }
      },
    });
  });
}

// 새 글: 위에서 날짜·제목을 정하고, 아래에서 마크다운(노션처럼 **굵게**, > 인용, - 목록, ## 제목, ``` 코드)으로 쓰며 오른쪽에서 바로 미리 본다
async function newPersonDoc(name, kind) {
  if (!needLogin()) return;
  const kd = PKINDS.find((k) => k[0] === kind);
  main.innerHTML = '<div class="wide"><p class="loading">틀을 불러오는 중…</p></div>';
  const tpl = ((await fetchFile(kd[3], 'main').catch(() => null)) || {}).content || '# 제목\n\n';
  const date = todayKst();
  main.innerHTML = `<div class="wide"><p style="margin:0 0 6px"><a href="#/people/${name}/${kind}">${esc(S.members[name])} · ${kd[1]}</a> › 새 글</p>
    <div class="new-meta">${kind === 'features' ? '' : `<label>날짜 <input type="date" id="nDate" value="${date}"></label>`}
      <label class="grow">제목 <input type="text" id="nTitle" maxlength="60" placeholder="${kind === 'devlog' ? '예: 결제 Saga 보상 트랜잭션' : kind === 'features' ? '예: 좌석 선점' : '예: PG 콜백 중복 수신'}"></label>
      <span class="muted" id="nPath"></span></div>
    <div class="md-help muted"><code>**굵게**</code> <code>*기울임*</code> <code>&gt; 인용</code> <code>- 목록</code> <code>1. 번호</code> <code>- [ ] 할 일</code> <code>## 제목</code> <code>\`코드\`</code> <code>\`\`\`코드 블록\`\`\`</code> <code>| 표 |</code> 를 쓰면 오른쪽처럼 보여요. 이미 있는 글은 저장 뒤 섹션마다 "수정"으로 고쳐요.</div>
    <div class="editor tall"><div class="editor-bar"><b>${kd[1]}</b><span class="grow"></span><span class="muted">${esc(S.me)} 브랜치에 저장</span>
    <button class="btn sm plain" data-cancel>취소</button><button class="btn sm" data-save>저장</button></div>
    <div class="editor-body"><textarea class="code" spellcheck="false"></textarea><div class="preview md"></div></div></div></div>`;
  const ta = $('textarea', main), pv = $('.preview', main), ti = $('#nTitle'), di = $('#nDate');
  const slug = (t) => (t || '').trim().replace(/[\\/:*?"<>|#%&{}$!'@`=+^~.,\[\]()]/g, '').replace(/\s+/g, '-').replace(/-+/g, '-').slice(0, 40) || '제목';
  const pathOf = () => {
    const d = di ? di.value.replace(/-/g, '') : '';
    return `${personDir(name)}/${kind}/${kind === 'features' ? '' : d + '-'}${slug(ti.value)}.md`;
  };
  // 첫 줄 제목은 위 칸에서 정한 값으로 바꾼다
  const head = () => kind === 'devlog' ? `# ${di.value} · ${ti.value.trim() || '한 줄 제목'}` : kind === 'features' ? `# ${ti.value.trim() || '기능 이름'}` : `# ${ti.value.trim() || '한 줄 제목'}`;
  let body = tpl.replace(/^#\s+.*$/m, '').replace(/^> 파일 이름:.*\n/m, '').replace(/^\n+/, '');
  body = body.replace(/작성: 이름/, `작성: ${S.members[name]}`).replace(/작성자: 이름/, `작성자: ${S.members[name]}`).replace(/\| 작성자 \| 이름 \|/, `| 작성자 | ${S.members[name]} |`).replace(/\| 날짜 \| YYYY-MM-DD \|/, `| 날짜 | ${date} |`);
  ta.value = head() + '\n\n' + body;
  const draw = () => { pv.innerHTML = ''; pv.appendChild(renderMd(ta.value)); $('#nPath').textContent = pathOf(); };
  const syncHead = () => { ta.value = ta.value.replace(/^#\s+.*$/m, head()); draw(); };
  ti.addEventListener('input', syncHead); if (di) di.addEventListener('input', syncHead);
  let t; ta.addEventListener('input', () => { clearTimeout(t); t = setTimeout(draw, 150); }); draw();
  // 목록·인용·할 일 줄에서 Enter를 누르면 다음 줄에 같은 기호를 이어 준다 (노션처럼)
  ta.addEventListener('keydown', (e) => {
    if (e.key === 'Tab') { e.preventDefault(); ta.setRangeText('  ', ta.selectionStart, ta.selectionEnd, 'end'); return; }
    if (e.key !== 'Enter' || e.shiftKey || e.isComposing) return;
    const s = ta.selectionStart, line = ta.value.slice(ta.value.lastIndexOf('\n', s - 1) + 1, s);
    const m = /^(\s*)(- \[[ x]\] |[-*] |> |(\d+)\. )/.exec(line); if (!m) return;
    e.preventDefault();
    if (line.trim() === m[2].trim()) { ta.setRangeText('', s - line.length, s, 'end'); draw(); return; }
    const next = m[3] ? `${Number(m[3]) + 1}. ` : m[2].replace('[x]', '[ ]');
    ta.setRangeText('\n' + m[1] + next, s, ta.selectionEnd, 'end'); draw();
  });
  ti.focus();
  $('[data-cancel]', main).addEventListener('click', () => { location.hash = `#/people/${name}/${kind}`; route(); });
  $('[data-save]', main).addEventListener('click', () => {
    if (!ti.value.trim()) { toast('제목을 써 주세요.'); ti.focus(); return; }
    const path = pathOf(), after = ta.value.endsWith('\n') ? ta.value : ta.value + '\n';
    commitFlow({
      path, heading: head(), before: '', after, title: `새 ${kd[1]} 저장`,
      run: async (message) => {
        try {
          await api('/save', { body: { path, mode: 'file', oldText: null, newText: after, message } });
          closeModal(); bump(); toast(`${S.me} 브랜치에 저장했어요. 다른 사람에게도 보이게 하려면 위의 "main에 합치기"를 눌러 주세요.`, 5000);
          if (S.ref !== S.me) { S.ref = S.me; store.set('ref', S.me); }
          loadBranches();
          location.hash = `#/people/${name}/${kind}/${encodeURIComponent(path.split('/').pop())}`;
        } catch (x) {
          if (x.error === 'MAIN_CONFLICT') { conflictModal(x); return; }
          if (x.error === 'STALE') throw new Error('같은 이름의 글이 이미 있어요. 제목을 바꿔 주세요.');
          throw x;
        }
      },
    });
  });
}

// ---------- 선 아이콘 (SVG, 24칸 기준, 글자색을 따름) ----------
const ICON_PATHS = {
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 3.6-6 8-6s8 2 8 6"/>',
  lock: '<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/><path d="M12 15v2"/>',
  train: '<rect x="5" y="3" width="14" height="14" rx="3"/><path d="M5 10h14"/><path d="M9 21l1.5-4M15 21l-1.5-4"/><path d="M9 13.5h.01M15 13.5h.01"/>',
  hourglass: '<path d="M6 3h12M6 21h12"/><path d="M7 3v2.5a5 5 0 0 0 10 0V3"/><path d="M7 21v-2.5a5 5 0 0 1 10 0V21"/>',
  seat: '<path d="M7 4a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v8H7z"/><path d="M4 12h16v4H4z"/><path d="M7 16v5M17 16v5"/>',
  card: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 10h18M7 15h4"/>',
  undo: '<path d="M9 14 4 9l5-5"/><path d="M4 9h11a5 5 0 0 1 0 10h-3"/>',
  ticket: '<path d="M3 7h18v3a2 2 0 0 0 0 4v3H3v-3a2 2 0 0 0 0-4z"/><path d="M14 7v2M14 11v2M14 15v2"/>',
  send: '<path d="M22 2 11 13"/><path d="M22 2l-7 20-4-9-9-4z"/>',
  shield: '<path d="M12 3 4 6v6c0 5 3.4 8 8 9 4.6-1 8-4 8-9V6z"/><path d="m9 12 2 2 4-4"/>',
  monitor: '<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/>',
  database: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5"/><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/>',
  server: '<rect x="3" y="3" width="18" height="8" rx="2"/><rect x="3" y="13" width="18" height="8" rx="2"/><path d="M7 7h.01M7 17h.01"/>',
  flask: '<path d="M9 3h6M10 3v6L4.2 19a1.4 1.4 0 0 0 1.2 2h13.2a1.4 1.4 0 0 0 1.2-2L14 9V3"/><path d="M7 15h10"/>',
  pin: '<path d="M12 22s7-6.5 7-12a7 7 0 0 0-14 0c0 5.5 7 12 7 12z"/><circle cx="12" cy="10" r="2.5"/>',
  chart: '<path d="M3 21h18"/><path d="M6 17v-6M11 17V5M16 17v-9M21 17v-3"/>',
  bell: '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 8 3 8H3s3-1 3-8"/><path d="M10 20a2 2 0 0 0 4 0"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M4.9 4.9 7 7M17 17l2.1 2.1M2 12h3M19 12h3M4.9 19.1 7 17M17 7l2.1-2.1"/>',
  receipt: '<path d="M6 2h12v20l-3-2-3 2-3-2-3 2z"/><path d="M9 7h6M9 11h6M9 15h4"/>',
  map: '<path d="m3 6 6-3 6 3 6-3v15l-6 3-6-3-6 3z"/><path d="M9 3v15M15 6v15"/>',
  bot: '<rect x="4" y="8" width="16" height="12" rx="3"/><path d="M12 8V4"/><path d="M9 13h.01M15 13h.01M9 17h6"/>',
  phone: '<rect x="7" y="2" width="10" height="20" rx="2"/><path d="M11 18h2"/>',
  cpu: '<rect x="6" y="6" width="12" height="12" rx="2"/><path d="M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4"/>',
  rocket: '<path d="M9 15l-3-3c2-6 7-10 14-10 0 7-4 12-10 14z"/><path d="M5 15c-1.5 1.5-2 5-2 5s3.5-.5 5-2"/><circle cx="15" cy="9" r="1.5"/>',
  // 버튼용
  pencil: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="m13 7 4 4"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  link: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>',
  reset: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/>',
  x: '<path d="M6 6l12 12M18 6 6 18"/>',
  save: '<path d="M5 3h11l3 3v15H5z"/><path d="M8 3v5h7V3"/><path d="M8 21v-7h8v7"/>',
  trash: '<path d="M4 7h16"/><path d="M6 7l1 14h10l1-14"/><path d="M9 7V4h6v3"/><path d="M10 11v6M14 11v6"/>',
  swap: '<path d="M7 4 3 8l4 4"/><path d="M3 8h14"/><path d="m17 20 4-4-4-4"/><path d="M21 16H7"/>',
  msg: '<path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z"/>',
  check: '<path d="m5 12 5 5 9-10"/>',
};
// 예전에 이모지로 저장된 칸도 같은 아이콘으로
const EMOJI_ICON = { '🙋': 'user', '🔐': 'lock', '🚆': 'train', '⏳': 'hourglass', '💺': 'seat', '💳': 'card', '↩️': 'undo', '🎫': 'ticket', '📨': 'send', '🛡️': 'shield', '🖥️': 'monitor', '🗂️': 'database', '🧱': 'server', '🧪': 'flask', '📌': 'pin', '📊': 'chart', '🔔': 'bell', '⚙️': 'gear', '🧾': 'receipt', '🗺️': 'map', '🤖': 'bot', '📱': 'phone', '🧠': 'cpu', '🚀': 'rocket' };
const svgI = (name, size = 18) => {
  const d = ICON_PATHS[EMOJI_ICON[name] || name];
  return d ? `<svg class="ic" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${d}</svg>` : '';
};
const nodeIcon = (name, size = 20) => `<span class="fl-icon">${svgI(name || 'pin', size)}</span>`;

// ---------- 로드맵 보드 (기능 영역별 칸, 카드 안에 A~E 파트별 했다/안 했다, 개발자에게 남길 말) ----------
const RM_PARTS = ['A', 'B', 'C', 'D', 'E'];
const RM_FIELD = { title: '제목', code: '코드', area: '영역', blocked: '막힘', prio: '우선', week: '주차', due: '마감일', note: '설명', deps: '선행 작업' };
const RM = { data: null, rev: 0, timer: null, stale: false, f: store.get('rm-f2', { q: '', part: '', prio: '', hideDone: false }) };
const rmById = () => new Map((RM.data ? RM.data.items : []).map((x) => [x.id, x]));
const areaLabel = (k) => { const a = RM.data.areas.find((x) => x.k === k); return a ? a.label : k; };
const partOwner = (p) => RM.data.parts[p].owner;
const partColor = (p) => colorOf(partOwner(p));
const partName = (p) => `${p} ${S.members[partOwner(p)] || ''}`;
const myPart = () => RM_PARTS.find((p) => partOwner(p) === S.me) || '';
// "내 파트만" 켜짐: 내 칸이 있는 카드만 진하게, 나머지는 흐리게
const mineOn = () => !!(RM.f.mine && myPart());
// 할 순서: 안 끝난 카드를 선행 작업 따라 단계로 (서버 lib/roadmap.js stagesOf와 같은 규칙)
function rmStages() {
  if (RM.stages && RM.stages.data === RM.data) return RM.stages.map;
  const by = rmById(); const memo = new Map();
  const stage = (it, seen) => {
    if (isDone(it)) return 0;
    if (memo.has(it.id)) return memo.get(it.id);
    if (seen.has(it.id)) return 1;
    seen.add(it.id);
    let s = 1;
    (it.deps || []).forEach((d) => { const x = by.get(d); if (x && !isDone(x)) s = Math.max(s, stage(x, seen) + 1); });
    seen.delete(it.id); memo.set(it.id, s);
    return s;
  };
  const map = new Map(RM.data.items.map((x) => [x.id, stage(x, new Set())]));
  RM.stages = { data: RM.data, map };
  return map;
}
const mineBtn = () => (S.me && myPart() ? `<button class="btn sm plain fl-tb rm-minebtn${mineOn() ? ' on' : ''}" id="rmMine" style="--pc:${partColor(myPart())}" aria-pressed="${mineOn()}" title="내 파트(${myPart()}) 카드만 진하게 보기">${svgI('user', 16)} 내 파트만${mineOn() ? ' 켜짐' : ''}</button>` : '');
const askBtn = (area) => (S.ai ? `<button class="btn sm plain fl-tb" data-rmask="${area || ''}" title="${area ? '이 칸' : '로드맵'} 현황을 보고 AI가 답해요">${svgI('bot', 16)} AI에게 묻기</button>` : '');
const mineOf = (it) => { const p = myPart(); return p && it.roles && it.roles[p] ? (it.roles[p].done ? 'done' : 'todo') : ''; };
const rmWho = (n) => (n === 'seed' ? '기능설계서' : esc(S.members[n] || n || ''));
const ago = (t) => {
  const s = (Date.now() - t) / 1000;
  if (s < 60) return '방금'; if (s < 3600) return `${Math.floor(s / 60)}분 전`; if (s < 86400) return `${Math.floor(s / 3600)}시간 전`;
  return kst(new Date(t).toISOString());
};
const rolesOf = (it) => RM_PARTS.filter((p) => it.roles && it.roles[p]);
const doneN = (it) => rolesOf(it).filter((p) => it.roles[p].done).length;
const isDone = (it) => rolesOf(it).length > 0 && doneN(it) === rolesOf(it).length;
const stateOf = (it) => (isDone(it) ? 'done' : it.blocked ? 'blocked' : doneN(it) ? 'doing' : 'todo');
const ST_LABEL = { todo: '안 함', doing: '하는 중', blocked: '막힘', done: '다 함' };
const stPill = (it) => { const s = stateOf(it); return `<span class="rm-st st-${s}">${ST_LABEL[s]}</span>`; };
const waitingOn = (it, by) => (it.deps || []).map((d) => by.get(d)).filter((d) => d && !isDone(d));
const sortItems = (a, b) => (a.week || 9) - (b.week || 9) || (a.prio < b.prio ? -1 : a.prio > b.prio ? 1 : 0) || (a.order || 0) - (b.order || 0);
const roleTip = (it, p) => { const r = it.roles[p]; return r.done ? `${partName(p)}: 했음 (${S.members[r.by] || r.by || ''} · ${kst(new Date(r.at || it.updatedAt).toISOString())})` : `${partName(p)}: 아직 안 함`; };

async function rmLoad() {
  const d = await api('/roadmap' + qs({}), { nocache: true });
  RM.data = d; RM.rev = d.rev; RM.stale = false; RM.at = Date.now();
  return d;
}
function rmPoll() {
  clearInterval(RM.timer);
  RM.timer = setInterval(async () => {
    if (!/^#\/roadmap/.test(location.hash)) { clearInterval(RM.timer); return; }
    if (document.hidden) return;
    try {
      const { rev } = await api('/roadmap/rev' + qs({ t: Date.now() }));
      if (rev === RM.rev) return;
      if (!$('#modalBack').hidden || RM.edit) { RM.stale = true; return; } // 편집 중이면 닫은 뒤에
      await rmLoad(); rmRender();
    } catch {}
  }, 20000);
}

function rmFiltered() {
  const f = RM.f; const q = f.q.trim().toLowerCase();
  return RM.data.items.filter((it) => (!f.prio || it.prio === f.prio) && (!f.part || (it.roles && it.roles[f.part]))
    && (!f.hideDone || !isDone(it)) && (!q || `${it.code} ${it.title} ${it.note}`.toLowerCase().includes(q)));
}

// 파트 칩: 누르면 그 파트 칸을 했다/안 했다로 바꾼다
const roleChip = (it, p, big) => {
  const r = it.roles[p];
  return `<button class="rm-chip${r.done ? ' on' : ''}${big ? ' big' : ''}" data-chk="${it.id}" data-p="${p}" style="--pc:${partColor(p)}" title="${esc(roleTip(it, p))}"${S.me ? '' : ' disabled'}>
    <span class="rm-chip-box">${r.done ? svgI('check', 12) : ''}</span>${p}${big ? ` <span class="rm-chip-name">${esc(S.members[partOwner(p)])}</span>` : ''}</button>`;
};

function rmCard(it, by, opt = {}) {
  const wait = waitingOn(it, by);
  const st = stateOf(it);
  const n = rolesOf(it).length, d = doneN(it);
  const mm = mineOn() ? mineOf(it) : null;
  const cls = mm === null ? '' : mm ? ` mine m-${mm}` : ' dim';
  return `<div class="rm-card st-${st}${cls}" data-open="${it.id}" tabindex="0" role="button"${mm ? ` style="--pc:${partColor(myPart())}"` : ''}>
    ${mm ? `<div class="rm-mytag m-${mm}">${mm === 'done' ? `${svgI('check', 13)} 내 칸 했음` : '내 칸 아직'}</div>` : ''}
    <div class="rm-card-top">${opt.stage ? `<span class="rm-stage">${opt.stage}단계</span>` : ''}${it.code ? `<span class="rm-code">${esc(it.code)}</span>` : ''}${it.prio !== 'P1' ? `<span class="badge">${it.prio}</span>` : ''}${it.blocked && st !== 'done' ? '<span class="rm-st st-blocked">막힘</span>' : ''}${it.week ? `<span class="muted">${it.week}주차</span>` : ''}<span class="rm-frac">${d}/${n}</span></div>
    <div class="rm-title">${esc(it.title)}</div>
    <div class="rm-chips">${rolesOf(it).map((p) => roleChip(it, p)).join('')}</div>
    ${(it.notes || wait.length) ? `<div class="rm-card-foot">${it.notes ? `<span class="rm-msgs" title="남긴 말">${svgI('msg', 14)} ${it.notes}</span>` : ''}${wait.length && st !== 'done' ? `<span class="rm-wait">기다림: ${wait.slice(0, 2).map((x) => esc(x.code || x.title)).join(', ')}${wait.length > 2 ? ` 외 ${wait.length - 2}` : ''}</span>` : ''}</div>` : ''}
  </div>`;
}

function rmPartStrip() {
  const items = RM.data.items;
  return `<div class="rm-parts${RM.f.part ? ' has-on' : ''}">${RM_PARTS.map((p) => {
    const mine = items.filter((x) => x.roles && x.roles[p]);
    const done = mine.filter((x) => x.roles[p].done).length;
    const pct = mine.length ? Math.round((done / mine.length) * 100) : 0;
    const msgs = (RM.data.msgs || []).filter((m) => m.to === p).length;
    return `<button class="rm-part${RM.f.part === p ? ' on' : ''}" data-fpart="${p}" style="--pc:${partColor(p)}" title="누르면 ${esc(partName(p))} 카드만 보여요" aria-pressed="${RM.f.part === p}">
      <span class="rm-part-head"><b>${esc(partName(p))}</b><span class="muted">${esc(RM.data.parts[p].label)}</span></span>
      <span class="rm-bar"><i style="width:${pct}%"></i></span>
      <span class="rm-part-nums"><b>${pct}%</b><span>${done}/${mine.length} 했음</span>${RM.f.part === p ? '<span class="rm-part-on" title="다시 누르면 풀려요">선택됨</span>' : ''}${msgs ? `<span class="rm-to">${svgI('msg', 14)} 받은 말 ${msgs}</span>` : ''}</span>
    </button>`;
  }).join('')}</div>`;
}

function rmBoard(list, by) {
  return `<div class="rm-areas">${RM.data.areas.map(({ k, label }) => {
    const all = RM.data.items.filter((x) => x.area === k);
    const rank = (x) => ({ todo: 0, done: 1, '': 2 })[mineOf(x)];
    const col = list.filter((x) => x.area === k).sort((x, y) => (mineOn() ? rank(x) - rank(y) : 0) || sortItems(x, y));
    if (!all.length && k !== 'etc') return '';
    if (!col.length && (RM.f.part || RM.f.q || RM.f.prio || RM.f.hideDone) && !S.me) return '';
    const done = all.filter(isDone).length;
    const pct = all.length ? Math.round((done / all.length) * 100) : 0;
    return `<section class="rm-area" data-area="${k}">
      <div class="rm-area-head">${nodeIcon(areaIcon(k), 17)}<b>${esc(label)}</b><span class="muted">${done}/${all.length}</span>${S.me ? `<button class="btn sm plain" data-add="${k}" title="${esc(label)}에 할 일 추가">${svgI('plus', 14)} 할 일</button>` : ''}</div>
      <span class="rm-bar area"><i style="width:${pct}%"></i></span>
      <div class="rm-cards">${col.map((x) => rmCard(x, by)).join('') || '<p class="muted rm-none">조건에 맞는 카드가 없어요.</p>'}</div>
    </section>`;
  }).join('')}</div>`;
}

function rmOrderView(by) {
  const st = rmStages();
  const open = RM.data.items.filter((x) => !isDone(x));
  const pool = mineOn() ? open.filter((x) => mineOf(x)) : open;
  const n = Math.max(0, ...pool.map((x) => st.get(x.id)));
  const done = RM.data.items.filter(isDone);
  const col = (s) => pool.filter((x) => st.get(x.id) === s).sort((x, y) => sortItems(x, y));
  const myNext = myPart() ? open.filter((x) => mineOf(x) === 'todo' && st.get(x.id) === 1).sort(sortItems) : [];
  return `<div class="rm-order-help">
      <p><b>먼저 끝나야 하는 일</b>을 따라 단계로 나눴어요. <b>1단계</b>는 기다릴 게 없어서 지금 바로 시작할 수 있는 일이고, 2단계는 그 카드가 기다리는 1단계 일이 끝나야 시작할 수 있어요. 카드를 끝내면 뒤 단계가 앞으로 당겨져요.${mineOn() ? ' 지금은 <b>내 파트 카드만</b> 보여요.' : ''}</p>
      ${myPart() ? `<p class="rm-order-next" style="--pc:${partColor(myPart())}"><b>${esc(partName(myPart()))}</b> 지금 바로 할 수 있는 내 일: ${myNext.length ? myNext.slice(0, 6).map((x) => `<button class="rm-link sm" data-open="${x.id}">${esc(x.code || x.title)}</button>`).join('') : '<span class="muted">없음 (앞 단계 일을 기다리는 중이거나 다 했어요)</span>'}</p>` : ''}
    </div>
    <div class="rm-order">${Array.from({ length: n }, (_, i) => i + 1).map((s) => { const c = col(s); return `<section class="rm-stagecol">
      <div class="rm-stagecol-head"><b>${s}단계</b><span class="muted">${s === 1 ? '지금 시작 가능' : `${s - 1}단계가 끝나면`} · ${c.length}개</span></div>
      <div class="rm-cards">${c.map((x) => rmCard(x, by)).join('')}</div></section>`; }).join('') || '<p class="empty">남은 일이 없어요.</p>'}</div>
    ${done.length ? `<details class="rm-more" style="margin-top:12px"><summary>다 한 일 ${done.length}개</summary><div class="rm-donegrid">${done.map((x) => rmCard(x, by)).join('')}</div></details>` : ''}`;
}

function rmPartsView(by) {
  // 파트마다: 아직 안 한 칸, 받은 말
  return `<div class="rm-partcols">${RM_PARTS.map((p) => {
    const todo = RM.data.items.filter((x) => x.roles && x.roles[p] && !x.roles[p].done).sort(sortItems);
    const msgs = (RM.data.msgs || []).filter((m) => m.to === p).slice(0, 8);
    return `<section class="rm-partcol${mineOn() ? (p === myPart() ? ' mine' : ' dim') : ''}" style="--pc:${partColor(p)}"><div class="rm-partcol-head"><b>${esc(partName(p))}</b> <span class="muted">남은 ${todo.length}개</span></div>
      ${msgs.length ? `<div class="rm-inbox"><div class="rm-rel-h">${svgI('msg', 14)} 받은 말</div>${msgs.map((m) => `<button class="rm-msg" data-open="${m.item}"><span class="muted">${rmWho(m.who)} · ${ago(m.at)} · ${esc(m.code || m.title || '')}</span><span>${esc((m.text || '(사진)').slice(0, 120))}</span></button>`).join('')}</div>` : ''}
      ${todo.map((x) => { const w = waitingOn(x, by); return `<div class="rm-todo" data-open="${x.id}">${roleChip(x, p)}<span class="rm-todo-t"><b>${esc(x.code)}</b> ${esc(x.title)}<small class="muted">${esc(areaLabel(x.area))}${x.week ? ` · ${x.week}주차` : ''}${w.length ? ` · 기다림 ${w.length}` : ''}</small></span></div>`; }).join('') || '<p class="muted">다 했어요.</p>'}
    </section>`;
  }).join('')}</div>`;
}

function rmDepsView(by) {
  // 행 = 기다리는 파트, 열 = 먼저 끝내야 하는 파트. 칸 숫자 = 아직 안 끝난 연결 수
  const edges = [];
  RM.data.items.forEach((it) => {
    if (isDone(it)) return;
    waitingOn(it, by).forEach((d) => {
      const need = rolesOf(d).filter((q) => !d.roles[q].done);
      rolesOf(it).filter((p) => !it.roles[p].done).forEach((p) => need.forEach((q) => { if (p !== q) edges.push({ it, d, p, q }); }));
    });
  });
  const cnt = (a, b) => new Set(edges.filter((e) => e.p === a && e.q === b).map((e) => e.it.id + e.d.id)).size;
  const groups = {};
  edges.forEach((e) => { const g = (groups[e.d.id] = groups[e.d.id] || { d: e.d, list: new Map(), q: new Set(), p: new Set() }); g.list.set(e.it.id, e.it); g.q.add(e.q); g.p.add(e.p); });
  const hot = Object.values(groups).sort((a, b) => b.list.size - a.list.size);
  const ready = RM_PARTS.map((p) => [p, RM.data.items.filter((x) => x.roles && x.roles[p] && !x.roles[p].done && !waitingOn(x, by).length).sort(sortItems)]);
  return `<p class="muted" style="margin:0 0 10px">다른 파트가 먼저 끝내야 시작할 수 있는 카드 수예요. 숫자를 누르면 아래 목록이 그 칸만 보여요.</p>
  <div class="tbl-wrap"><table class="rm-matrix"><thead><tr><th>기다리는 파트 ↓ / 먼저 끝낼 파트 →</th>${RM_PARTS.map((p) => `<th style="color:${partColor(p)}">${esc(partName(p))}</th>`).join('')}</tr></thead>
  <tbody>${RM_PARTS.map((a) => `<tr><th style="color:${partColor(a)}">${esc(partName(a))}</th>${RM_PARTS.map((b) => {
    const c = a === b ? 0 : cnt(a, b);
    return `<td>${a === b ? '<span class="muted">-</span>' : c ? `<button class="rm-cell" data-a="${a}" data-b="${b}" style="--heat:${Math.min(1, c / 6)}">${c}</button>` : '<span class="muted">0</span>'}</td>`;
  }).join('')}</tr>`).join('')}</tbody></table></div>
  <div class="section-title">먼저 끝내면 여러 카드가 풀리는 작업</div>
  <div>${hot.length ? hot.map((g) => `<div class="rm-edge" data-q="${[...g.q].join(' ')}" data-p="${[...g.p].join(' ')}">
      <button class="rm-link" data-open="${g.d.id}"><b>${esc(g.d.code)}</b> ${esc(g.d.title)} ${stPill(g.d)}</button>
      <span class="muted">→ 기다리는 ${g.list.size}개:</span>
      ${[...g.list.values()].map((x) => `<button class="rm-link sm" data-open="${x.id}" data-parts="${rolesOf(x).join(' ')}">${esc(x.code || x.title)}</button>`).join('')}
    </div>`).join('') : '<p class="empty">다른 파트를 기다리는 카드가 없어요.</p>'}</div>
  <div class="section-title">지금 바로 할 수 있는 것 <span class="muted" style="font-weight:500">(내 칸이 안 됐고 선행 작업이 다 끝남)</span></div>
  <div class="rm-ready-grid">${ready.map(([p, list]) => `<div><div class="rm-partcol-head" style="--pc:${partColor(p)}"><b>${esc(partName(p))}</b> <span class="muted">${list.length}</span></div>
    ${list.slice(0, 8).map((x) => `<button class="rm-link" data-open="${x.id}"><b>${esc(x.code)}</b> ${esc(x.title)}</button>`).join('') || '<p class="muted" style="margin:4px 0">없음</p>'}${list.length > 8 ? `<p class="muted" style="margin:4px 0">외 ${list.length - 8}개</p>` : ''}</div>`).join('')}</div>`;
}

// 관리자: 기록 고치기·지우기 버튼 연결. after = 끝난 뒤 다시 그리기
function rmLogBind(root, after) {
  if (!S.admin) return;
  const find = (seq) => (RM.data.log || []).find((x) => x.seq === seq) || (RM.lastItemLog || []).find((x) => x.seq === seq);
  $$('[data-ldel]', root).forEach((b) => {
    let armed = false, t;
    b.addEventListener('click', async (ev) => {
      ev.stopPropagation();
      if (!armed) { armed = true; b.classList.add('armed'); b.title = '한 번 더 누르면 지워요'; toast('한 번 더 누르면 이 기록을 지워요.'); t = setTimeout(() => { armed = false; b.classList.remove('armed'); }, 4000); return; }
      clearTimeout(t); b.disabled = true;
      try { await api('/roadmap/log-delete', { body: { seq: Number(b.dataset.ldel) } }); toast('기록을 지웠어요.'); await rmLoad(); after ? after() : rmRender(); } catch (x) { toast(x.message); b.disabled = false; }
    });
  });
  $$('[data-ledit]', root).forEach((b) => b.addEventListener('click', (ev) => {
    ev.stopPropagation();
    const seq = Number(b.dataset.ledit); const e = find(seq);
    const isNote = e && e.act === 'note';
    const line = root.querySelector(`.rm-log[data-seq="${seq}"] .rm-log-what`);
    const cur = e ? (isNote ? e.text || '' : e.memo || (line ? line.textContent.trim() : '')) : '';
    const back = RM.modal;
    const m = openModal(`<h2>기록 고치기</h2><p class="muted">${isNote ? '남긴 말 본문을 고쳐요.' : '최근 변경에 보이는 설명을 이 글로 바꿔요. 비우면 원래 설명으로 돌아가요.'}</p>
      <textarea class="rm-nowta" id="logTa" rows="4" maxlength="2000">${esc(cur)}</textarea><p class="err" id="logErr"></p>
      <div class="row"><button class="btn plain" data-x>취소</button><button class="btn" id="logGo">저장</button></div>`);
    const reopen = () => { if (back && back.kind === 'card') rmOpen(back.id); else if (back && back.kind === 'area') rmArea(back.k); else { closeModal(); } };
    $('[data-x]', m).addEventListener('click', reopen);
    $('#logGo', m).addEventListener('click', async () => {
      try { await api('/roadmap/log-edit', { body: { seq, text: $('#logTa', m).value } }); toast('고쳤어요.'); await rmLoad(); rmRender(); reopen(); } catch (x) { $('#logErr', m).textContent = x.message; }
    });
  }));
}

function rmLogLine(e, by, withTitle = true) {
  const it = by.get(e.item);
  const name = withTitle ? (it ? `<button class="rm-link sm" data-open="${it.id}">${esc(e.code || '')} ${esc(e.title || '')}</button>` : `<b>${esc(e.code || '')} ${esc(e.title || '')}</b>`) : '';
  let what = '';
  if (e.memo) what = `${name} <span class="rm-note-text">${esc(e.memo)}</span> <span class="muted">(수정됨)</span>`;
  else if (e.act === 'seed') what = esc(e.text);
  else if (e.act === 'add') what = `${name} 추가`;
  else if (e.act === 'delete') what = `${name} 삭제`;
  else if (e.act === 'flow') what = `흐름도 수정${(e.added || []).length ? ` · 칸 추가: ${esc(e.added.join(', '))}` : ''}${(e.removed || []).length ? ` · 칸 삭제: ${esc(e.removed.join(', '))}` : ''}${e.edges ? ` · 연결선 ${e.edges[0]}→${e.edges[1]}개` : ''}`;
  else if (e.act === 'now') what = `<b>${esc(e.label || '')}</b> · ${esc(partName(e.part))} 지금 하는 일${e.text ? `: <span class="rm-note-text">${esc(e.text)}</span>` : ' 지움'}`;
  else if (e.act === 'note') what = `${name} ${e.to ? `<b>→ ${esc(partName(e.to))}</b>에게` : ''} 남긴 말${e.text ? `: <span class="rm-note-text">${esc(e.text.length > 140 ? e.text.slice(0, 140) + '…' : e.text)}</span>` : ''}${e.img ? ' <span class="badge">사진</span>' : ''}`;
  else {
    const ch = (e.changes || []).map(([f, a, b]) => {
      if (f === 'check') return b ? `<span class="rm-chg ok">${svgI('check', 13)} ${esc(a)} 했음</span>` : `<span class="rm-chg">${esc(a)} 체크 풂</span>`;
      if (f === 'role+') return `${esc(a)} 파트 추가`;
      if (f === 'role-') return `${esc(a)} 파트 뺌`;
      if (f === 'status') return `상태 ${esc(a)} → ${esc(b)}`;
      if (f === 'note') return '설명 고침';
      if (f === 'deps') return `선행 작업 ${a}→${b}개`;
      if (f === 'blocked') return b ? '막힘 표시' : '막힘 풂';
      if (f === 'area') return `영역 ${esc(areaLabel(a))} → ${esc(areaLabel(b))}`;
      if (f === 'week') return `주차 ${a || '미정'} → ${b || '미정'}`;
      if (f === 'owner' || f === 'doneBy') return `${f === 'owner' ? '담당' : '완료한 사람'} ${esc(S.members[a] || a || '없음')} → ${esc(S.members[b] || b || '없음')}`;
      if (f === 'with') return '함께 하는 파트 바뀜';
      return `${RM_FIELD[f] || f} ${esc(String(a || '없음'))} → ${esc(String(b || '없음'))}`;
    }).join(', ');
    what = `${name} ${ch}`;
  }
  const adm = S.admin ? `<span class="rm-log-adm"><button class="fl-ibtn" data-ledit="${e.seq}" title="이 기록 고치기" aria-label="이 기록 고치기">${svgI('pencil', 14)}</button><button class="fl-ibtn del" data-ldel="${e.seq}" title="이 기록 지우기 (두 번 누르기)" aria-label="이 기록 지우기">${svgI('trash', 14)}</button></span>` : '';
  return `<div class="rm-log${S.admin ? ' adm' : ''}" data-seq="${e.seq}"><span class="rm-log-who"><span class="dot" style="background:${colorOf(e.who)}"></span> ${rmWho(e.who)}</span><span class="rm-log-what">${what}</span><span class="muted rm-log-at" title="${kst(new Date(e.at).toISOString(), true)}">${ago(e.at)}</span>${adm}</div>`;
}

// ---------- 흐름도 (예매 흐름 위에 기능 영역을 놓고, 칸마다 맡은 사람·진행률·지금 하는 일) ----------
// 모양은 서버의 flow(노드 위치·이름·아이콘, 연결선). 로그인하면 "다이어그램 수정"으로 끌어 옮기고 추가·삭제한다
const FLOW_ICONS = ['user', 'lock', 'train', 'hourglass', 'seat', 'card', 'undo', 'ticket', 'send', 'shield', 'monitor', 'database', 'server', 'flask', 'pin', 'chart', 'bell', 'gear', 'receipt', 'map', 'bot', 'phone', 'cpu', 'rocket'];
const curFlow = () => (RM.edit ? RM.edit.flow : RM.data.flow);
function areaParts(k) {
  // 이 영역 카드에 칸이 있는 파트 = 맡은 사람 (역할 분담 그대로)
  const list = RM.data.items.filter((x) => x.area === k);
  return RM_PARTS.map((p) => {
    const mine = list.filter((x) => x.roles && x.roles[p]);
    return { p, total: mine.length, done: mine.filter((x) => x.roles[p].done).length };
  }).filter((x) => x.total).sort((a, b) => b.total - a.total);
}
const nowOf = (k, p) => (RM.data.now || []).find((n) => n.area === k && n.part === p);
const areaIcon = (k) => { const n = RM.data.flow.nodes.find((x) => x.k === k); const a = RM.data.areas.find((x) => x.k === k); return (n && n.icon) || (a && a.icon) || ''; };

function flowNode(n) {
  const ed = !!RM.edit;
  const tools = ed ? `<span class="fl-tools"><button class="fl-ibtn" data-fedit="${n.k}" title="이름·아이콘 바꾸기" aria-label="이름·아이콘 바꾸기">${svgI('pencil', 15)}</button><button class="fl-ibtn del" data-fdel="${n.k}" title="칸 지우기" aria-label="칸 지우기">${svgI('trash', 15)}</button></span>` : '';
  const pos = `style="left:${n.x}px;top:${n.y}px"`;
  if (n.kind === 'start') return `<div class="fl-node fl-user" data-k="${n.k}" ${pos}>${nodeIcon(n.icon, 18)}${esc(n.label)}${tools}</div>`;
  const items = RM.data.items.filter((it) => it.area === n.k);
  const done = items.filter(isDone).length;
  const pct = items.length ? Math.round((done / items.length) * 100) : 0;
  const parts = areaParts(n.k);
  const blocked = items.filter((it) => it.blocked && !isDone(it)).length;
  const msgs = items.reduce((s, it) => s + (it.notes || 0), 0);
  const mi = mineOn() && !ed ? items.filter((it) => mineOf(it)) : null;
  const mtodo = mi ? mi.filter((it) => mineOf(it) === 'todo').length : 0;
  return `<div class="fl-node${pct === 100 && items.length ? ' all' : ''}${mi ? (mi.length ? ' mine' : ' dim') : ''}" data-k="${n.k}" ${pos} tabindex="0" role="button" title="${ed ? '끌어서 옮기기' : `눌러서 ${esc(n.label)} 열기`}">
    <div class="fl-head">${nodeIcon(n.icon)}<b>${esc(n.label)}</b>${mi && mi.length ? `<span class="fl-mine${mtodo ? '' : ' m-done'}" style="--pc:${partColor(myPart())}" title="내 칸 ${mi.length}개 중 ${mi.length - mtodo}개 했음">${mtodo ? `내 것 ${mtodo}개 남음` : `${svgI('check', 13)} 내 것 다 함`}</span>` : `<span class="fl-pct">${done}/${items.length}</span>`}${tools}</div>
    <span class="rm-bar area"><i style="width:${pct}%"></i></span>
    <div class="fl-people">${parts.map(({ p, total, done: d }) => { const nw = nowOf(n.k, p); return `<button class="fl-who${mi && p === myPart() ? ' me' : ''}" data-now="${n.k}" data-p="${p}" style="--pc:${partColor(p)}" title="${esc(partName(p))}: ${d}/${total}${nw ? ` · 지금: ${esc(nw.text)}` : ''} (누르면 지금 하는 일 적기)"${ed ? ' disabled' : ''}>
      <span class="fl-who-name"><b>${p}</b> ${esc(S.members[partOwner(p)])}</span><span class="fl-who-n">${d}/${total}</span>
      ${nw ? `<span class="fl-now">${esc(nw.text)}</span>` : ''}</button>`; }).join('') || '<span class="muted" style="font-size:12.5px">맡은 사람 없음 · 칸을 눌러 할 일을 추가해요</span>'}</div>
    ${blocked || msgs ? `<div class="fl-foot">${blocked ? `<span class="rm-st st-blocked">막힘 ${blocked}</span>` : ''}${msgs ? `<span class="rm-msgs">${svgI('msg', 14)} ${msgs}</span>` : ''}</div>` : ''}
  </div>`;
}

function rmFlow() {
  const f = curFlow();
  const ed = RM.edit;
  const bar = S.me ? (ed
    ? `<div class="fl-bar editing"><span class="fl-bar-t">${svgI('pencil', 16)} 다이어그램 수정 중</span>
        <button class="btn sm plain fl-tb" data-fact="add">${svgI('plus', 16)} 칸 추가</button>
        <button class="btn sm plain fl-tb${ed.link ? ' on' : ''}" data-fact="link">${svgI('link', 16)} ${ed.link ? (ed.link.a ? '이을 칸을 누르세요' : '시작 칸을 누르세요') : '연결선 추가'}</button>
        <span class="muted fl-hint">칸은 끌어서 옮기고, 선 위 글을 누르면 고치거나 지워요.</span>
        <span style="flex:1"></span>
        <button class="btn sm plain fl-tb" data-fact="reset" title="처음 모양으로">${svgI('reset', 16)} 처음 모양</button>
        <button class="btn sm plain fl-tb" data-fact="cancel">${svgI('x', 16)} 취소</button>
        <button class="btn sm fl-tb" data-fact="save">${svgI('save', 16)} 저장</button></div>`
    : `<div class="fl-bar"><span class="muted fl-bar-txt"><b>이름</b>을 누르면 지금 하는 일을 적고, <b>칸</b>을 누르면 할 일을 보고 추가해요.</span>
        <span class="fl-bar-acts">${askBtn('')}${mineBtn()}<button class="btn sm plain fl-tb" data-fact="edit">${svgI('pencil', 16)} 다이어그램 수정</button></span></div>`)
    : `<div class="fl-bar"><span class="muted fl-bar-txt">칸을 누르면 그 기능의 할 일이 보여요. 로그인하면 지금 하는 일을 적고 다이어그램도 고칠 수 있어요.</span><span class="fl-bar-acts">${askBtn('')}</span></div>`;
  return `${bar}<div class="fl-wrap"><div class="fl${ed ? ' editing' : ''}${ed && ed.link ? ' linking' : ''}" id="flow">
    <svg class="fl-svg" id="flowSvg" aria-hidden="true"></svg>${f.nodes.map(flowNode).join('')}<div id="flowLabs"></div></div></div>
    ${RM.data.flow.by ? `<p class="muted" style="font-size:12px;margin:6px 0 0">다이어그램 마지막 수정: ${rmWho(RM.data.flow.by)} · ${ago(RM.data.flow.at)}</p>` : ''}`;
}

// 칸 위치를 재서 선을 잇는다. 선 방향은 두 칸의 위치를 보고 정한다 (위아래로 멀면 아래→위, 옆으로 멀면 옆→옆)
function drawFlow() {
  const fl = $('#flow'); const svg = $('#flowSvg');
  if (!fl || !svg) return;
  const mobile = getComputedStyle(svg).display === 'none';
  const R = {};
  $$('.fl-node', fl).forEach((n) => { R[n.dataset.k] = { x: n.offsetLeft, y: n.offsetTop, w: n.offsetWidth, h: n.offsetHeight }; });
  if (mobile) { fl.style.height = ''; $('#flowLabs').innerHTML = ''; return; }
  const vals = Object.values(R);
  const h = Math.max(300, ...vals.map((r) => r.y + r.h)) + 30;
  const w = Math.max(1000, ...vals.map((r) => r.x + r.w)) + 20;
  fl.style.height = h + 'px'; fl.style.width = w + 'px';
  svg.setAttribute('viewBox', `0 0 ${w} ${h}`); svg.setAttribute('width', w); svg.setAttribute('height', h);
  const side = (A, B) => {
    const ax = A.x + A.w / 2, ay = A.y + A.h / 2, bx = B.x + B.w / 2, by = B.y + B.h / 2;
    const gapY = B.y > A.y + A.h ? B.y - (A.y + A.h) : A.y > B.y + B.h ? A.y - (B.y + B.h) : -1;
    if (gapY > 20 && Math.abs(by - ay) > Math.abs(bx - ax) * 0.35) return by > ay ? ['b', 't'] : ['t', 'b'];
    return bx > ax ? ['r', 'l'] : ['l', 'r'];
  };
  const pt = (r, s) => (s === 't' ? [r.x + r.w / 2, r.y] : s === 'b' ? [r.x + r.w / 2, r.y + r.h] : s === 'l' ? [r.x, r.y + r.h / 2] : [r.x + r.w, r.y + r.h / 2]);
  const dir = { t: [0, -1], b: [0, 1], l: [-1, 0], r: [1, 0] };
  let out = '<defs><marker id="flArrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="var(--muted)"/></marker></defs>';
  let labs = '';
  curFlow().edges.forEach((e, i) => {
    const A = R[e.a], B = R[e.b]; if (!A || !B) return;
    const [sa, sb] = side(A, B);
    const [x1, y1] = pt(A, sa), [x2, y2] = pt(B, sb);
    const d = Math.max(30, Math.min(150, Math.hypot(x2 - x1, y2 - y1) / 2.5));
    const c1 = [x1 + dir[sa][0] * d, y1 + dir[sa][1] * d], c2 = [x2 + dir[sb][0] * d, y2 + dir[sb][1] * d];
    out += `<path d="M${x1},${y1} C${c1[0]},${c1[1]} ${c2[0]},${c2[1]} ${x2},${y2}" class="fl-edge${e.dash ? ' dash' : ''}" marker-end="url(#flArrow)"/>`;
    const mx = (x1 + 3 * c1[0] + 3 * c2[0] + x2) / 8, my = (y1 + 3 * c1[1] + 3 * c2[1] + y2) / 8;
    if (e.label || RM.edit) labs += `<button class="fl-lab${e.label ? '' : ' empty'}" data-edge="${i}" style="left:${mx}px;top:${my}px"${RM.edit ? '' : ' tabindex="-1"'}>${e.label ? esc(e.label) : svgI('pencil', 12)}</button>`;
  });
  svg.innerHTML = out;
  $('#flowLabs').innerHTML = labs;
  if (RM.edit) $$('[data-edge]', fl).forEach((b) => b.addEventListener('click', (ev) => { ev.stopPropagation(); flowEdgeEdit(+b.dataset.edge); }));
}

function bindFlow(root) {
  const ed = RM.edit;
  $$('[data-fact]', root).forEach((b) => b.addEventListener('click', () => flowAct(b.dataset.fact)));
  if (!ed) {
    $$('.fl-node[data-k]', root).forEach((n) => {
      const node = RM.data.flow.nodes.find((x) => x.k === n.dataset.k);
      if (!node || node.kind === 'start') return;
      n.addEventListener('click', () => rmArea(n.dataset.k));
      n.addEventListener('keydown', (e) => { if (e.key === 'Enter' && e.target === n) rmArea(n.dataset.k); });
    });
    $$('[data-now]', root).forEach((b) => b.addEventListener('click', (e) => { e.stopPropagation(); rmNowEdit(b.dataset.now, b.dataset.p); }));
  } else {
    $$('[data-fedit]', root).forEach((b) => b.addEventListener('click', (e) => { e.stopPropagation(); flowNodeEdit(b.dataset.fedit); }));
    $$('[data-fdel]', root).forEach((b) => b.addEventListener('pointerdown', (e) => e.stopPropagation()));
    $$('[data-fedit]', root).forEach((b) => b.addEventListener('pointerdown', (e) => e.stopPropagation()));
    $$('[data-fdel]', root).forEach((b) => b.addEventListener('click', (e) => {
      e.stopPropagation();
      if (!b.classList.contains('armed')) { b.classList.add('armed'); b.title = '한 번 더 누르면 지워요'; toast('한 번 더 누르면 칸을 지워요.'); setTimeout(() => { b.classList.remove('armed'); b.title = '칸 지우기'; }, 4000); return; }
      flowDelNode(b.dataset.fdel);
    }));
    $$('.fl-node[data-k]', root).forEach((n) => flowDrag(n));
  }
  requestAnimationFrame(drawFlow);
  if (!RM.flowResize) { RM.flowResize = true; window.addEventListener('resize', () => { clearTimeout(RM.frt); RM.frt = setTimeout(drawFlow, 120); }); }
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(drawFlow);
}

// ---- 다이어그램 수정 ----
function flowAct(act) {
  if (act === 'edit') { if (!needLogin(() => rmRender())) return; RM.edit = { flow: JSON.parse(JSON.stringify(RM.data.flow)), link: null, v: RM.data.flow.v || 0 }; rmRender(); return; }
  const ed = RM.edit; if (!ed) return;
  if (act === 'cancel') { RM.edit = null; if (RM.stale) rmLoad().then(rmRender); else rmRender(); return; }
  if (act === 'link') { ed.link = ed.link ? null : { a: null }; rmRender(); return; }
  if (act === 'add') { flowNodeEdit(null); return; }
  if (act === 'reset') { flowResetModal(); return; }
  if (act === 'save') flowSave();
}
async function flowSave() {
  const ed = RM.edit;
  try {
    await api('/roadmap/flow', { body: { flow: { nodes: ed.flow.nodes, edges: ed.flow.edges }, v: ed.v } });
    RM.edit = null; await rmLoad(); rmRender(); toast('다이어그램을 저장했어요. 모두에게 같은 모양으로 보여요.');
  } catch (x) {
    if (x.error === 'STALE') { toast(x.message, 6000); await rmLoad(); RM.edit = null; rmRender(); return; }
    toast(x.message, 6000);
  }
}
function flowResetModal() {
  const m = openModal(`<h2>처음 모양으로</h2><p class="muted">칸 위치와 연결선을 처음 만든 모양으로 되돌려요. 직접 추가한 칸은 남기고 맨 아래에 놓아요. 저장을 눌러야 반영돼요.</p>
    <div class="row"><button class="btn plain" data-x>취소</button><button class="btn" id="frGo">되돌리기</button></div>`);
  $('[data-x]', m).addEventListener('click', closeModal);
  $('#frGo', m).addEventListener('click', async () => {
    try {
      const d = await api('/roadmap/flow-default' + qs({}));
      const custom = RM.edit.flow.nodes.filter((n) => !d.flow.nodes.some((x) => x.k === n.k));
      const maxY = Math.max(...d.flow.nodes.map((n) => n.y)) + 220;
      custom.forEach((n, i) => { n.x = 20 + (i % 3) * 360; n.y = maxY + Math.floor(i / 3) * 200; });
      RM.edit.flow = { ...RM.edit.flow, nodes: [...d.flow.nodes, ...custom], edges: d.flow.edges };
      closeModal(); rmRender();
    } catch (x) { toast(x.message); }
  });
}
function flowDelNode(k) {
  const ed = RM.edit;
  const n = ed.flow.nodes.find((x) => x.k === k);
  const cards = RM.data.items.filter((x) => x.area === k).length;
  if (cards) { toast(`'${n.label}' 칸에 할 일 카드 ${cards}장이 있어서 지울 수 없어요. 카드를 다른 칸으로 옮기거나 지운 뒤에 지워 주세요.`, 6000); return; }
  ed.flow.nodes = ed.flow.nodes.filter((x) => x.k !== k);
  ed.flow.edges = ed.flow.edges.filter((e) => e.a !== k && e.b !== k);
  rmRender();
}
function flowNodeEdit(k) {
  const ed = RM.edit;
  const n = k ? ed.flow.nodes.find((x) => x.k === k) : null;
  let icon = n ? (EMOJI_ICON[n.icon] || n.icon || 'pin') : 'pin';
  const m = openModal(`<h2>${n ? '칸 고치기' : '칸 추가'}</h2>
    <div class="field"><label>이름</label><input type="text" id="fnLabel" maxlength="30" value="${esc(n ? n.label : '')}" placeholder="예: 알림" autofocus></div>
    <div class="field"><label>아이콘</label><div class="fl-iconpick">${FLOW_ICONS.map((ic) => `<button type="button" class="fl-ipick${ic === icon ? ' on' : ''}" data-ic="${ic}" title="${ic}">${svgI(ic, 20)}</button>`).join('')}</div></div>
    ${n ? '' : '<p class="muted" style="font-size:13px">새 칸은 할 일 카드를 넣을 수 있는 기능 칸이 돼요. 만든 뒤 끌어서 자리를 잡아요.</p>'}
    <p class="err" id="fnErr"></p>
    <div class="row"><button class="btn plain" data-x>취소</button><button class="btn" id="fnGo">${n ? '바꾸기' : '추가'}</button></div>`);
  $('[data-x]', m).addEventListener('click', closeModal);
  $$('.fl-ipick', m).forEach((b) => b.addEventListener('click', () => { icon = b.dataset.ic; $$('.fl-ipick', m).forEach((x) => x.classList.toggle('on', x === b)); }));
  $('#fnGo', m).addEventListener('click', () => {
    const label = $('#fnLabel', m).value.trim();
    if (!label) { $('#fnErr', m).textContent = '이름을 써 주세요.'; return; }
    if (n) { n.label = label; n.icon = icon; }
    else {
      const wrap = $('.fl-wrap');
      const y = Math.max(0, (window.scrollY + 120) - (wrap ? wrap.getBoundingClientRect().top + window.scrollY : 0));
      ed.flow.nodes.push({ k: 'n' + Date.now().toString(36).slice(-6), kind: 'area', label, icon, x: 20 + ((wrap && wrap.scrollLeft) || 0), y: Math.round(y) });
    }
    closeModal(); rmRender();
  });
}
function flowEdgeEdit(i) {
  const ed = RM.edit; const e = ed.flow.edges[i]; if (!e) return;
  const lab = (k) => { const n = ed.flow.nodes.find((x) => x.k === k); return n ? n.label : k; };
  const m = openModal(`<h2>연결선</h2><p class="muted" style="margin:0 0 6px">${esc(lab(e.a))} → ${esc(lab(e.b))}</p>
    <div class="field"><label>선 위 글 <span class="muted">(비워도 돼요)</span></label><input type="text" id="feLabel" maxlength="30" value="${esc(e.label)}" autofocus></div>
    <label class="chk"><input type="checkbox" id="feDash"${e.dash ? ' checked' : ''}> 점선 (곁가지·비동기 흐름)</label>
    <div class="row"><button class="btn sm danger" id="feDel">${svgI('trash', 15)} 선 지우기</button><button class="btn sm plain" id="feRev">${svgI('swap', 15)} 방향 바꾸기</button><span style="flex:1"></span><button class="btn plain" data-x>취소</button><button class="btn" id="feGo">바꾸기</button></div>`);
  $('[data-x]', m).addEventListener('click', closeModal);
  $('#feDel', m).addEventListener('click', () => { ed.flow.edges.splice(i, 1); closeModal(); rmRender(); });
  $('#feRev', m).addEventListener('click', () => { [e.a, e.b] = [e.b, e.a]; closeModal(); rmRender(); });
  $('#feGo', m).addEventListener('click', () => { e.label = $('#feLabel', m).value.trim(); e.dash = $('#feDash', m).checked; closeModal(); rmRender(); });
}
// 끌어서 옮기기 (10px 칸에 맞춤). 연결선 추가 중이면 누른 칸을 고른다
function flowDrag(el) {
  el.addEventListener('pointerdown', (ev) => {
    if (ev.button !== 0) return;
    const ed = RM.edit; const n = ed.flow.nodes.find((x) => x.k === el.dataset.k); if (!n) return;
    if (ed.link) {
      ev.preventDefault();
      if (!ed.link.a) { ed.link.a = n.k; rmRender(); $(`.fl-node[data-k="${n.k}"]`).classList.add('picked'); return; }
      if (ed.link.a !== n.k && !ed.flow.edges.some((e) => e.a === ed.link.a && e.b === n.k)) ed.flow.edges.push({ a: ed.link.a, b: n.k, label: '', dash: false });
      ed.link = null; rmRender(); flowEdgeEdit(ed.flow.edges.length - 1); return;
    }
    ev.preventDefault();
    const sx = ev.clientX, sy = ev.clientY, ox = n.x, oy = n.y;
    el.setPointerCapture(ev.pointerId); el.classList.add('dragging');
    const move = (e) => {
      n.x = Math.max(0, Math.round((ox + e.clientX - sx) / 10) * 10); n.y = Math.max(0, Math.round((oy + e.clientY - sy) / 10) * 10);
      el.style.left = n.x + 'px'; el.style.top = n.y + 'px';
      cancelAnimationFrame(RM.dragRaf); RM.dragRaf = requestAnimationFrame(drawFlow);
    };
    const up = () => { el.classList.remove('dragging'); el.removeEventListener('pointermove', move); el.removeEventListener('pointerup', up); el.removeEventListener('pointercancel', up); drawFlow(); };
    el.addEventListener('pointermove', move); el.addEventListener('pointerup', up); el.addEventListener('pointercancel', up);
  });
}

// 이름을 눌러 "지금 하는 일" 한 줄 적기
function rmNowEdit(k, p, back) {
  if (!needLogin(() => rmNowEdit(k, p, back))) return;
  const n = nowOf(k, p);
  const m = openModal(`<h2>${esc(areaLabel(k))} · ${esc(partName(p))}</h2>
    <p class="muted" style="margin:0 0 8px">지금 이 기능에서 하고 있는 일을 한 줄로 적어요. 흐름도 칸에 바로 보여요. 비우고 저장하면 지워져요.</p>
    ${n ? `<p class="muted" style="font-size:13px;margin:0 0 6px">마지막: ${rmWho(n.who)} · ${ago(n.at)}</p>` : ''}
    <textarea id="nowText" rows="3" maxlength="300" class="rm-nowta" placeholder="예: 입장 토큰 검증 필터 만드는 중, 내일 테스트" autofocus>${esc(n ? n.text : '')}</textarea>
    <p class="err" id="nowErr"></p>
    <div class="row"><button class="btn plain" data-x>취소</button><button class="btn" id="nowGo">저장</button></div>`);
  const close = () => (back ? rmArea(k) : (closeModal(), rmAfterClose()));
  $('[data-x]', m).addEventListener('click', close);
  const ta = $('#nowText', m);
  ta.addEventListener('keydown', (e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) $('#nowGo', m).click(); });
  $('#nowGo', m).addEventListener('click', async () => {
    try {
      await api('/roadmap/now', { body: { area: k, part: p, text: ta.value } });
      await rmLoad(); rmRender(); toast(ta.value.trim() ? '지금 하는 일을 적었어요.' : '지웠어요.');
      if (back) rmArea(k); else closeModal();
    } catch (x) { $('#nowErr', m).textContent = x.message; }
  });
}

// 칸을 눌렀을 때: 맡은 사람별 진행·지금 하는 일 + 그 기능의 할 일 카드
function rmArea(k) {
  RM.modal = { kind: 'area', k };
  const by = rmById();
  const st = rmStages();
  const items = RM.data.items.filter((x) => x.area === k).sort((x, y) => (st.get(x.id) || 99) - (st.get(y.id) || 99) || sortItems(x, y));
  const parts = areaParts(k);
  const done = items.filter(isDone).length;
  const m = openModal(`<div class="rm-detail">
    <div class="rm-detail-head"><span class="muted">기능 영역</span><span class="rm-frac">${done}/${items.length} 끝</span></div>
    <h2 class="fl-h2">${nodeIcon(areaIcon(k), 20)} ${esc(areaLabel(k))}</h2>
    <div class="rm-rel-h">맡은 사람</div>
    <div class="fl-team">${parts.map(({ p, total, done: d }) => { const n = nowOf(k, p); return `<div class="fl-team-row" style="--pc:${partColor(p)}">
      <span class="fl-team-who"><span><b>${p}</b> ${esc(S.members[partOwner(p)])}</span><small class="muted">${d}/${total} 했음</small></span>
      <span class="fl-team-now">${n ? `${esc(n.text)} <small class="muted">${rmWho(n.who)} · ${ago(n.at)}</small>` : '<span class="muted">지금 하는 일 없음</span>'}</span>
      ${S.me ? `<button class="btn sm plain" data-nowp="${p}">${n ? '고치기' : '적기'}</button>` : ''}</div>`; }).join('') || '<p class="muted">아직 맡은 사람이 없어요. 할 일을 추가하면서 파트를 고르면 생겨요.</p>'}</div>
    <div class="fl-cards-head"><span class="rm-rel-h" style="margin:0">할 일 ${items.length}개 <span class="muted" style="font-weight:500">· 할 순서대로</span></span><span class="fl-cards-acts">${askBtn(k)}${S.me ? `<button class="btn sm" data-add="${k}">${svgI('plus', 14)} 할 일 추가</button>` : ''}</span></div>
    <div class="rm-cards">${items.map((x) => rmCard(x, by, { stage: st.get(x.id) })).join('') || '<p class="muted">할 일이 없어요.</p>'}</div>
    <div class="row"><button class="btn plain" data-x>닫기</button></div></div>`, { wide: true });
  $('[data-x]', m).addEventListener('click', () => { RM.modal = null; closeModal(); rmAfterClose(); });
  $$('[data-nowp]', m).forEach((b) => b.addEventListener('click', () => rmNowEdit(k, b.dataset.nowp, true)));
  $$('[data-chk]', m).forEach((b) => b.addEventListener('click', (e) => { e.stopPropagation(); rmCheck(b.dataset.chk, b.dataset.p); }));
  $$('[data-open]', m).forEach((b) => b.addEventListener('click', () => { RM.back = k; rmOpen(b.dataset.open); }));
  $$('[data-add]', m).forEach((b) => b.addEventListener('click', () => { RM.back = k; rmEdit(null, k); }));
  $$('[data-rmask]', m).forEach((b) => b.addEventListener('click', () => rmAsk(k)));
}

// 로드맵 AI 질문: 서버가 path(roadmap 또는 roadmap:칸)를 보고 현황을 붙인다
function rmAsk(k) {
  const label = k ? areaLabel(k) : '';
  const c = newConv(null);
  Object.assign(c, { doc: '로드맵', path: k ? 'roadmap:' + k : 'roadmap', heading: label, selection: k ? `${label} 칸 (할 일·체크·선행 작업·할 순서)` : '로드맵 전체 (할 일·체크·선행 작업·할 순서)', section: '' });
  aiOpen(); aiChat(c, '');
}

async function pageRoadmap(view) {
  RM.view = ['flow', 'order', 'board', 'parts', 'deps', 'log'].includes(view) ? view : 'flow';
  if (RM.data) {
    // 탭을 옮기거나 다시 열 때는 가진 것으로 바로 그리고, 오래됐으면 뒤에서 새로 받아 바뀐 경우만 다시 그린다
    rmRender(); rmPoll();
    if (Date.now() - (RM.at || 0) > 10000) {
      const old = RM.rev;
      rmLoad().then(() => { if (RM.rev !== old && $('#modalBack').hidden && !RM.edit) rmRender(); }).catch(() => {});
    }
    return;
  }
  main.innerHTML = '<div class="wide"><p class="loading">로드맵 불러오는 중…</p></div>';
  await rmLoad();
  rmRender();
  rmPoll();
}

function rmRender() {
  if (!RM.data || !/^#\/roadmap/.test(location.hash)) return;
  const by = rmById();
  const f = RM.f;
  const items = RM.data.items;
  const checks = items.reduce((s, x) => s + rolesOf(x).length, 0), checked = items.reduce((s, x) => s + doneN(x), 0);
  const cardsDone = items.filter(isDone).length;
  const list = rmFiltered();
  let body = '';
  if (RM.view === 'flow') body = rmFlow();
  else if (RM.view === 'board') body = rmBoard(list, by);
  else if (RM.view === 'order') body = rmOrderView(by);
  else if (RM.view === 'parts') body = rmPartsView(by);
  else if (RM.view === 'deps') body = rmDepsView(by);
  else {
    const log = RM.data.log;
    body = log.length ? `<div class="rm-logs">${log.map((e) => rmLogLine(e, by)).join('')}</div><p class="muted">최근 ${log.length}건까지 보여요. 체크·풀기는 3분 뒤에 올라오고, 3분 안에 되돌리면 남지 않아요. 카드를 열면 그 카드의 전체 기록이 있어요.${S.admin ? ' 팀장은 줄마다 연필·휴지통으로 기록을 고치거나 지울 수 있어요.' : ''}</p>` : '<p class="empty">아직 기록이 없어요.</p>';
  }
  main.innerHTML = `<div class="wide rm">
    <div class="rm-head"><div><h1 class="page">로드맵</h1>
      <p class="page-sub" style="margin:0">카드 ${items.length}장 중 <b>${cardsDone}장 끝</b> · 파트 칸 ${checks}개 중 ${checked}개 체크 (${checks ? Math.round(checked / checks * 100) : 0}%). ${S.me ? '카드 안 A~E 칸을 누르면 했다·안 했다가 바뀌어요.' : '로그인하면 체크하고 할 일을 추가할 수 있어요.'}</p></div>
      <div class="rm-head-acts">${S.me ? `<button class="btn" data-add="">${svgI('plus', 16)} 할 일 추가</button>` : '<button class="btn" data-login>로그인</button>'}</div></div>
    ${rmPartStrip()}
    ${mineOn() ? (() => { const mi = items.filter((x) => mineOf(x)); const d = mi.filter((x) => mineOf(x) === 'done').length; return `<div class="rm-minesum" style="--pc:${partColor(myPart())}"><b>${esc(partName(myPart()))}</b> 내 칸 ${mi.length}개 중 <b>${d}개 했음</b> · <b>${mi.length - d}개 남음</b><span class="muted">진한 카드가 내 것, 흐린 카드는 다른 파트 것이에요.</span></div>`; })() : ''}
    <div class="tabs">${[['flow', '흐름도'], ['order', '할 순서'], ['board', '보드'], ['parts', '파트별 할 일·받은 말'], ['deps', '의존 관계'], ['log', '최근 변경']].map(([k, l]) => `<button data-v="${k}" class="${RM.view === k ? 'on' : ''}">${l}</button>`).join('')}</div>
    ${RM.view !== 'flow' ? `<div class="rm-tools">${askBtn('')}${mineBtn()}</div>` : ''}
    ${RM.view === 'board' ? `<div class="filters rm-filters">
      <input type="search" id="rmQ" placeholder="코드·제목 검색" value="${esc(f.q)}">
      <select id="rmPart"><option value="">모든 파트</option>${RM_PARTS.map((p) => `<option value="${p}"${f.part === p ? ' selected' : ''}>${esc(partName(p))}</option>`).join('')}</select>
      <select id="rmPrio"><option value="">우선 전체</option>${['P1', 'P2', 'P3'].map((p) => `<option${f.prio === p ? ' selected' : ''}>${p}</option>`).join('')}</select>
      <label class="chk"><input type="checkbox" id="rmHide"${f.hideDone ? ' checked' : ''}> 끝난 카드 숨기기</label>
      <span class="muted">${list.length}장</span>
      <span class="rm-legend"><span class="rm-chip on" style="--pc:var(--muted)"><span class="rm-chip-box">${svgI('check', 12)}</span>A</span> 했음 <span class="rm-chip" style="--pc:var(--muted)"><span class="rm-chip-box"></span>A</span> 아직</span></div>` : ''}
    ${body}</div>`;
  rmBind(main);
  if (RM.view === 'flow') bindFlow(main);
}

function rmBind(root) {
  const save = () => store.set('rm-f2', RM.f);
  $$('.tabs button', root).forEach((b) => b.addEventListener('click', () => { location.hash = '#/roadmap/' + b.dataset.v; }));
  $$('[data-chk]', root).forEach((b) => b.addEventListener('click', (e) => { e.stopPropagation(); rmCheck(b.dataset.chk, b.dataset.p); }));
  $$('[data-open]', root).forEach((b) => {
    b.addEventListener('click', () => rmOpen(b.dataset.open));
    b.addEventListener('keydown', (e) => { if (e.key === 'Enter' && e.target === b) rmOpen(b.dataset.open); });
  });
  $$('[data-add]', root).forEach((b) => b.addEventListener('click', () => rmEdit(null, b.dataset.add)));
  $$('[data-rmask]', root).forEach((b) => b.addEventListener('click', () => rmAsk(b.dataset.rmask)));
  rmLogBind(root);
  const mb = $('#rmMine', root); if (mb) mb.addEventListener('click', () => { RM.f.mine = !RM.f.mine; save(); rmRender(); });
  const lg = $('[data-login]', root); if (lg) lg.addEventListener('click', () => loginModal(() => rmRender()));
  $$('[data-fpart]', root).forEach((b) => b.addEventListener('click', () => {
    RM.f.part = RM.f.part === b.dataset.fpart ? '' : b.dataset.fpart; save();
    if (RM.view !== 'board') location.hash = '#/roadmap/board'; else rmRender();
  }));
  $$('.rm-cell', root).forEach((b) => b.addEventListener('click', () => {
    const on = b.classList.toggle('on');
    $$('.rm-cell', root).forEach((x) => { if (x !== b) x.classList.remove('on'); });
    $$('.rm-edge', root).forEach((r) => {
      const hit = !on || (r.dataset.q.split(' ').includes(b.dataset.b) && r.dataset.p.split(' ').includes(b.dataset.a));
      r.hidden = !hit;
      $$('[data-parts]', r).forEach((x) => { x.hidden = on && !x.dataset.parts.split(' ').includes(b.dataset.a); });
    });
  }));
  const q = $('#rmQ', root);
  if (q) {
    q.addEventListener('input', () => { RM.f.q = q.value; save(); clearTimeout(RM.qt); RM.qt = setTimeout(() => { rmRender(); const n = $('#rmQ'); if (n) { n.focus(); n.setSelectionRange(n.value.length, n.value.length); } }, 250); });
    $('#rmPart', root).addEventListener('change', (e) => { RM.f.part = e.target.value; save(); rmRender(); });
    $('#rmPrio', root).addEventListener('change', (e) => { RM.f.prio = e.target.value; save(); rmRender(); });
    $('#rmHide', root).addEventListener('change', (e) => { RM.f.hideDone = e.target.checked; save(); rmRender(); });
  }
}

async function rmCheck(id, p) {
  if (!needLogin(() => rmRender())) return;
  const it = rmById().get(id); if (!it || !it.roles[p]) return;
  const done = !it.roles[p].done;
  it.roles[p] = done ? { done: true, by: S.me, at: Date.now() } : { done: false, by: null, at: null }; // 바로 보이게
  const inModal = !$('#modalBack').hidden;
  if (inModal) rmPatch(id); else rmRender();
  try {
    await api('/roadmap/check', { body: { id, part: p, done } });
    toast(`${it.code || it.title}: ${partName(p)} ${done ? '했음' : '체크 풂'}`);
    await rmLoad();
    rmRender();
    if (inModal && !$('#modalBack').hidden) rmPatch(id);
  } catch (x) { toast(x.message); await rmLoad(); rmRender(); if (inModal) rmPatch(id); }
}

// 열린 창 안에서 그 카드의 체크 칸·상태만 바꾼다 (창 전체를 다시 그리면 크기가 흔들림)
function rmPatch(id) {
  const m = $('#modal'); const it = rmById().get(id); if (!it) return;
  const by = rmById();
  const frag = (html) => { const t = document.createElement('div'); t.innerHTML = html.trim(); return t.firstElementChild; };
  const bindChk = (root) => $$('[data-chk]', root).forEach((b) => b.addEventListener('click', (e) => { e.stopPropagation(); rmCheck(b.dataset.chk, b.dataset.p); }));
  // 칸 상세 창의 카드
  $$(`.rm-card[data-open="${id}"]`, m).forEach((c) => {
    const s = $('.rm-stage', c); const n = frag(rmCard(it, by, { stage: s ? Number(s.textContent.replace(/\D/g, '')) : 0 }));
    c.replaceWith(n); bindChk(n); n.addEventListener('click', () => { RM.back = RM.modal && RM.modal.k; rmOpen(id); });
  });
  // 카드 창의 파트 칸
  $$(`.rm-role [data-chk="${id}"]`, m).forEach((b) => {
    const p = b.dataset.p, r = it.roles[p]; if (!r) return;
    const row = b.closest('.rm-role');
    row.innerHTML = `${roleChip(it, p, true)}<span class="muted">${r.done ? `${esc(S.members[r.by] || r.by || '')} · ${kst(new Date(r.at || it.updatedAt).toISOString())}` : '아직 안 함'}</span>`;
    bindChk(row);
  });
  const st = $('.rm-detail-head .rm-st', m); if (st && RM.modal && RM.modal.kind === 'card') st.outerHTML = stPill(it);
}

function rmAfterClose() { if (RM.stale) rmLoad().then(rmRender).catch(() => {}); }

// 저장 공통: 충돌이면 최신 내용을 받아 다시 연다
async function rmSave(item, v) {
  try {
    const r = await api('/roadmap/save', { body: { item, v } });
    await rmLoad(); rmRender();
    return r.item;
  } catch (x) {
    if (x.error === 'STALE' || x.error === 'GONE') { toast(x.message, 5000); await rmLoad(); rmRender(); if (x.current) rmOpen(x.current.id); else closeModal(); return null; }
    if (x.error === 'LOGIN') { loginModal(() => rmRender()); return null; }
    throw x;
  }
}
const rmPlain = (it) => ({ id: it.id, code: it.code, title: it.title, area: it.area, roles: it.roles, blocked: !!it.blocked, prio: it.prio, week: it.week, due: it.due, note: it.note, deps: it.deps || [] });

function rmOpen(id) {
  const back = RM.back || (RM.modal && RM.modal.kind === 'card' ? RM.modal.back : null);
  RM.back = null;
  RM.modal = { kind: 'card', id, back };
  const by = rmById();
  const it = by.get(id);
  if (!it) { toast('그 사이 지워진 카드예요.'); return; }
  const wait = waitingOn(it, by);
  const deps = (it.deps || []).map((d) => by.get(d)).filter(Boolean);
  const after = RM.data.items.filter((x) => (x.deps || []).includes(it.id));
  const link = (x) => `<button class="rm-link" data-go="${x.id}"><b>${esc(x.code)}</b> ${esc(x.title)} ${stPill(x)}</button>`;
  const same = !$('#modalBack').hidden && $('#rmNotes') && $('#rmNotes').dataset.id === id;
  const keep = same ? { top: $('#modal').scrollTop, draft: ($('#rmNote') || {}).value || '', to: ($('#rmTo') || {}).value } : null;
  const m = openModal(`<div class="rm-detail">
    <div class="rm-detail-head"><span class="muted">${esc(areaLabel(it.area))}</span>${it.code ? `<span class="rm-code">${esc(it.code)}</span>` : ''}<span class="badge">${it.prio}</span>${it.week ? `<span class="muted">${it.week}주차</span>` : ''}${it.due ? `<span class="muted">마감 ${esc(it.due)}</span>` : ''}${stPill(it)}</div>
    <h2>${esc(it.title)}</h2>
    <div class="rm-rel-h">파트별로 했나요? ${S.me ? '<span class="muted" style="font-weight:500">누르면 바뀌어요</span>' : ''}</div>
    <div class="rm-rolelist">${rolesOf(it).map((p) => { const r = it.roles[p]; return `<div class="rm-role">${roleChip(it, p, true)}<span class="muted">${r.done ? `${esc(S.members[r.by] || r.by || '')} · ${kst(new Date(r.at || it.updatedAt).toISOString())}` : '아직 안 함'}</span></div>`; }).join('')}</div>
    ${S.me ? `<label class="chk" style="margin:6px 0"><input type="checkbox" id="rmBlocked"${it.blocked ? ' checked' : ''}> 막힘 (도움이 필요해요)</label>` : it.blocked ? '<p><span class="rm-st st-blocked">막힘</span></p>' : ''}
    ${it.note ? `<div class="md rm-note">${renderMd(it.note)}</div>` : ''}
    ${wait.length ? `<div class="banner" style="margin:10px 0">아직 안 끝난 선행 작업 ${wait.length}개가 있어요.</div>` : ''}
    <div class="rm-rel"><div><div class="rm-rel-h">먼저 끝나야 하는 작업 (${deps.length})</div>${deps.map(link).join('') || '<p class="muted">없음</p>'}</div>
      <div><div class="rm-rel-h">이 작업을 기다리는 작업 (${after.length})</div>${after.map(link).join('') || '<p class="muted">없음</p>'}</div></div>
    <div class="section-title" style="margin-top:18px">개발자에게 남길 말 <span class="muted" style="font-weight:500">사진은 Ctrl+V로 붙여 넣어요</span></div>
    ${S.me ? `<div class="rm-noteform">
      <div class="rm-noteto"><span class="muted">받는 사람</span><select id="rmTo"><option value="">모두</option>${(() => { const oth = rolesOf(it).filter((x) => x !== myPart()); const def = oth.length === 1 ? oth[0] : ''; return RM_PARTS.filter((p) => p !== myPart()).map((p) => `<option value="${p}"${p === def ? ' selected' : ''}>${esc(partName(p))}</option>`).join(''); })()}</select></div>
      <textarea id="rmNote" rows="3" maxlength="2000" placeholder="예: 응답에 seat_no 꼭 넣어 주세요 / 오늘 여기까지 했어요 / 이거 막혔어요"></textarea>
      <div class="rm-noteacts"><label class="btn sm plain">사진 고르기<input type="file" id="rmFile" accept="image/*" hidden></label><span id="rmImgInfo" class="muted"></span><span style="flex:1"></span><button class="btn sm" id="rmNoteGo">남기기</button></div>
      <img id="rmImgPrev" class="rm-img-prev" hidden alt="붙일 사진 미리보기"></div>` : '<p class="muted">로그인하면 말을 남길 수 있어요.</p>'}
    <div id="rmNotes" data-id="${it.id}">${(RM.notesHtml && RM.notesHtml.id === it.id) ? RM.notesHtml.html : '<p class="loading">불러오는 중…</p>'}</div>
    <div class="row">${S.me ? '<button class="btn sm danger" id="rmDel">삭제</button><span style="flex:1"></span><button class="btn plain" id="rmEditBtn">고치기</button>' : ''}${back ? `<button class="btn plain" data-back>← ${esc(areaLabel(back))}</button>` : ''}<button class="btn plain" data-x>닫기</button></div>
  </div>`, { wide: true });
  if (keep) { const ta = $('#rmNote', m); if (ta) ta.value = keep.draft; const to = $('#rmTo', m); if (to && keep.to != null) to.value = keep.to; m.scrollTop = keep.top; }
  $('[data-x]', m).addEventListener('click', () => { RM.modal = null; closeModal(); rmAfterClose(); });
  const bk = $('[data-back]', m); if (bk) bk.addEventListener('click', () => rmArea(back));
  $$('[data-go]', m).forEach((b) => b.addEventListener('click', () => rmOpen(b.dataset.go)));
  $$('[data-chk]', m).forEach((b) => b.addEventListener('click', () => rmCheck(b.dataset.chk, b.dataset.p)));
  if (S.me) {
    $('#rmBlocked', m).addEventListener('change', async (e) => {
      try { const r = await rmSave({ ...rmPlain(it), blocked: e.target.checked }, it.v); if (r) { toast(r.blocked ? '막힘으로 표시했어요.' : '막힘을 풀었어요.'); rmOpen(r.id); } } catch (x) { toast(x.message); }
    });
    $('#rmEditBtn', m).addEventListener('click', () => { RM.back = back; rmEdit(it); });
    twoStep($('#rmDel', m), '한 번 더 누르면 삭제', async () => {
      try { await api('/roadmap/delete', { body: { id: it.id } }); toast(`${it.code || it.title} 카드를 지웠어요.`); await rmLoad(); rmRender(); if (back) rmArea(back); else { RM.modal = null; closeModal(); } }
      catch (x) { toast(x.message); }
    });
    rmNoteForm(m, it);
  }
  rmNotes(m, it);
}

// 사진은 브라우저에서 긴 변 1600px, JPEG로 줄여서 보낸다 (1MB 이하)
function shrinkImage(file) {
  return new Promise((resolve, reject) => {
    if (!/^image\//.test(file.type)) { reject(new Error('사진 파일만 붙일 수 있어요.')); return; }
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      let w = img.naturalWidth, h = img.naturalHeight; const k = Math.min(1, 1600 / Math.max(w, h)); w = Math.round(w * k); h = Math.round(h * k);
      const c = document.createElement('canvas'); c.width = w; c.height = h;
      const g = c.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, w, h); g.drawImage(img, 0, 0, w, h);
      let q = 0.85, out = c.toDataURL('image/jpeg', q);
      while (out.length > 1300000 && q > 0.4) { q -= 0.12; out = c.toDataURL('image/jpeg', q); }
      if (out.length > 1300000) { reject(new Error('사진이 너무 커요.')); return; }
      resolve(out);
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('사진을 읽지 못했어요.')); };
    img.src = url;
  });
}

function rmNoteForm(m, it) {
  let img = null;
  const ta = $('#rmNote', m), prev = $('#rmImgPrev', m), info = $('#rmImgInfo', m);
  const setImg = async (file) => {
    try {
      img = await shrinkImage(file); prev.src = img; prev.hidden = false;
      info.innerHTML = `사진 ${Math.round(img.length * 0.75 / 1024)}KB <button class="btn sm plain" id="rmImgX">빼기</button>`;
      $('#rmImgX', m).addEventListener('click', () => { img = null; prev.hidden = true; info.textContent = ''; });
    } catch (x) { toast(x.message); }
  };
  $('#rmFile', m).addEventListener('change', (e) => { if (e.target.files[0]) setImg(e.target.files[0]); e.target.value = ''; });
  ta.addEventListener('paste', (e) => {
    const f = [...(e.clipboardData && e.clipboardData.items || [])].find((x) => x.type.startsWith('image/'));
    if (f) { e.preventDefault(); setImg(f.getAsFile()); }
  });
  const go = $('#rmNoteGo', m);
  go.addEventListener('click', async () => {
    if (!ta.value.trim() && !img) { toast('할 말이나 사진을 넣어 주세요.'); return; }
    go.disabled = true;
    const to = $('#rmTo', m).value;
    try {
      await api('/roadmap/note', { body: { id: it.id, text: ta.value, img, to } });
      ta.value = ''; img = null; prev.hidden = true; info.textContent = '';
      toast(to ? `${partName(to)}에게 남겼어요.` : '남겼어요.'); RM.stale = true; rmNotes(m, it);
    } catch (x) { toast(x.message); } finally { go.disabled = false; }
  });
}

async function rmNotes(m, it) {
  const box = $('#rmNotes', m);
  let log;
  try { log = (await api('/roadmap/item-log' + qs({ id: it.id, t: Date.now() }))).log; } catch (x) { box.innerHTML = `<p class="err">${esc(x.message)}</p>`; return; }
  RM.lastItemLog = log;
  if (!box.isConnected) return;
  const by = rmById();
  const notes = log.filter((e) => e.act === 'note');
  const rest = log.filter((e) => e.act !== 'note');
  box.innerHTML = `${notes.map((e) => `<div class="rm-noteitem">
      <div class="rm-noteitem-h"><span class="dot" style="background:${colorOf(e.who)}"></span> <b>${rmWho(e.who)}</b>${e.to ? ` <span class="rm-to">→ ${esc(partName(e.to))}</span>` : ''} <span class="muted">${kst(new Date(e.at).toISOString())}</span>
      ${e.who === S.me || S.admin ? `<button class="btn sm plain" data-ndel="${e.seq}">지우기</button>` : ''}${S.admin ? `<button class="btn sm plain" data-ledit="${e.seq}">고치기</button>` : ''}${e.editedBy ? ' <span class="muted">(수정됨)</span>' : ''}</div>
      ${e.text ? `<div class="rm-note-body">${esc(e.text)}</div>` : ''}
      ${e.img ? (S.me ? `<a href="/api/roadmap/img?id=${e.img}" target="_blank" rel="noopener"><img class="rm-img" loading="lazy" src="/api/roadmap/img?id=${e.img}" alt="${esc(rmWho(e.who))}의 스크린샷"></a>` : '<p class="muted">로그인하면 사진이 보여요.</p>') : ''}
    </div>`).join('') || '<p class="muted">아직 남긴 말이 없어요.</p>'}
    ${rest.length ? `<details class="rm-hist"><summary>바뀐 기록 ${rest.length}건</summary>${rest.map((e) => rmLogLine(e, by, false)).join('')}</details>` : ''}`;
  RM.notesHtml = { id: it.id, html: box.innerHTML };
  rmLogBind(box, () => rmNotes(m, it));
  $$('[data-ndel]', box).forEach((b) => twoStep(b, '한 번 더', async () => {
    try { await api('/roadmap/note-delete', { body: { seq: Number(b.dataset.ndel) } }); RM.stale = true; rmNotes(m, it); } catch (x) { toast(x.message); }
  }));
}

function rmEdit(it, area) {
  if (!needLogin(() => rmEdit(it, area))) return;
  const isNew = !it;
  const cur = it ? rmPlain(it) : { code: '', title: '', area: area || 'etc', roles: myPart() ? { [myPart()]: { done: false } } : {}, blocked: false, prio: 'P1', week: 0, due: '', note: '', deps: [] };
  const deps = new Set(cur.deps);
  const others = RM.data.items.filter((x) => !it || x.id !== it.id).sort((a, b) => RM.data.areas.findIndex((z) => z.k === a.area) - RM.data.areas.findIndex((z) => z.k === b.area) || sortItems(a, b));
  const m = openModal(`<h2>${isNew ? '할 일 추가' : '카드 고치기'}</h2>
    <form id="rmForm" class="rm-form">
      <div class="field"><label>할 일</label><input type="text" name="title" maxlength="200" required value="${esc(cur.title)}" placeholder="예: 좌석 선점 API에 매수 제한 붙이기" autofocus></div>
      <div class="rm-grid2">
        <div class="field"><label>영역</label><select name="area">${RM.data.areas.map((a) => `<option value="${a.k}"${cur.area === a.k ? ' selected' : ''}>${esc(a.label)}</option>`).join('')}</select></div>
        <div class="field"><label>맡을 사람</label><div class="rm-with">${RM_PARTS.map((p) => `<label class="chk rm-pick" style="--pc:${partColor(p)}"><input type="checkbox" name="role" value="${p}"${cur.roles[p] ? ' checked' : ''}> ${esc(S.members[partOwner(p)])}</label>`).join('')}</div></div>
      </div>
      <details class="rm-more"${isNew ? '' : ' open'}><summary>더 적기 <span class="muted">(우선순위·주차·마감일·설명·먼저 끝나야 할 일, 안 적어도 돼요)</span></summary>
      <div class="rm-grid">
        <div class="field"><label>우선</label><select name="prio">${['P1', 'P2', 'P3'].map((p) => `<option${cur.prio === p ? ' selected' : ''}>${p}</option>`).join('')}</select></div>
        <div class="field"><label>주차</label><select name="week">${[0, 1, 2, 3, 4].map((w) => `<option value="${w}"${Number(cur.week) === w ? ' selected' : ''}>${w ? w + '주차' : '미정'}</option>`).join('')}</select></div>
        <div class="field"><label>마감일</label><input type="date" name="due" value="${esc(cur.due || '')}"></div>
        <div class="field"><label>번호 <span class="muted">(비우면 자동)</span></label><input type="text" name="code" maxlength="24" value="${esc(cur.code)}" placeholder="자동"></div>
      </div>
      <div class="field"><label>설명 <span class="muted">(마크다운)</span></label><textarea name="note" rows="3" maxlength="2000">${esc(cur.note)}</textarea></div>
      <div class="rm-depbox"><div class="rm-dephead">먼저 끝나야 하는 일 <span class="muted" id="rmDepN">${deps.size}개</span></div>
        <input type="search" id="rmDepQ" placeholder="번호·제목으로 찾기">
        <div class="rm-deppick">${others.map((x) => `<label class="rm-depopt" data-t="${esc(`${x.code} ${x.title} ${areaLabel(x.area)}`.toLowerCase())}"><input type="checkbox" value="${x.id}"${deps.has(x.id) ? ' checked' : ''}><b>${esc(x.code)}</b> ${esc(x.title)} ${stPill(x)}</label>`).join('')}</div></div>
      </details>
      <p class="err" id="rmErr"></p>
      <div class="row"><button type="button" class="btn plain" data-x>취소</button><button class="btn" id="rmGo">${isNew ? '추가' : '저장'}</button></div>
    </form>`, { wide: true });
  const back = RM.back; RM.back = null;
  $('[data-x]', m).addEventListener('click', () => { if (it) { RM.back = back; rmOpen(it.id); } else if (back) rmArea(back); else { closeModal(); rmAfterClose(); } });
  const form = $('#rmForm', m);
  $('#rmDepQ', m).addEventListener('input', (e) => { const q = e.target.value.trim().toLowerCase(); $$('.rm-depopt', m).forEach((o) => { o.hidden = q && !o.dataset.t.includes(q); }); });
  $$('.rm-depopt input', m).forEach((c) => c.addEventListener('change', () => { if (c.checked) deps.add(c.value); else deps.delete(c.value); $('#rmDepN', m).textContent = `${deps.size}개`; }));
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const parts = $$('input[name=role]:checked', m).map((x) => x.value);
    if (!parts.length) { $('#rmErr', m).textContent = '맡을 사람을 한 명 이상 골라 주세요.'; return; }
    const item = {
      ...(it ? { id: it.id } : {}), code: form.code.value.trim(), title: form.title.value.trim(), area: form.area.value,
      roles: Object.fromEntries(parts.map((p) => [p, { done: !!(cur.roles[p] && cur.roles[p].done) }])),
      blocked: cur.blocked, prio: form.prio.value, week: Number(form.week.value), due: form.due.value, note: form.note.value, deps: [...deps],
    };
    const btn = $('#rmGo', m); btn.disabled = true;
    try {
      const r = await rmSave(item, it ? it.v : undefined);
      if (r) { toast(isNew ? '할 일을 추가했어요.' : '저장했어요.'); RM.back = back; rmOpen(r.id); }
    } catch (x) { $('#rmErr', m).textContent = x.message; } finally { btn.disabled = false; }
  });
}

async function route() {
  const h = location.hash.replace(/^#\/?/, '').split('/');
  const [p, a] = h;
  const priv = ['history', 'commit', 'compare', 'tree'].includes(p);
  try {
    if (!S.me && priv) { setNav(''); pageLocked(); return; }
    if (!p) { setNav('home'); await pageHome(); }
    else if (p === 'doc' && DOCS[a]) { setNav(a); await pageDoc(a); }
    else if (p === 'db') { setNav('db'); await pageDb(a); }
    else if (p === 'ui' && a) { setNav('ui'); await pageUiOne(decodeURIComponent(a)); }
    else if (p === 'ui') { setNav('ui'); await pageUi(); }
    else if (p === 'people' && a) { setNav('people'); await pagePerson(a, h[2], h[3] ? decodeURIComponent(h[3]) : null); }
    else if (p === 'people') { setNav('people'); await pagePeople(); }
    else if (p === 'roadmap') { setNav('roadmap'); await pageRoadmap(a); }
    else if (p === 'ai') { setNav('home'); await pageHome(); aiOpen(); aiList(); }
    else if (p === 'history') { setNav('history'); await pageHistory(); }
    else if (p === 'commit' && a) { setNav('history'); await pageCommit(a); }
    else if (p === 'compare' && a) { setNav(''); await pageCompare(a); }
    else if (p === 'tree') { setNav('tree'); await pageTree(); }
    else { setNav(''); main.innerHTML = '<div class="wrap"><p class="empty">없는 페이지예요. <a href="#/">홈으로</a></p></div>'; }
  } catch (x) {
    main.innerHTML = `<div class="wrap"><p class="empty">불러오지 못했어요: ${esc(x.message)}</p></div>`;
  }
  if (!/^#\/(doc|db)/.test(location.hash) && !pendingJump) window.scrollTo(0, 0);
}
window.addEventListener('hashchange', () => { closeModal(); route(); });
$('#navToggle').addEventListener('click', (e) => { e.stopPropagation(); $('#side').classList.toggle('open'); });

(async () => {
  const r = store.get('ref', 'main');
  S.ref = r;
  try { await loadMe(); } catch { renderTop(); }
  if (S.ref !== 'main' && !S.members[S.ref]) S.ref = 'main';
  renderTop();
  route();
  loadBranches();
  prefetch();
})();
})();
