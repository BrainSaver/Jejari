// GitHub REST 호출 모음. 토큰은 Cloudflare 비밀값 GITHUB_TOKEN.
import { repoOf, apiOf, MAIN } from './config.js';

export class GhError extends Error {
  constructor(status, body, path) {
    super(`GitHub ${status} ${path}: ${(body && body.message) || ''}`);
    this.status = status; this.body = body;
  }
}

export async function gh(env, method, path, body) {
  const url = path.startsWith('http') ? path : `${apiOf(env)}${path.replace('{repo}', repoOf(env))}`;
  const res = await fetch(url, {
    method,
    headers: {
      'Authorization': `Bearer ${env.GITHUB_TOKEN}`,
      'Accept': 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'User-Agent': 'jejari-docs-site',
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 204) return null;
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { message: text }; }
  if (!res.ok) throw new GhError(res.status, data, path);
  return data;
}

const enc = new TextEncoder();
const dec = new TextDecoder();
export function b64encode(str) {
  const bytes = enc.encode(str);
  let bin = '';
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}
export function b64decode(b64) {
  const bin = atob((b64 || '').replace(/\s/g, ''));
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return dec.decode(bytes);
}
const encPath = (p) => p.split('/').map(encodeURIComponent).join('/');

// 파일 읽기. 없으면 null.
export async function getFile(env, path, ref) {
  try {
    const d = await gh(env, 'GET', `/repos/{repo}/contents/${encPath(path)}?ref=${encodeURIComponent(ref)}`);
    if (Array.isArray(d)) return null;
    let content = d.content;
    if (!content && d.sha) {
      const blob = await gh(env, 'GET', `/repos/{repo}/git/blobs/${d.sha}`);
      content = blob.content;
    }
    return { path, ref, sha: d.sha, content: b64decode(content) };
  } catch (e) {
    if (e.status === 404) return null;
    throw e;
  }
}

export async function listDir(env, dir, ref) {
  try {
    const d = await gh(env, 'GET', `/repos/{repo}/contents/${encPath(dir)}?ref=${encodeURIComponent(ref)}`);
    return Array.isArray(d) ? d.map((x) => ({ name: x.name, path: x.path, type: x.type, sha: x.sha })) : [];
  } catch (e) {
    if (e.status === 404) return [];
    throw e;
  }
}

export async function putFile(env, { path, branch, content, sha, message, author }) {
  return gh(env, 'PUT', `/repos/{repo}/contents/${encPath(path)}`, {
    message, branch, content: b64encode(content), ...(sha ? { sha } : {}), author,
  });
}

// main을 이름 브랜치에 합친다. 'merged' | 'uptodate' | 'conflict'
export async function syncMain(env, branch) {
  // 브랜치에 main에 없는 커밋이 없으면 빨리 감기만 한다 (합치기 커밋이 계속 생기지 않게)
  const c = await compare(env, branch, MAIN);
  if (c.status === 'identical' || c.status === 'behind') return 'uptodate';
  if (c.status === 'ahead') {
    const head = await gh(env, 'GET', `/repos/{repo}/git/ref/heads/${MAIN}`);
    try {
      await gh(env, 'PATCH', `/repos/{repo}/git/refs/heads/${encodeURIComponent(branch)}`, { sha: head.object.sha, force: false });
      return 'merged';
    } catch (e) {
      if (e.status !== 422) throw e; // 그 사이 브랜치가 움직였으면 아래 merge로
    }
  }
  try {
    const r = await gh(env, 'POST', '/repos/{repo}/merges', {
      base: branch, head: MAIN, commit_message: `merge: main → ${branch} (문서 사이트 자동)`,
    });
    return r ? 'merged' : 'uptodate';
  } catch (e) {
    if (e.status === 409) return 'conflict';
    throw e;
  }
}

export async function compare(env, base, head) {
  return gh(env, 'GET', `/repos/{repo}/compare/${encodeURIComponent(base)}...${encodeURIComponent(head)}`);
}

// main과 브랜치가 둘 다 바꾼 파일 (충돌 후보)
export async function conflictFiles(env, branch) {
  const [mine, theirs] = await Promise.all([compare(env, MAIN, branch), compare(env, branch, MAIN)]);
  const a = new Set((mine.files || []).map((f) => f.filename));
  return (theirs.files || []).map((f) => f.filename).filter((f) => a.has(f));
}

export async function listCommits(env, ref, { path, perPage = 50 } = {}) {
  const q = new URLSearchParams({ sha: ref, per_page: String(perPage) });
  if (path) q.set('path', path);
  try {
    return await gh(env, 'GET', `/repos/{repo}/commits?${q}`);
  } catch (e) {
    if (e.status === 404 || e.status === 409) return [];
    throw e;
  }
}

export function slimCommit(c) {
  return {
    sha: c.sha,
    message: c.commit.message,
    author: (c.commit.author && c.commit.author.name) || (c.author && c.author.login) || '',
    date: (c.commit.author && c.commit.author.date) || (c.commit.committer && c.commit.committer.date),
    parents: (c.parents || []).map((p) => p.sha),
  };
}
