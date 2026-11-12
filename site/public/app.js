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

async function api(path, opts = {}) {
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
function bump() { fresh.n = Date.now(); }

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
  const d = await api('/me' + qs({}));
  Object.assign(S, { me: d.name, ko: d.ko, admin: d.admin, members: d.members, pins: d.pins, ai: d.ai });
  renderTop();
}
async function loadBranches() {
  if (!S.me) { S.branches = null; renderTop(); return; }
  try { S.branches = await api('/branches' + qs({})); } catch { S.branches = null; }
  renderTop();
}
function myAhead() { const b = S.branches && S.branches.branches.find((x) => x.name === S.me); return b ? b.ahead : 0; }
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
    <span class="acts"><a class="btn sm ghost" href="#/compare/${ref}">main과 다른 부분</a><button class="btn sm plain" data-main>main 보기</button></span></div>`;
}
function bindBanner(root) { const b = $('[data-main]', root); if (b) b.addEventListener('click', () => setRef('main')); }

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
      <a class="card" href="#/doc/plan"><h3>기획서</h3><p>프로젝트 목표, 팀 역할, 일정, 기술 선택 이유</p></a>
      <a class="card" href="#/doc/func"><h3>기능설계</h3><p>기능 목록, 상세, 상태 전이, API, Kafka, 에러 코드</p></a>
      <a class="card" href="#/db"><h3>DB설계 · ERD</h3><p>Oracle·MongoDB·Redis·Kafka 역할, 테이블 정의, ERD</p></a>
      <a class="card" href="#/ui"><h3>화면설계</h3><p>사용자 11개, 관리자 7개 화면을 눌러 보는 시안과 설명</p></a>
      <a class="card" href="#/doc/conc"><h3>동시성</h3><p>트랜잭션 순서, SQL, Redis Lua, 락 순서, 테스트 목록</p></a>
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
      closeModal(); bump(); await loadBranches(); toast(`main에 합쳤어요 (PR #${r.pr}).`, 5000); route();
    } catch (x) {
      b.disabled = false; b.textContent = '합치기';
      if (x.error === 'MAIN_CONFLICT') { conflictModal(x); return; }
      $('#mErr', m).innerHTML = esc(x.message) + (x.url ? ` <a href="${esc(x.url)}" target="_blank" rel="noopener">PR 열기</a>` : '');
    }
  });
}
$('#mergeBtn').addEventListener('click', mergeModal);

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
      ${conv.messages.length ? '' : `<p class="muted ai-hello">${free ? '프로젝트에 대해 무엇이든 물어보세요. AI는 프로젝트 지도와 관련 문서를 찾아 보고 짧게 답해요. 문서에서 글을 드래그해 물어볼 수도 있어요.' : '고른 부분에 대해 물어보세요.'}</p>`}
      <div class="ask-log"></div>
    </div>
    <div class="ai-foot">
      ${conv.messages.length ? '' : `<div class="ask-chips">${(free ? FREE_CHIPS : ASK_CHIPS).map((q) => `<button type="button" class="chip">${esc(q)}</button>`).join('')}</div>`}
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
  if (src) src.addEventListener('click', (e) => { e.preventDefault(); jumpTo(conv.path, conv.heading); });
  if (window.innerWidth >= 700) setTimeout(() => input.focus(), 30);
}

function setNav(key) { $$('.side a').forEach((a) => a.classList.toggle('on', a.dataset.nav === key)); $('#side').classList.remove('open'); }
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
})();
})();
