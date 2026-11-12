// 문서 사이트 API. 읽기는 누구나, 쓰기는 이름+PIN 로그인 후 자기 이름 브랜치에만.
import { MEMBERS, NAMES, MAIN, REFS, EDITABLE, READABLE, adminOf } from '../../lib/config.js';
import {
  gh, GhError, getFile, listDir, putFile, syncMain, compare, conflictFiles, listCommits, slimCommit,
} from '../../lib/github.js';
import { pinStatus, currentUser, login, logout, resetPin, sessionCookie, clearCookie } from '../../lib/auth.js';
import { commitMessage, validMessage, askDoc } from '../../lib/groq.js';
import { buildContext } from '../../lib/context.js';
import { aiLogStore, AI_LOG_MAX } from '../../lib/ailog.js';

const json = (data, status = 200, headers = {}) =>
  new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...headers } });
const fail = (status, error, message, extra = {}) => json({ error, message, ...extra }, status);

const okPath = (p, list) => typeof p === 'string' && !p.includes('..') && list.some((re) => re.test(p));
const okRef = (r) => REFS.includes(r);
// 로그인 안 한 사람은 main의 기획서·기능설계서·DB설계서(ERD 포함)·화면설계·동시성 문서만 볼 수 있다
const PUBLIC = [/^README\.md$/, /^docs\/concurrency\.md$/, /^docs\/기능설계서\.md$/, /^docs\/DB설계서\.md$/, /^docs\/ui\/[UA]-\d{2}\.(md|html)$/, /^docs\/ui\/index\.json$/, /^docs\/erd\/jejari-erd\.html$/];
const LOGIN_FIRST = () => fail(401, 'LOGIN', '로그인하면 볼 수 있어요.');
const authorOf = (name) => ({ name, email: `${name}@users.noreply.jejari.pages.dev` });

// AI 질문은 로그인 없이 쓸 수 있으므로 IP당 분당 횟수를 제한한다 (인스턴스 메모리, 대략적인 방어)
const ASK_PER_MIN = 15;
const askHits = new Map();
function askAllowed(req) {
  const ip = req.headers.get('CF-Connecting-IP') || req.headers.get('X-Forwarded-For') || 'local';
  const now = Date.now();
  const list = (askHits.get(ip) || []).filter((t) => now - t < 60000);
  if (list.length >= ASK_PER_MIN) { askHits.set(ip, list); return false; }
  list.push(now); askHits.set(ip, list);
  if (askHits.size > 5000) askHits.clear();
  return true;
}
// ---------- AI 질문 기록 (로그인한 사람. 저장소는 lib/ailog.js: D1, 없으면 KV) ----------
const sOk = (v, max) => typeof v === 'string' && v.length <= max;
function validConv(c) {
  if (!c || typeof c !== 'object' || !/^c[a-z0-9]{4,24}$/.test(c.id || '')) return false;
  if (!sOk(c.doc || '', 80) || !sOk(c.path || '', 200) || !sOk(c.heading || '', 300) || !sOk(c.selection || '', 6000) || !sOk(c.section || '', 8000)) return false;
  if (typeof c.at !== 'number' || !Array.isArray(c.messages) || c.messages.length > 80) return false;
  const okMsg = (m) => m && ['user', 'assistant'].includes(m.role) && sOk(m.content, 8000)
    && (m.followups == null || (Array.isArray(m.followups) && m.followups.length <= 5 && m.followups.every((f) => sOk(f, 120))))
    && (m.refs == null || (Array.isArray(m.refs) && m.refs.length <= 8 && m.refs.every((r) => r && sOk(r.doc || '', 120) && sOk(r.heading || '', 300) && sOk(r.path || '', 200))))
    && (m.model == null || sOk(m.model, 80));
  return c.messages.every(okMsg) && JSON.stringify(c).length <= 90000;
}
const cleanConv = (c) => ({
  id: c.id, at: c.at, doc: c.doc || '', path: c.path || '', heading: c.heading || '', selection: c.selection || '', section: c.section || '',
  messages: c.messages.map((m) => ({ role: m.role, content: m.content, ...(m.followups ? { followups: m.followups } : {}), ...(m.refs ? { refs: m.refs.map((r) => ({ doc: r.doc || '', heading: r.heading || '', path: r.path || '' })) } : {}), ...(m.model ? { model: m.model } : {}) })),
});
const str = (v, max) => typeof v === 'string' && v.trim().length > 0 && v.length <= max;

