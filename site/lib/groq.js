// 커밋 메시지를 Groq로 만든다. 키가 없거나 실패하면 기본 형식으로 돌려준다.
import { scopeOf } from './config.js';

const TYPES = 'feat|fix|perf|refactor|test|db|docs|build|ci|chore';
const VALID = new RegExp(`^(${TYPES})\\([^)]{1,20}\\): .{2,60}$`);

export function fallbackMessage(path, heading) {
  const scope = scopeOf(path);
  if (scope === 'erd') return 'db(erd): ERD 편집기 수정 내보내기';
  const what = heading ? heading.replace(/^#+\s*/, '').slice(0, 40) : path.split('/').pop();
  return `docs(${scope}): ${what} 수정`;
}

// 간단한 줄 단위 차이 (앞뒤 같은 줄을 잘라내고 남은 부분만)
export function roughDiff(before, after, limit = 6000) {
  const a = (before || '').split('\n');
  const b = (after || '').split('\n');
  let s = 0;
  while (s < a.length && s < b.length && a[s] === b[s]) s++;
  let ea = a.length - 1, eb = b.length - 1;
  while (ea >= s && eb >= s && a[ea] === b[eb]) { ea--; eb--; }
  const out = [
    ...a.slice(s, ea + 1).map((l) => `- ${l}`),
    ...b.slice(s, eb + 1).map((l) => `+ ${l}`),
  ].join('\n');
  return out.length > limit ? out.slice(0, limit) + '\n…(생략)' : out;
}

export async function commitMessage(env, { path, heading, before, after }) {
  const fallback = fallbackMessage(path, heading);
  if (!env.GROQ_API_KEY) return { message: fallback, ai: false, reason: 'GROQ_API_KEY 없음' };
  const diff = roughDiff(before, after);
  if (!diff.trim()) return { message: fallback, ai: false, reason: '바뀐 내용 없음' };

  const system = [
    '너는 깃 커밋 메시지를 한 줄로 쓰는 도우미다.',
    `형식: 타입(범위): 제목. 타입은 ${TYPES.split('|').join(', ')} 중 하나이고 문서 수정은 보통 docs, ERD 스키마 내보내기는 db.`,
    `범위는 반드시 "${scopeOf(path)}"를 쓴다.`,
    '제목은 한국어, 무엇을 바꿨는지 구체적으로, 50자 이내, 마침표 없이.',
    '설명이나 따옴표 없이 메시지 한 줄만 출력한다.',
  ].join('\n');
  const user = `파일: ${path}\n${heading ? `섹션: ${heading}\n` : ''}바뀐 줄(-는 지운 줄, +는 추가한 줄):\n${diff}`;

  try {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 8000);
    const res = await fetch(env.GROQ_API || 'https://api.groq.com/openai/v1/chat/completions', {
      method: 'POST',
      signal: ctrl.signal,
      headers: { 'Authorization': `Bearer ${env.GROQ_API_KEY}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: env.GROQ_MODEL || 'llama-3.3-70b-versatile',
        temperature: 0.2,
        max_tokens: 80,
        messages: [{ role: 'system', content: system }, { role: 'user', content: user }],
      }),
    });
    clearTimeout(timer);
    if (!res.ok) return { message: fallback, ai: false, reason: `Groq ${res.status}` };
    const data = await res.json();
    let line = ((data.choices && data.choices[0] && data.choices[0].message.content) || '').trim().split('\n')[0];
    line = line.replace(/^["'`]+|["'`]+$/g, '').replace(/\.$/, '').trim();
    if (!VALID.test(line)) return { message: fallback, ai: false, reason: '형식이 맞지 않음', raw: line };
    return { message: line, ai: true };
  } catch (e) {
    return { message: fallback, ai: false, reason: 'Groq 응답 없음' };
  }
}

export function validMessage(m) {
  return typeof m === 'string' && m.trim().length >= 3 && m.length <= 200 && !/\n/.test(m.trim());
}

// ---------- 문서 질문 (AI에게 물어보기) ----------
const ASK_SYSTEM = [
  '너는 "제자리" 팀 프로젝트의 설계 문서를 모두 알고 있는 선배 개발자다.',
  '함께 주는 [프로젝트 지도]로 전체 구조를 파악하고, [관련 문서 조각]은 전체 문서에서 질문과 관련 있는 부분을 자동으로 찾아 온 것이다.',
  '질문하는 사람은 이 부분을 처음 공부하는 팀원이다. 고른 부분만 풀이하지 말고, 프로젝트 전체에서 이 부분이 어디에 쓰이고 어떤 기능·테이블·절차·담당자와 연결되는지까지 짚어 준다.',
  '한국어로, 쉬운 말로, 핵심부터 설명한다. 모르는 용어는 한 줄로 풀고, 필요하면 짧은 예시나 비유, Java·Spring 기준의 짧은 코드를 든다.',
  '근거가 되는 곳은 "동시성 §9", "기능설계 RSV-02", "DB설계 waitlist"처럼 문서와 섹션으로 짚어 준다.',
  '주어진 문서에 없는 우리 팀 설계를 지어내지 않는다. 문서로 알 수 없으면 그렇다고 말하고 일반적인 방식을 설명한다.',
  '마크다운으로, 보통 20줄 이내로 답한다.',
].join('\n');

const clip = (s, n) => { s = String(s || ''); return s.length > n ? s.slice(0, n) + '\n…(생략)' : s; };

export function contextMessage(ctx, { doc, path, heading, selection, section }, sectionChars) {
  return [
    ctx.brief,
    '',
    '[관련 문서 조각 (전체 문서에서 자동 검색)]',
    ...(ctx.related.length ? ctx.related.map((r) => `### ${r.doc} · ${r.heading || '(머리말)'}\n${r.text}`) : ['(찾은 조각 없음)']),
    '',
    `[지금 보고 있는 섹션: ${clip(doc, 40)}${path ? ` (${clip(path, 80)})` : ''}${heading ? ' · ' + clip(heading, 120) : ''}]`,
    clip(section, sectionChars),
    '',
    '[고른 부분]',
    clip(selection, 1500),
  ].join('\n');
}

async function callGroq(env, msgs) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 30000);
  try {
    const res = await fetch(env.GROQ_API || 'https://api.groq.com/openai/v1/chat/completions', {
      method: 'POST',
      signal: ctrl.signal,
      headers: { 'Authorization': `Bearer ${env.GROQ_API_KEY}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ model: env.GROQ_ASK_MODEL || env.GROQ_MODEL || 'llama-3.3-70b-versatile', temperature: 0.3, max_tokens: 1200, messages: msgs }),
    });
    const text = await res.text();
    return { status: res.status, text };
  } finally { clearTimeout(timer); }
}

// buildContext는 context.js (문서 검색). 순환을 피하려고 인자로 받는다.
export async function askDoc(env, input, buildContext) {
  if (!env.GROQ_API_KEY) return { error: 'NO_AI', status: 503, message: 'AI 키가 설정되지 않았어요.' };
  const { question, history } = input;
  const big = Number(env.ASK_CONTEXT_CHARS) || 6000;
  // 무료 등급은 분당 토큰이 작아서, 너무 크다고 거절되면 맥락을 줄여 한 번 더 보낸다
  const tries = [{ related: big, section: 3000 }, { related: Math.round(big / 3), section: 1200 }];
  let last = null;
  for (const t of tries) {
    let ctx;
    try { ctx = await buildContext(env, input, t.related); } catch (e) { ctx = { brief: '', related: [] }; }
    const base = contextMessage(ctx, input, t.section);
    const msgs = [{ role: 'system', content: ASK_SYSTEM }];
    const hist = Array.isArray(history) ? history : [];
    if (hist.length) {
      // 첫 질문은 맥락과 함께, 그 뒤 문답은 최근 6개만
      msgs.push({ role: 'user', content: base + '\n\n질문: ' + clip(hist[0].content, 500) });
      hist.slice(1).slice(-6).forEach((h) => msgs.push({ role: h.role === 'assistant' ? 'assistant' : 'user', content: clip(h.content, 3000) }));
      msgs.push({ role: 'user', content: clip(question, 500) });
    } else {
      msgs.push({ role: 'user', content: base + '\n\n질문: ' + clip(question, 500) });
    }
    let r;
    try { r = await callGroq(env, msgs); } catch (e) { return { error: 'AI_FAIL', status: 504, message: 'AI 응답이 늦어요. 다시 시도해 주세요.' }; }
    const tooBig = r.status === 413 || (r.status === 429 && /token/i.test(r.text) && /(request|too large|limit)/i.test(r.text) && t === tries[0]);
    if (tooBig) { last = r; continue; }
    if (r.status === 429) return { error: 'AI_BUSY', status: 429, message: 'AI 사용량이 잠시 꽉 찼어요. 1분쯤 뒤에 다시 물어봐 주세요.' };
    if (r.status < 200 || r.status >= 300) return { error: 'AI_FAIL', status: 502, message: `AI 응답을 받지 못했어요 (Groq ${r.status}).` };
    let answer = '';
    try { const d = JSON.parse(r.text); answer = ((d.choices && d.choices[0] && d.choices[0].message.content) || '').trim(); } catch {}
    if (!answer) return { error: 'AI_FAIL', status: 502, message: 'AI가 빈 답을 보냈어요.' };
    return { answer, refs: ctx.related.map((x) => ({ doc: x.doc, heading: x.heading, path: x.path })) };
  }
  return { error: 'AI_BUSY', status: 429, message: `질문이 AI 한도보다 커요 (Groq ${last && last.status}). 고른 부분을 줄이거나 1분 뒤에 다시 물어봐 주세요.` };
}
