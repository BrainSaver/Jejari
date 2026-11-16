// AI 질문에 붙일 프로젝트 맥락: 항상 넣는 "프로젝트 지도" + 전체 문서에서 관련 부분 검색.
// 문서 전체(20만 토큰 이상)를 매번 보낼 수 없어서, 문서를 섹션 조각으로 나눠 질문과 겹치는 낱말이 많은 조각을 고른다.
import { getFile, listDir } from './github.js';

const SOURCES = [
  { path: 'README.md', doc: '기획서' },
  { path: 'docs/기능설계서.md', doc: '기능설계' },
  { path: 'docs/concurrency.md', doc: '동시성' },
  { path: 'docs/DB설계서.md', doc: 'DB설계' },
];
const TTL_MS = 10 * 60 * 1000;
let corpus = null; // { at, chunks, brief }

const stripTags = (s) => String(s || '').replace(/<[^>]+>/g, '').trim();

// 마크다운을 제목 단위 조각으로 나눈다. 너무 긴 조각은 빈 줄 기준으로 다시 자른다.
export function chunkDoc(doc, path, text, maxLen = 2200) {
  const out = [];
  const trail = []; // [level, title]
  let buf = [], fence = false;
  const headingPath = () => trail.map((t) => t[1]).join(' > ');
  const flush = () => {
    const body = buf.join('\n').trim(); buf = [];
    if (body.length < 40) return;
    const heading = headingPath();
    if (body.length <= maxLen) { out.push({ doc, path, heading, text: body }); return; }
    let part = '';
    for (const para of body.split(/\n\s*\n/)) {
      if (part && part.length + para.length > maxLen) { out.push({ doc, path, heading, text: part.trim() }); part = ''; }
      part += para.slice(0, maxLen * 2) + '\n\n';
    }
    if (part.trim().length >= 40) out.push({ doc, path, heading, text: part.trim() });
  };
  for (const line of text.split('\n')) {
    if (/^\s*(```|~~~)/.test(line)) fence = !fence;
    const h = !fence && line.match(/^(#{1,4})\s+(.*)$/);
    const sum = !fence && line.match(/<summary>(.*?)<\/summary>/);
    if (h || sum) {
      flush();
      const level = h ? h[1].length : 2;
      const title = stripTags(h ? h[2] : sum[1]);
      while (trail.length && trail[trail.length - 1][0] >= level) trail.pop();
      if (level > 1) trail.push([level, title]);
    }
    buf.push(line);
  }
  flush();
  return out;
}

function headingsOf(text, level) {
  const re = new RegExp(`^${'#'.repeat(level)}\\s+(.*)$`);
  let fence = false; const out = [];
  for (const l of text.split('\n')) {
    if (/^\s*(```|~~~)/.test(l)) fence = !fence;
    const m = !fence && l.match(re);
    if (m) out.push(stripTags(m[1]).replace(/`/g, ''));
  }
  return out;
}

// 항상 넣는 짧은 프로젝트 지도: 개요, 역할, 문서별 목차, 원천 규칙
export function buildBrief(files, uiList) {
  const readme = files['README.md'] || '';
  const overview = (readme.split('<summary><b>프로젝트 개요</b></summary>')[1] || '').split('<p align')[0].trim().slice(0, 600);
  const roles = (readme.match(/^\| [A-E] \| .*$/gm) || []).map((r) => {
    const c = r.split('|').map((x) => x.trim());
    return `- ${c[1]} ${c[2]}(${c[3]}): ${c[4].slice(0, 150)}`;
  });
  const toc = (path, label, level = 2) => {
    const t = files[path]; if (!t) return '';
    return `- ${label} (${path}): ${headingsOf(t, level).join(' / ').slice(0, 700)}`;
  };
  const readmeToc = (readme.match(/<summary><b>(.*?)<\/b><\/summary>/g) || []).map((s) => stripTags(s)).join(' / ');
  const tables = files['docs/DB설계서.md'] ? headingsOf(files['docs/DB설계서.md'], 4).map((h) => h.split(' ')[0]).join(', ') : '';
  return [
    '[프로젝트 지도]',
    `제자리(Jejari): ${overview || '명절 열차 공정 예매 시스템. 5명이 3주 동안 만드는 팀 프로젝트.'}`,
    '기술: Spring Boot, Spring Data JPA, Kafka, Oracle(예약·결제), MongoDB(행동 로그), Redis(대기열·좌석 선점·캐시), Python, k6, Docker Compose.',
    roles.length ? '역할:\n' + roles.join('\n') : '',
    '문서와 목차:',
    `- 기획서 (README.md): ${readmeToc}`,
    toc('docs/기능설계서.md', '기능설계'),
    toc('docs/concurrency.md', '동시성 절차'),
    toc('docs/DB설계서.md', 'DB설계'),
    tables ? `- 테이블: ${tables.slice(0, 900)}` : '',
    uiList.length ? `- 화면설계: ${uiList.join(', ')}` : '',
    '원천 규칙: 스키마(테이블·컬럼·제약)는 ERD 편집기, 트랜잭션 순서·SQL·Redis 절차는 동시성 문서(concurrency.md §번호)가 원천. 1차 구현 범위는 기능설계 1.1.',
  ].filter(Boolean).join('\n');
}

async function loadCorpus(env) {
  if (corpus && Date.now() - corpus.at < TTL_MS) return corpus;
  const files = {};
  const chunks = [];
  const got = await Promise.all(SOURCES.map((s) => getFile(env, s.path, 'main').catch(() => null)));
  got.forEach((f, i) => { if (f) { files[SOURCES[i].path] = f.content; chunks.push(...chunkDoc(SOURCES[i].doc, SOURCES[i].path, f.content)); } });
  const uiList = [];
  const dir = await listDir(env, 'docs/ui', 'main').catch(() => []);
  const uiMd = dir.filter((x) => /^[UA]-\d{2}\.md$/.test(x.name));
  const uiFiles = await Promise.all(uiMd.map((x) => getFile(env, x.path, 'main').catch(() => null)));
  uiFiles.forEach((f, i) => {
    if (!f) return;
    const title = (f.content.match(/^# (.*)$/m) || [])[1] || uiMd[i].name;
    uiList.push(title);
    chunks.push(...chunkDoc(`화면설계 ${title}`, uiMd[i].path, f.content));
  });
  corpus = { at: Date.now(), chunks: indexChunks(chunks), brief: buildBrief(files, uiList) };
  return corpus;
}

// ---------- 검색 (BM25) ----------
const PARTICLE = /(에서는|에서|으로는|으로|에게|까지|부터|이라|라는|하는|해서|하고|하면|이며|이고|입니다|합니다|됩니다|은|는|이|가|을|를|에|의|로|와|과|도|만)$/;
const STOP = new Set(['그리고', '하지만', '때문', '경우', '이것', '저것', '그것', '있는', '없는', '합니다', '입니다', '설명', '쉽게', '해줘', '알려줘', '무엇', '어떻게', '이렇게', '부분']);
export function termsOf(text) {
  const out = [];
  const s = String(text || '');
  for (const m of s.matchAll(/§\s?\d+(?:\.\d+)?/g)) out.push({ t: m[0].replace(/\s/g, ''), w: 3 });
  for (const m of s.matchAll(/\b[A-Z]{2,5}-\d{2}\b/g)) out.push({ t: m[0].toLowerCase(), w: 3 });
  for (const m of s.matchAll(/[A-Za-z][A-Za-z0-9_]*(?:[:._][A-Za-z0-9_{}]+)*/g)) {
    const t = m[0].toLowerCase(); if (t.length < 3) continue;
    out.push({ t, w: /[_:.]/.test(t) ? 2.5 : 1.5 });
    if (/[_:.]/.test(t)) t.split(/[_:.{}]+/).filter((x) => x.length >= 3).forEach((x) => out.push({ t: x, w: 0.7 }));
  }
  for (const m of s.matchAll(/[가-힣]{2,}/g)) {
    let t = m[0]; for (let i = 0; i < 2; i++) t = t.replace(PARTICLE, '');
    if (t.length >= 2 && !STOP.has(t)) out.push({ t, w: 1 });
  }
  return out;
}
function tokensOf(text) {
  const tf = new Map();
  for (const { t } of termsOf(text)) tf.set(t, (tf.get(t) || 0) + 1);
  return tf;
}
export function indexChunks(chunks) {
  const df = new Map();
  chunks.forEach((c) => {
    c.tf = tokensOf(c.heading + '\n' + c.text);
    c.htf = tokensOf(c.heading);
    c.len = c.text.length;
    for (const t of c.tf.keys()) df.set(t, (df.get(t) || 0) + 1);
  });
  const avg = chunks.reduce((a, c) => a + c.len, 0) / Math.max(1, chunks.length);
  return { list: chunks, df, avg, n: chunks.length };
}
// query: 문자열 또는 [{ text, w }] (부분마다 무게). 섹션 본문의 § 참조는 명시적 연결이라 무게를 유지한다.
export function search(index, query, { exclude, limitChars }) {
  const q = new Map();
  const parts = typeof query === 'string' ? [{ text: query, w: 1 }] : query;
  for (const p of parts) {
    for (const { t, w } of termsOf(p.text)) {
      const ww = t.startsWith('§') || /^[a-z]{2,5}-\d{2}$/.test(t) ? Math.max(w * p.w, 2) : w * p.w;
      q.set(t, (q.get(t) || 0) + ww);
    }
  }
  const k1 = 1.2, b = 0.75;
  const scored = index.list.map((c) => {
    let s = 0;
    for (const [t, w] of q) {
      const f = c.tf.get(t); if (!f) continue;
      const idf = Math.log(1 + (index.n - index.df.get(t) + 0.5) / (index.df.get(t) + 0.5));
      s += w * idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * c.len / index.avg));
      if (c.htf.has(t)) s += w * idf * 0.8;
    }
    return { c, s };
  }).filter((x) => x.s > 0 && !(exclude && exclude(x.c))).sort((a, b) => b.s - a.s);
  const out = []; let used = 0;
  for (const { c } of scored) {
    if (used + c.text.length > limitChars) { if (used > limitChars * 0.6) break; continue; }
    out.push({ doc: c.doc, path: c.path, heading: c.heading, text: c.text });
    used += c.text.length;
    if (out.length >= 8) break;
  }
  return out;
}

// selection·question·현재 섹션으로 맥락을 만든다. budget은 관련 조각에 쓸 글자 수.
export async function buildContext(env, { path, heading, selection, section, question }, budget) {
  const cp = await loadCorpus(env);
  const head = String(heading || '').replace(/^#+\s*/, '');
  const query = [
    { text: selection, w: 1 },
    { text: question, w: 0.6 },
    { text: head, w: 0.5 },
    { text: String(section || '').slice(0, 1500), w: 0.25 },
  ];
  const related = search(cp.chunks, query, {
    limitChars: budget,
    // 이미 보내는 현재 섹션의 조각은 빼서 자리를 아낀다
    exclude: (c) => c.path === path && head && c.heading.split(' > ')[0] === head.split(' > ')[0],
  });
  return { brief: cp.brief, related };
}