function kstDate() {
  const d = new Date(Date.now() + 9 * 3600 * 1000);
  return d.toISOString().slice(0, 10).replace(/-/g, '');
}
function countOf(hay, needle) {
  let n = 0, i = 0;
  while ((i = hay.indexOf(needle, i)) !== -1) { n++; i += needle.length; }
  return n;
}

// 짧은 캐시 (같은 데이터 연속 요청 줄이기). ?fresh=1이면 건너뜀.
async function cached(request, seconds, make) {
  const url = new URL(request.url);
  if (url.searchParams.has('fresh') || typeof caches === 'undefined') return make();
  const key = new Request(url.toString(), { method: 'GET' });
  const cache = caches.default;
  const hit = await cache.match(key).catch(() => null);
  if (hit) return hit;
  const res = await make();
  if (res.status === 200) {
    const copy = new Response(res.clone().body, res);
    copy.headers.set('Cache-Control', `public, max-age=${seconds}`);
    await cache.put(key, copy).catch(() => {});
  }
  return res;
}

// ---------- 읽기 ----------

async function branchesInfo(env) {
  const heads = {};
  const rows = await Promise.all(NAMES.map(async (name) => {
    try {
      const c = await compare(env, MAIN, name);
      const last = (c.commits || []).slice(-1)[0];
      return { name, ko: MEMBERS[name], ahead: c.ahead_by, behind: c.behind_by, lastDate: last ? last.commit.author.date : null };
    } catch (e) {
      if (e.status === 404) return { name, ko: MEMBERS[name], missing: true };
      throw e;
    }
  }));
  return { main: MAIN, branches: rows, heads };
}

async function commitsFeed(env, { path, perPage = 40 }) {
  const lists = await Promise.all(REFS.map((r) => listCommits(env, r, { path, perPage })));
  const map = new Map();
  lists.forEach((list, i) => {
    for (const c of list) {
      const s = map.get(c.sha) || { ...slimCommit(c), refs: [] };
      s.refs.push(REFS[i]);
      map.set(c.sha, s);
    }
  });
  return [...map.values()].sort((a, b) => (a.date < b.date ? 1 : -1));
}

async function graph(env) {
  const [all, docs] = await Promise.all([
    Promise.all(REFS.map((r) => listCommits(env, r, { perPage: 100 }))),
    Promise.all(REFS.map((r) => listCommits(env, r, { path: 'docs', perPage: 100 }))),
  ]);
  const map = new Map();
  const heads = {};
  all.forEach((list, i) => {
    if (list[0]) heads[REFS[i]] = list[0].sha;
    for (const c of list) if (!map.has(c.sha)) map.set(c.sha, slimCommit(c));
  });
  const docSet = new Set();
  docs.forEach((list) => list.forEach((c) => docSet.add(c.sha)));
  const commits = [...map.values()].sort((a, b) => (a.date < b.date ? 1 : -1));
  return { heads, commits, docs: [...docSet] };
}

// ---------- 쓰기 ----------

async function prepare(env, me) {
  const sync = await syncMain(env, me);
  if (sync === 'conflict') {
    const files = await conflictFiles(env, me).catch(() => []);
    return { error: 'MAIN_CONFLICT', message: 'main의 최신 내용을 내 브랜치에 합치다 충돌이 났어요. 저장을 멈췄어요.', files };
  }
  return { sync };
}

