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
  '너는 "제자리" 팀 프로젝트의 설계 문서를 설명해 주는 도우미다.',
  '제자리: 명절 열차 공정 예매 시스템. 5명이 3주 동안 Spring Boot, Kafka, Redis, MongoDB, Oracle, Python으로 만든다.',
  '핵심: 대기열(Redis), 좌석 동시성(중복 예약 0건), 결제 기한 자동 반환, Saga·Outbox·멱등성, 위험 점수와 의심 구간 타임라인.',
  '질문하는 사람은 이 부분을 처음 공부하는 팀원이다. 한국어로, 쉬운 말로, 핵심부터 짧게 설명한다.',
  '모르는 용어는 한 줄로 풀어 주고, 필요하면 짧은 예시나 비유를 든다. 코드가 도움이 되면 Java·Spring 기준으로 짧게 보여 준다.',
  '주어진 문서 내용에 근거해 답하고, 문서에 없는 우리 팀 설계를 지어내지 않는다. 문서만으로 알 수 없으면 그렇다고 말하고 일반적인 방식을 설명한다.',
  '마크다운으로 답한다. 너무 길게 쓰지 않는다 (보통 15줄 이내).',
].join('\n');

const clip = (s, n) => { s = String(s || ''); return s.length > n ? s.slice(0, n) + '\n…(생략)' : s; };

export async function askDoc(env, { doc, path, heading, selection, section, question, history }) {
  if (!env.GROQ_API_KEY) return { error: 'NO_AI', status: 503, message: 'AI 키가 설정되지 않았어요.' };
  const context = [
    `문서: ${clip(doc, 40)}${path ? ` (${clip(path, 80)})` : ''}`,
    heading ? `섹션: ${clip(heading, 120)}` : '',
    '',
    '[선택한 부분]',
    clip(selection, 3000),
    '',
    '[선택한 부분이 들어 있는 섹션 원문 (마크다운)]',
    clip(section, 8000),
  ].filter((l) => l !== null).join('\n');
  const msgs = [{ role: 'system', content: ASK_SYSTEM }, { role: 'user', content: context + '\n\n질문: ' + clip(question, 500) }];
  // 이어서 묻기: 첫 질문 뒤의 문답은 그대로 이어 붙인다
  // 첫 질문은 문서 맥락과 함께 항상 보내고, 그 뒤 문답은 최근 6개만 보낸다
  const hist = Array.isArray(history) ? history : [];
  if (hist.length) {
    msgs[1].content = context + '\n\n질문: ' + clip(hist[0].content, 500);
    hist.slice(1).slice(-6).forEach((h) => msgs.push({ role: h.role === 'assistant' ? 'assistant' : 'user', content: clip(h.content, 3000) }));
    msgs.push({ role: 'user', content: clip(question, 500) });
  }
  try {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 25000);
    const res = await fetch(env.GROQ_API || 'https://api.groq.com/openai/v1/chat/completions', {
      method: 'POST',
      signal: ctrl.signal,
      headers: { 'Authorization': `Bearer ${env.GROQ_API_KEY}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ model: env.GROQ_MODEL || 'llama-3.3-70b-versatile', temperature: 0.3, max_tokens: 1200, messages: msgs }),
    });
    clearTimeout(timer);
    if (res.status === 429) return { error: 'AI_BUSY', status: 429, message: 'AI 사용량이 잠시 꽉 찼어요. 조금 뒤에 다시 물어봐 주세요.' };
    if (!res.ok) return { error: 'AI_FAIL', status: 502, message: `AI 응답을 받지 못했어요 (Groq ${res.status}).` };
    const data = await res.json();
    const answer = ((data.choices && data.choices[0] && data.choices[0].message.content) || '').trim();
    if (!answer) return { error: 'AI_FAIL', status: 502, message: 'AI가 빈 답을 보냈어요.' };
    return { answer };
  } catch (e) {
    return { error: 'AI_FAIL', status: 504, message: 'AI 응답이 늦어요. 다시 시도해 주세요.' };
  }
}
