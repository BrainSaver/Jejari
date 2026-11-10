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