async function save(env, me, body) {
  const { path, mode, oldText, newText, message, heading } = body;
  if (!okPath(path, EDITABLE)) return fail(400, 'BAD_PATH', '사이트에서 고칠 수 없는 파일이에요.');
  if (typeof newText !== 'string') return fail(400, 'BAD_BODY', '내용이 비었어요.');
  if (!validMessage(message)) return fail(400, 'BAD_MESSAGE', '커밋 메시지는 한 줄로 써 주세요.');

  const p = await prepare(env, me);
  if (p.error) return json(p, 409);

  const cur = await getFile(env, path, me);
  let next;
  if (mode === 'section') {
    if (!cur) return fail(409, 'STALE', '내 브랜치에 이 파일이 없어요.', { current: null });
    if (!oldText) return fail(400, 'BAD_BODY', '고칠 섹션 원문이 없어요.');
    const n = countOf(cur.content, oldText);
    if (n !== 1) {
      return fail(409, 'STALE', n === 0
        ? '편집하는 동안 이 섹션이 바뀌었어요. 최신 내용과 비교해서 다시 고쳐 주세요.'
        : '같은 내용의 섹션이 여러 개라 어디를 고칠지 알 수 없어요.', { current: cur.content });
    }
    next = cur.content.replace(oldText, () => newText);
  } else if (mode === 'file') {
    if (cur && typeof oldText === 'string' && cur.content !== oldText) {
      return fail(409, 'STALE', '편집하는 동안 이 파일이 바뀌었어요. 최신 내용과 비교해서 다시 고쳐 주세요.', { current: cur.content });
    }
    if (cur && oldText == null && !body.overwrite) {
      return fail(409, 'STALE', '이미 있는 파일이에요.', { current: cur.content });
    }
    next = newText;
  } else {
    return fail(400, 'BAD_BODY', 'mode는 section 또는 file이에요.');
  }
  if (cur && next === cur.content) return fail(400, 'NO_CHANGE', '바뀐 내용이 없어요.');

  try {
    const r = await putFile(env, { path, branch: me, content: next, sha: cur && cur.sha, message: message.trim(), author: authorOf(me) });
    return json({ ok: true, branch: me, sync: p.sync, commit: r.commit.sha, url: r.commit.html_url, heading: heading || null });
  } catch (e) {
    if (e.status === 409 || e.status === 422) return fail(409, 'STALE', '방금 다른 저장이 먼저 들어갔어요. 다시 시도해 주세요.');
    throw e;
  }
}

async function erdExport(env, me, body) {
  const { content, message } = body;
  if (typeof content !== 'string' || content.length > 900000) return fail(400, 'BAD_BODY', 'ERD 내보내기 내용이 없거나 너무 커요.');
  try { JSON.parse(content); } catch { return fail(400, 'BAD_BODY', 'ERD 내보내기가 JSON이 아니에요.'); }
  if (!validMessage(message)) return fail(400, 'BAD_MESSAGE', '커밋 메시지는 한 줄로 써 주세요.');
  const p = await prepare(env, me);
  if (p.error) return json(p, 409);
  const path = `docs/erd/exports/${me}-${kstDate()}.json`;
  const cur = await getFile(env, path, me);
  if (cur && cur.content === content) return fail(400, 'NO_CHANGE', '오늘 저장한 내용과 같아요.');
  const r = await putFile(env, { path, branch: me, content, sha: cur && cur.sha, message: message.trim(), author: authorOf(me) });
  return json({ ok: true, path, branch: me, commit: r.commit.sha });
}

// 내 브랜치에 있는 커밋을 되돌리는 새 커밋 (해당 파일을 그 커밋 직전 상태로)
async function revert(env, me, body) {
  const { sha, force } = body;
  if (!/^[0-9a-f]{40}$/.test(sha || '')) return fail(400, 'BAD_SHA', '커밋을 찾을 수 없어요.');
  const c = await gh(env, 'GET', `/repos/{repo}/commits/${sha}`);
  if (c.commit.author.name !== me) return fail(403, 'NOT_MINE', '내가 만든 수정만 되돌릴 수 있어요.');
  if ((c.parents || []).length !== 1) return fail(400, 'MERGE_COMMIT', '합치기 커밋은 되돌릴 수 없어요.');
  const p = await prepare(env, me);
  if (p.error) return json(p, 409);

  const ref = await gh(env, 'GET', `/repos/{repo}/git/ref/heads/${me}`);
  const head = ref.object.sha;
  // 내 브랜치에 들어 있는 커밋인지
  const cmp = await compare(env, sha, head);
  if (cmp.status !== 'ahead' && cmp.status !== 'identical') return fail(400, 'NOT_IN_BRANCH', '내 브랜치에 없는 커밋이에요.');

  const parent = c.parents[0].sha;
  const files = (c.files || []).filter((f) => okPath(f.filename, EDITABLE) || okPath(f.previous_filename || '', EDITABLE));
  if (!files.length) return fail(400, 'NOTHING', '되돌릴 수 있는 문서 파일이 없어요.');

  const later = [];
  const tree = [];
  for (const f of files) {
    const atCommit = f.status === 'removed' ? null : await getFile(env, f.filename, sha);
    const now = await getFile(env, f.filename, head);
    if ((atCommit && atCommit.sha) !== (now && now.sha)) later.push(f.filename);
    const before = f.status === 'added' ? null : await getFile(env, f.previous_filename || f.filename, parent);
    tree.push({ path: f.filename, mode: '100644', type: 'blob', sha: before ? before.sha : null });
  }
  if (later.length && !force) {
    return fail(409, 'LATER_CHANGES', '이 수정 뒤에 같은 파일이 또 바뀌었어요. 되돌리면 뒤의 수정도 함께 사라져요.', { files: later });
  }
  const headCommit = await gh(env, 'GET', `/repos/{repo}/git/commits/${head}`);
  const newTree = await gh(env, 'POST', '/repos/{repo}/git/trees', { base_tree: headCommit.tree.sha, tree });
  const title = c.commit.message.split('\n')[0];
  const now = new Date().toISOString();
  const nc = await gh(env, 'POST', '/repos/{repo}/git/commits', {
    message: `revert: ${title}\n\n되돌린 커밋 ${sha.slice(0, 7)}`,
    tree: newTree.sha, parents: [head], author: { ...authorOf(me), date: now },
  });
  await gh(env, 'PATCH', `/repos/{repo}/git/refs/heads/${me}`, { sha: nc.sha });
  return json({ ok: true, commit: nc.sha, files: files.map((f) => f.filename) });
}

