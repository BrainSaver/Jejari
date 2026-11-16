// 커밋 메시지를 Groq로 만든다. 키가 없거나 실패하면 기본 형식으로 돌려준다.
import { scopeOf } from './config.js';

// ---------- 모델 고르기 ----------
// Groq는 모델을 자주 내리므로 이름을 박아 두지 않는다. 환경 변수가 없으면 /models 목록에서 선호 순서대로 고른다.
const PREFER = [
  'moonshotai/kimi-k2-instruct-0905', 'moonshotai/kimi-k2-instruct', 'meta-llama/llama-4-maverick-17b-128e-instruct',
  'llama-3.3-70b-versatile', 'meta-llama/llama-4-scout-17b-16e-instruct', 'openai/gpt-oss-120b', 'qwen/qwen3-32b',
  'openai/gpt-oss-20b', 'llama-3.1-8b-instant',
];
const FALLBACK_MODEL = 'llama-3.3-70b-versatile';
let modelCache = null; // { at, id }
const chatUrl = (env) => env.GROQ_API || 'https://api.groq.com/openai/v1/chat/completions';
export async function pickModel(env, { refresh } = {}) {
  if (env.GROQ_MODEL && !refresh) return env.GROQ_MODEL;
  if (!refresh && modelCache && Date.now() - modelCache.at < 6 * 3600 * 1000) return modelCache.id;
  const url = chatUrl(env);
  if (!/\/chat\/completions$/.test(url)) return env.GROQ_MODEL || FALLBACK_MODEL;
  try {
    const res = await fetch(url.replace(/\/chat\/completions$/, '/models'), { headers: { 'Authorization': `Bearer ${env.GROQ_API_KEY}` } });
    if (!res.ok) return env.GROQ_MODEL || FALLBACK_MODEL;
    const ids = ((await res.json()).data || []).filter((m) => m.active !== false).map((m) => m.id);
    const chat = ids.filter((id) => !/whisper|tts|guard|playai|orpheus|distil|compound|prompt|safeguard/i.test(id));
    const id = PREFER.find((x) => chat.includes(x)) || chat.find((x) => /llama|kimi|qwen|gpt-oss|mistral|gemma/i.test(x)) || chat[0] || FALLBACK_MODEL;
    modelCache = { at: Date.now(), id };
    return id;
  } catch { return env.GROQ_MODEL || FALLBACK_MODEL; }
}
// 모델 이름이 틀렸으면(404·모델 없음) 목록을 새로 받아 한 번 더 보낸다
async function groqChat(env, body, { model, signal }) {
  const send = (m) => fetch(chatUrl(env), {
    method: 'POST', signal,
    headers: { 'Authorization': `Bearer ${env.GROQ_API_KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...body, model: m }),
  });
  let m = model || await pickModel(env);
  let res = await send(m);
  if (res.status === 404 || res.status === 400) {
    const text = await res.clone().text();
    if (/model/i.test(text) && /(not found|decommissioned|does not exist|not supported)/i.test(text)) {
      modelCache = null;
      m = await pickModel(env, { refresh: true });
      res = await send(m);
    }
  }
  return { res, model: m };
}
const stripThink = (t) => String(t || '').replace(/<think>[\s\S]*?<\/think>/g, '').trim();

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
    const { res } = await groqChat(env, {
      temperature: 0.2,
      max_tokens: 400,
      messages: [{ role: 'system', content: system }, { role: 'user', content: user }],
    }, { signal: ctrl.signal });
    clearTimeout(timer);
    if (!res.ok) return { message: fallback, ai: false, reason: `Groq ${res.status}` };
    const data = await res.json();
    let line = stripThink((data.choices && data.choices[0] && data.choices[0].message.content) || '').split('\n')[0];
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
  '질문하는 사람은 이 부분을 처음 공부하는 팀원이다. 전체 맥락을 알고 있되, 답은 **짧고 간단하게** 한다.',
  '형식: 핵심 한두 문장 → 프로젝트에서 어디에 쓰이는지 한 문장(근거는 "동시성 §9", "기능설계 RSV-02", "DB설계 waitlist"처럼 짚기). 전체 3~6줄, 쉬운 한국어.',
  '표·긴 코드·긴 목록은 질문이 직접 요구할 때만 쓴다. 자세한 내용은 다 설명하지 말고 아래 "이어서" 질문으로 넘긴다.',
  '주어진 문서에 없는 우리 팀 설계를 지어내지 않는다. 문서로 알 수 없으면 그렇다고 짧게 말한다.',
  '답 맨 끝에 반드시 아래 형식으로, 이 팀원이 다음에 궁금해할 만한 짧은 질문 3개를 붙인다 (각 30자 이내, 서로 다른 방향: 원리·우리 설계와의 연결·구현 방법 등).',
  '[[이어서]]',
  '- 질문 1',
  '- 질문 2',
  '- 질문 3',
].join('\n');

// 답 끝의 "[[이어서]]" 목록을 떼어 내 버튼용 질문으로 돌려준다
export function splitFollowups(text) {
  const m = String(text || '').match(/\n?\s*\**\[\[\s*이어서\s*\]\]\**\s*\n?([\s\S]*)$/);
  if (!m) return { body: String(text || '').trim(), followups: [] };
  const followups = m[1].split('\n').map((l) => l.replace(/^\s*(?:[-*•]|\d+[.)])\s*/, '').replace(/^["'“]|["'”]$/g, '').trim())
    .filter((l) => l && l.length <= 80).slice(0, 4);
  return { body: String(text).slice(0, m.index).replace(/\n-{3,}\s*$/, '').trim(), followups };
}
const clip = (s, n) => { s = String(s || ''); return s.length > n ? s.slice(0, n) + '\n…(생략)' : s; };

export function contextMessage(ctx, { doc, path, heading, selection, section }, sectionChars) {
  const out = [
    ctx.brief,
    '',
    '[관련 문서 조각 (전체 문서에서 자동 검색)]',
    ...(ctx.related.length ? ctx.related.map((r) => `### ${r.doc} · ${r.heading || '(머리말)'}\n${r.text}`) : ['(찾은 조각 없음)']),
  ];
  // 문서에서 글을 골라 물을 때만 지금 섹션과 고른 부분을 붙인다 (채팅으로 바로 물으면 없음)
  if (String(section || '').trim()) out.push('', `[지금 보고 있는 섹션: ${clip(doc, 40)}${path ? ` (${clip(path, 80)})` : ''}${heading ? ' · ' + clip(heading, 120) : ''}]`, clip(section, sectionChars));
  if (String(selection || '').trim()) out.push('', '[고른 부분]', clip(selection, 1500));
  return out.join('\n');
}

async function callGroq(env, msgs) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 30000);
  try {
    const { res, model } = await groqChat(env, { temperature: 0.3, max_tokens: 2000, messages: msgs }, { model: env.GROQ_ASK_MODEL, signal: ctrl.signal });
    const text = await res.text();
    return { status: res.status, text, model };
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
    if (r.status < 200 || r.status >= 300) {
      let why = ''; try { why = (JSON.parse(r.text).error || {}).message || ''; } catch {}
      return { error: 'AI_FAIL', status: 502, message: `AI 응답을 받지 못했어요 (Groq ${r.status}${why ? ': ' + why.slice(0, 120) : ''}).` };
    }
    let answer = '';
    try { const d = JSON.parse(r.text); answer = stripThink((d.choices && d.choices[0] && d.choices[0].message.content) || ''); } catch {}
    const { body: shortAnswer, followups } = splitFollowups(answer);
    if (!shortAnswer) return { error: 'AI_FAIL', status: 502, message: 'AI가 빈 답을 보냈어요.' };
    return { answer: shortAnswer, followups, model: r.model, refs: ctx.related.map((x) => ({ doc: x.doc, heading: x.heading, path: x.path }))
      .filter((x, i, all) => all.findIndex((y) => y.path === x.path && y.heading === x.heading) === i).slice(0, 5) };
  }
  return { error: 'AI_BUSY', status: 429, message: `질문이 AI 한도보다 커요 (Groq ${last && last.status}). 고른 부분을 줄이거나 1분 뒤에 다시 물어봐 주세요.` };
}
