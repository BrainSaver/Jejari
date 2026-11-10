// 로컬 테스트용 가짜 GitHub API (사이트가 쓰는 엔드포인트만). 메모리 안의 작은 git.
// node site/test/mock-github.mjs <레포 폴더> [포트]
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = process.argv[2] || '.';
const PORT = Number(process.argv[3] || 9100);
const NAMES = ['SeongWon', 'DongGyun', 'DongHyeon', 'DaIn', 'YoungJun'];

const blobs = new Map(); // sha → content
const trees = new Map(); // sha → {path: blobSha}
const commits = new Map(); // sha → {sha, tree, parents, message, author, date}
const refs = new Map(); // branch → sha
const pulls = [];
let clock = Date.parse('2026-11-10T00:00:00Z');

const sha1 = (s) => crypto.createHash('sha1').update(s).digest('hex');
const putBlob = (c) => { const s = sha1('blob:' + c); blobs.set(s, c); return s; };
const putTree = (t) => { const s = sha1('tree:' + JSON.stringify(Object.entries(t).sort())); trees.set(s, { ...t }); return s; };
function putCommit({ tree, parents, message, author }) {
  clock += 60000;
  const date = new Date(clock).toISOString();
  const s = sha1('commit:' + tree + parents.join() + message + date + Math.random());
  commits.set(s, { sha: s, tree, parents, message, author: author || { name: 'BrainSaver', email: 'b@x' }, date });
  return s;
}

// 시드: 레포 문서 파일
const seed = {};
const add = (rel) => { const p = path.join(ROOT, rel); if (fs.existsSync(p)) seed[rel] = fs.readFileSync(p, 'utf8'); };
['README.md', 'docs/기능설계서.md', 'docs/DB설계서.md', 'docs/concurrency.md', 'docs/erd/jejari-erd.html'].forEach(add);
const uiDir = path.join(ROOT, 'docs/ui');
if (fs.existsSync(uiDir)) fs.readdirSync(uiDir).forEach((f) => add('docs/ui/' + f));
const seedTree = putTree(Object.fromEntries(Object.entries(seed).map(([k, v]) => [k, putBlob(v)])));
const root = putCommit({ tree: seedTree, parents: [], message: 'docs: 초기 문서' });
refs.set('main', root);
NAMES.forEach((n) => refs.set(n, root));

const resolve = (ref) => refs.get(ref) || (commits.has(ref) ? ref : [...commits.keys()].find((k) => k.startsWith(ref)));
function ancestors(s) {
  const out = new Set(); const st = [s];
  while (st.length) { const x = st.pop(); if (!x || out.has(x)) continue; out.add(x); st.push(...commits.get(x).parents); }
  return out;
}
function mergeBase(a, b) {
  const A = ancestors(a);
  const order = []; const st = [b]; const seen = new Set();
  while (st.length) { const x = st.shift(); if (seen.has(x)) continue; seen.add(x); order.push(x); st.push(...commits.get(x).parents); }
  return order.find((x) => A.has(x));
}
const treeOf = (c) => trees.get(commits.get(c).tree);
function diffTrees(t1, t2) {
  const files = [];
  const keys = new Set([...Object.keys(t1 || {}), ...Object.keys(t2 || {})]);
  for (const k of [...keys].sort()) {
    const a = (t1 || {})[k], b = (t2 || {})[k];
    if (a === b) continue;
    const A = a ? blobs.get(a).split('\n') : [], B = b ? blobs.get(b).split('\n') : [];
    const patch = ['@@ -1 +1 @@', ...A.filter((l) => !B.includes(l)).slice(0, 50).map((l) => '-' + l), ...B.filter((l) => !A.includes(l)).slice(0, 50).map((l) => '+' + l)].join('\n');
    files.push({ filename: k, status: !a ? 'added' : !b ? 'removed' : 'modified', additions: B.filter((l) => !A.includes(l)).length, deletions: A.filter((l) => !B.includes(l)).length, patch, sha: b });
  }
  return files;
}
function ghCommit(c) {
  return {
    sha: c.sha, html_url: `https://github.com/mock/commit/${c.sha}`,
    commit: { message: c.message, author: { ...c.author, date: c.date }, committer: { name: 'GitHub', date: c.date }, tree: { sha: c.tree } },
    author: null, parents: c.parents.map((p) => ({ sha: p })),
  };
}
function merge3(baseC, ours, theirs) {
  const b = treeOf(baseC), o = treeOf(ours), t = treeOf(theirs);
  const out = {}; const conflicts = [];
  for (const k of new Set([...Object.keys(b), ...Object.keys(o), ...Object.keys(t)])) {
    const vb = b[k], vo = o[k], vt = t[k];
    if (vo === vt) { if (vo) out[k] = vo; }
    else if (vb === vo) { if (vt) out[k] = vt; }
    else if (vb === vt) { if (vo) out[k] = vo; }
    else conflicts.push(k);
  }
  return { out, conflicts };
}
function doMerge(base, head, message, author) {
  const bs = refs.get(base), hs = resolve(head);
  if (ancestors(bs).has(hs)) return { status: 204 };
  if (ancestors(hs).has(bs)) { // 빨리 감기 대신 merge 커밋 (GitHub merges API도 커밋을 만든다)
    const s = putCommit({ tree: commits.get(hs).tree, parents: [bs, hs], message, author });
    refs.set(base, s); return { status: 201, sha: s };
  }
  const mb = mergeBase(bs, hs);
  const { out, conflicts } = merge3(mb, bs, hs);
  if (conflicts.length) return { status: 409, conflicts };
  const s = putCommit({ tree: putTree(out), parents: [bs, hs], message, author });
  refs.set(base, s); return { status: 201, sha: s };
}
function reachableSorted(s) { return [...ancestors(s)].map((x) => commits.get(x)).sort((a, b) => (a.date < b.date ? 1 : -1)); }