async function mergeToMain(env, me) {
  const p = await prepare(env, me);
  if (p.error) return json(p, 409);
  const cmp = await compare(env, MAIN, me);
  if (!cmp.ahead_by) return fail(400, 'NOTHING', 'main에 없는 수정이 없어요.');
  const owner = (env.GITHUB_REPO || 'brainsaver/jejari').split('/')[0];
  const open = await gh(env, 'GET', `/repos/{repo}/pulls?state=open&base=${MAIN}&head=${owner}:${me}`);
  let pr = open[0];
  if (!pr) {
    const files = (cmp.files || []).map((f) => `- ${f.filename}`).join('\n');
    pr = await gh(env, 'POST', '/repos/{repo}/pulls', {
      title: `docs: ${me}(${MEMBERS[me]}) 문서 수정 main 반영`,
      head: me, base: MAIN,
      body: `문서 사이트에서 ${me}가 main에 합치기를 눌러 만든 PR입니다.\n\n커밋 ${cmp.ahead_by}개\n\n바뀐 파일\n${files}`,
    });
  }
  try {
    const m = await gh(env, 'PUT', `/repos/{repo}/pulls/${pr.number}/merge`, {
      merge_method: 'merge', commit_title: `Merge branch '${me}' (문서 사이트)`,
    });
    return json({ ok: true, pr: pr.number, url: pr.html_url, sha: m.sha, commits: cmp.ahead_by });
  } catch (e) {
    if (e.status === 405 || e.status === 409) {
      return fail(409, 'NOT_MERGEABLE', '지금은 main에 합칠 수 없어요. GitHub PR에서 확인해 주세요.', { url: pr.html_url });
    }
    throw e;
  }
}

// ---------- 라우터 ----------

export async function onRequest(ctx) {
  const { request: req, env } = ctx;
  const url = new URL(req.url);
  const route = `${req.method} /${(ctx.params.path || []).join('/')}`;
  const q = url.searchParams;

  try {
    if (!env.AUTH) return fail(500, 'NO_KV', 'KV 바인딩 AUTH가 없어요. README의 설정을 확인해 주세요.');
    const me = await currentUser(env, req);

    if (req.method === 'POST') {
      const ct = req.headers.get('Content-Type') || '';
      if (!ct.startsWith('application/json')) return fail(415, 'JSON_ONLY', 'JSON 요청만 받아요.');
    }
    const body = req.method === 'POST' ? await req.json().catch(() => ({})) : null;

    switch (route) {
      // 로그인
      case 'GET /me':
        return json({ name: me, ko: me ? MEMBERS[me] : null, admin: me === adminOf(env), members: MEMBERS, pins: await pinStatus(env), ai: !!env.GROQ_API_KEY, aiLog: env.AI_DB ? 'd1' : 'kv' });
      case 'POST /login': {
        const r = await login(env, body.name, body.pin);
        if (r.error) return json(r, r.error === 'LOCKED' ? 423 : 401);
        return json({ ok: true, name: r.name, firstSet: r.firstSet }, 200, { 'Set-Cookie': sessionCookie(req, r.sid) });
      }
      case 'POST /logout':
        await logout(env, req);
        return json({ ok: true }, 200, { 'Set-Cookie': clearCookie(req) });
      case 'POST /admin/reset-pin': {
        if (!me) return fail(401, 'LOGIN', '로그인이 필요해요.');
        const r = await resetPin(env, me, body.name);
        return json(r, r.error ? 403 : 200);
      }

      // 읽기
      case 'GET /file': {
        const path = q.get('path'); const ref = q.get('ref') || MAIN;
        if (!okPath(path, READABLE) || !okRef(ref)) return fail(400, 'BAD_PATH', '읽을 수 없는 파일이에요.');
        if (!me && (ref !== MAIN || !okPath(path, PUBLIC))) return LOGIN_FIRST();
        return cached(req, 10, async () => {
          const f = await getFile(env, path, ref);
          return f ? json(f, 200) : fail(404, 'NOT_FOUND', '파일이 없어요.');
        });
      }
      case 'GET /list': {
        const dir = q.get('dir'); const ref = q.get('ref') || MAIN;
        if (!['docs/ui', 'docs/erd/exports'].includes(dir) || !okRef(ref)) return fail(400, 'BAD_PATH', '볼 수 없는 폴더예요.');
        if (!me && (ref !== MAIN || dir !== 'docs/ui')) return LOGIN_FIRST();
        return cached(req, 10, async () => json(await listDir(env, dir, ref)));
      }
      case 'GET /branches':
        if (!me) return LOGIN_FIRST();
        return cached(req, 15, async () => json(await branchesInfo(env)));
      case 'GET /compare': {
        if (!me) return LOGIN_FIRST();
        const b = q.get('branch');
        if (!NAMES.includes(b)) return fail(400, 'BAD_REF', '이름 브랜치만 볼 수 있어요.');
        return cached(req, 10, async () => {
          const c = await compare(env, MAIN, b);
          return json({
            branch: b, ahead: c.ahead_by, behind: c.behind_by,
            commits: (c.commits || []).map(slimCommit).reverse(),
            files: (c.files || []).map((f) => ({ filename: f.filename, status: f.status, additions: f.additions, deletions: f.deletions, patch: f.patch || '' })),
          });
        });
      }
      case 'GET /commits': {
        if (!me) return LOGIN_FIRST();
        const path = q.get('path') || undefined;
        if (path && !/^(docs|README\.md)/.test(path)) return fail(400, 'BAD_PATH', '문서 경로만 거를 수 있어요.');
        return cached(req, 15, async () => json(await commitsFeed(env, { path })));
      }
      case 'GET /commit': {
        if (!me) return LOGIN_FIRST();
        const sha = q.get('sha') || '';
        if (!/^[0-9a-f]{7,40}$/.test(sha)) return fail(400, 'BAD_SHA', '커밋을 찾을 수 없어요.');
        return cached(req, 300, async () => {
          const c = await gh(env, 'GET', `/repos/{repo}/commits/${sha}`);
          return json({
            ...slimCommit(c), url: c.html_url,
            files: (c.files || []).map((f) => ({ filename: f.filename, status: f.status, additions: f.additions, deletions: f.deletions, patch: f.patch || '' })),
          });
        });
      }
      case 'GET /graph':
        if (!me) return LOGIN_FIRST();
        return cached(req, 20, async () => json(await graph(env)));

      // 쓰기
      case 'POST /prepare': {
        if (!me) return fail(401, 'LOGIN', '로그인이 필요해요.');
        const path = body.path;
        if (!okPath(path, EDITABLE)) return fail(400, 'BAD_PATH', '사이트에서 고칠 수 없는 파일이에요.');
        const p = await prepare(env, me);
        if (p.error) return json(p, 409);
        const f = await getFile(env, path, me);
        return json({ branch: me, sync: p.sync, file: f });
      }
      case 'POST /commit-message': {
        if (!me) return fail(401, 'LOGIN', '로그인이 필요해요.');
        if (!okPath(body.path, EDITABLE)) return fail(400, 'BAD_PATH', '사이트에서 고칠 수 없는 파일이에요.');
        return json(await commitMessage(env, body));
      }
      case 'POST /ask': {
        // 로그인 없이 가능. 문서 일부를 골라 Groq에 질문한다
        const { question } = body;
        const selection = body.selection == null ? '' : body.selection;
        if (typeof selection !== 'string' || selection.length > 6000 || !str(question, 500)) return fail(400, 'BAD_ASK', '질문(500자 이하)이 필요하고, 고른 글은 6000자 이하여야 해요.');
        if (body.history != null && (!Array.isArray(body.history) || body.history.length > 20
          || !body.history.every((h) => h && ['user', 'assistant'].includes(h.role) && str(h.content, 6000)))) return fail(400, 'BAD_ASK', '대화 기록 형식이 맞지 않아요.');
        if (!askAllowed(req)) return fail(429, 'ASK_LIMIT', `AI 질문은 1분에 ${ASK_PER_MIN}번까지예요. 잠시 뒤에 다시 물어봐 주세요.`);
        const r = await askDoc(env, {
          doc: String(body.doc || ''), path: String(body.path || ''), heading: String(body.heading || ''),
          selection, section: String(body.section || ''), question, history: body.history,
        }, buildContext);
        return r.error ? fail(r.status, r.error, r.message) : json(r);
      }
      case 'GET /ai-log':
        if (!me) return fail(401, 'LOGIN', '로그인하면 계정에 저장된 질문 기록을 볼 수 있어요.');
        return json({ list: await aiLogStore(env).list(me) });
      case 'POST /ai-log/save':
        if (!me) return fail(401, 'LOGIN', '로그인이 필요해요.');
        if (!validConv(body.conv)) return fail(400, 'BAD_CONV', '저장할 대화 형식이 맞지 않아요.');
        await aiLogStore(env).save(me, cleanConv(body.conv));
        return json({ ok: true });
      case 'POST /ai-log/merge':
        // 로그인 전에 이 기기에 쌓인 기록을 계정으로 옮긴다 (같은 대화는 최근 것을 남김)
        if (!me) return fail(401, 'LOGIN', '로그인이 필요해요.');
        if (!Array.isArray(body.list) || body.list.length > AI_LOG_MAX || !body.list.every(validConv)) return fail(400, 'BAD_CONV', '옮길 기록 형식이 맞지 않아요.');
        return json({ list: await aiLogStore(env).merge(me, body.list.map(cleanConv)) });
      case 'POST /ai-log/delete':
        if (!me) return fail(401, 'LOGIN', '로그인이 필요해요.');
        await aiLogStore(env).remove(me, body.all ? null : [].concat(body.id || []).filter((x) => typeof x === 'string'));
        return json({ ok: true });
      case 'POST /context': {
        // LLM용 복사: 프로젝트 지도와 전체 문서에서 찾은 관련 부분 (외부 LLM은 길게 받으므로 넉넉히)
        if (!str(body.selection, 6000)) return fail(400, 'BAD_ASK', '선택한 글(6000자 이하)이 필요해요.');
        const ctx = await buildContext(env, {
          path: String(body.path || ''), heading: String(body.heading || ''), selection: body.selection,
          section: String(body.section || ''), question: String(body.question || ''),
        }, 16000);
        return json(ctx);
      }
      case 'POST /save':
        if (!me) return fail(401, 'LOGIN', '로그인이 필요해요.');
        return await save(env, me, body);
      case 'POST /erd-export':
        if (!me) return fail(401, 'LOGIN', '로그인이 필요해요.');
        return await erdExport(env, me, body);
      case 'POST /revert':
        if (!me) return fail(401, 'LOGIN', '로그인이 필요해요.');
        return await revert(env, me, body);
      case 'POST /merge':
        if (!me) return fail(401, 'LOGIN', '로그인이 필요해요.');
        return await mergeToMain(env, me);

      default:
        return fail(404, 'NO_ROUTE', '없는 API예요.');
    }
  } catch (e) {
    if (e instanceof GhError) {
      const msg = e.status === 401 || e.status === 403
        ? 'GitHub 토큰 권한을 확인해 주세요.'
        : 'GitHub 요청이 실패했어요.';
      return fail(502, 'GITHUB', msg, { status: e.status, detail: e.message });
    }
    return fail(500, 'SERVER', '서버 오류가 났어요.', { detail: String(e && e.message || e) });
  }
}