function send(res, status, data) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(data == null ? '' : JSON.stringify(data));
}

const server = http.createServer(async (req, res) => {
  let body = '';
  for await (const ch of req) body += ch;
  const b = body ? JSON.parse(body) : {};
  const u = new URL(req.url, 'http://x');
  const p = decodeURIComponent(u.pathname).replace(/^\/repos\/[^/]+\/[^/]+/, '');
  const q = u.searchParams;
  const m = (re) => p.match(re);
  let r;
  try {
    if (req.headers.authorization !== 'Bearer test-token') return send(res, 401, { message: 'Bad credentials' });
    // 테스트 도우미: 다른 사람이 main을 바꾼 상황 만들기
    if (req.method === 'POST' && p === '/_test/commit') {
      const t = { ...treeOf(refs.get(b.branch)) };
      t[b.path] = putBlob(b.content);
      const s = putCommit({ tree: putTree(t), parents: [refs.get(b.branch)], message: b.message, author: { name: b.author || 'BrainSaver' } });
      refs.set(b.branch, s); return send(res, 201, { sha: s });
    }
    if (req.method === 'GET' && (r = m(/^\/contents\/(.*)$/))) {
      const c = resolve(q.get('ref') || 'main'); if (!c) return send(res, 404, { message: 'No ref' });
      const t = treeOf(c); const fp = r[1];
      if (t[fp]) return send(res, 200, { type: 'file', path: fp, sha: t[fp], content: Buffer.from(blobs.get(t[fp])).toString('base64'), encoding: 'base64' });
      const kids = Object.keys(t).filter((k) => k.startsWith(fp + '/') && !k.slice(fp.length + 1).includes('/'));
      if (kids.length) return send(res, 200, kids.map((k) => ({ type: 'file', name: k.split('/').pop(), path: k, sha: t[k] })));
      return send(res, 404, { message: 'Not Found' });
    }
    if (req.method === 'PUT' && (r = m(/^\/contents\/(.*)$/))) {
      const head = refs.get(b.branch); const t = { ...treeOf(head) }; const fp = r[1];
      if ((t[fp] || undefined) !== (b.sha || undefined)) return send(res, 409, { message: `${fp} does not match ${b.sha}` });
      t[fp] = putBlob(Buffer.from(b.content, 'base64').toString('utf8'));
      const s = putCommit({ tree: putTree(t), parents: [head], message: b.message, author: b.author });
      refs.set(b.branch, s);
      return send(res, 200, { content: { sha: t[fp] }, commit: { sha: s, html_url: `https://github.com/mock/commit/${s}` } });
    }
    if (req.method === 'POST' && p === '/merges') {
      const x = doMerge(b.base, b.head, b.commit_message, { name: 'BrainSaver' });
      if (x.status === 409) return send(res, 409, { message: 'Merge conflict' });
      if (x.status === 204) return send(res, 204);
      return send(res, 201, ghCommit(commits.get(x.sha)));
    }
    if (req.method === 'GET' && (r = m(/^\/compare\/(.+)\.\.\.(.+)$/))) {
      const a = resolve(r[1]), h = resolve(r[2]); if (!a || !h) return send(res, 404, { message: 'Not Found' });
      const A = ancestors(a), H = ancestors(h);
      const ahead = [...H].filter((x) => !A.has(x)).map((x) => commits.get(x)).sort((x, y) => (x.date < y.date ? -1 : 1));
      const behind = [...A].filter((x) => !H.has(x));
      const mb = mergeBase(a, h);
      return send(res, 200, {
        status: a === h ? 'identical' : !behind.length ? 'ahead' : !ahead.length ? 'behind' : 'diverged',
        ahead_by: ahead.length, behind_by: behind.length, commits: ahead.map(ghCommit),
        files: diffTrees(treeOf(mb), treeOf(h)),
      });
    }
    if (req.method === 'GET' && p === '/commits') {
      const c = resolve(q.get('sha') || 'main'); if (!c) return send(res, 404, { message: 'No commit found' });
      let list = reachableSorted(c);
      const fp = q.get('path');
      if (fp) list = list.filter((x) => diffTrees(x.parents[0] ? treeOf(x.parents[0]) : {}, trees.get(x.tree)).some((f) => f.filename === fp || f.filename.startsWith(fp + '/')) && x.parents.length < 2);
      return send(res, 200, list.slice(0, Number(q.get('per_page') || 30)).map(ghCommit));
    }
    if (req.method === 'GET' && (r = m(/^\/commits\/([0-9a-f]+)$/))) {
      const c = commits.get(resolve(r[1])); if (!c) return send(res, 404, { message: 'Not Found' });
      return send(res, 200, { ...ghCommit(c), files: diffTrees(c.parents[0] ? treeOf(c.parents[0]) : {}, trees.get(c.tree)) });
    }
    if (req.method === 'GET' && (r = m(/^\/git\/ref\/heads\/(.+)$/))) return send(res, 200, { object: { sha: refs.get(r[1]) } });
    if (req.method === 'PATCH' && (r = m(/^\/git\/refs\/heads\/(.+)$/))) { refs.set(r[1], b.sha); return send(res, 200, { object: { sha: b.sha } }); }
    if (req.method === 'GET' && (r = m(/^\/git\/commits\/(.+)$/))) return send(res, 200, { sha: r[1], tree: { sha: commits.get(r[1]).tree } });
    if (req.method === 'GET' && (r = m(/^\/git\/blobs\/(.+)$/))) return send(res, 200, { content: Buffer.from(blobs.get(r[1])).toString('base64') });
    if (req.method === 'POST' && p === '/git/trees') {
      const t = { ...trees.get(b.base_tree) };
      for (const e of b.tree) { if (e.sha) t[e.path] = e.sha; else delete t[e.path]; }
      return send(res, 201, { sha: putTree(t) });
    }
    if (req.method === 'POST' && p === '/git/commits') return send(res, 201, { sha: putCommit({ tree: b.tree, parents: b.parents, message: b.message, author: b.author }) });
    if (req.method === 'GET' && p === '/pulls') return send(res, 200, pulls.filter((x) => x.state === 'open' && `mock:${x.head}` === q.get('head') || (x.state === 'open' && q.get('head').endsWith(':' + x.head))));
    if (req.method === 'POST' && p === '/pulls') { const pr = { number: pulls.length + 1, head: b.head, base: b.base, state: 'open', title: b.title, body: b.body, html_url: `https://github.com/mock/pull/${pulls.length + 1}` }; pulls.push(pr); return send(res, 201, pr); }
    if (req.method === 'PUT' && (r = m(/^\/pulls\/(\d+)\/merge$/))) {
      const pr = pulls[Number(r[1]) - 1];
      const x = doMerge(pr.base, pr.head, b.commit_title, { name: 'BrainSaver' });
      if (x.status === 409) return send(res, 405, { message: 'Pull Request is not mergeable' });
      pr.state = 'closed'; return send(res, 200, { sha: x.sha || refs.get(pr.base), merged: true });
    }
    if (req.method === 'GET' && p === '/_test/state') return send(res, 200, { refs: Object.fromEntries(refs), pulls, commits: commits.size });
    return send(res, 404, { message: 'mock: no route ' + req.method + ' ' + p });
  } catch (e) {
    console.error(e);
    return send(res, 500, { message: String(e) });
  }
});
server.listen(PORT, () => console.log('mock github on', PORT));
