// 봇 대회 진행 화면 — 버튼 하나로 준비 → 명의 만들기 → 사람 확인 → 사람·봇 동시 예매 → 평가 → 정리.
//
// 실행: node loadtest/contest/web/server.js        → http://localhost:8098 (이 PC에서만 열림)
// 필요: Node 18+, Git Bash(Windows), Docker, 봇마다 bot.json의 needs (보통 Playwright)
//
// 봇은 loadtest/contest/<이름>/bot.json 한 장으로 꽂거나, 화면의 "+ 봇 추가"로 넣는다. 이 파일을 고칠 필요가 없다.
// 쓰는 법: loadtest/contest/진행-화면-사용법.md (화면의 "사용 방법"에도 그대로 보임)
// 화면에는 봇 이름·진행·결과만 보이고 봇 코드는 보이지 않는다 (블라인드 규칙).
// 관리자 비밀번호는 메모리에만 두고 파일·로그에 남기지 않는다. day.sh에는 환경 변수로만 넘긴다.
//
// 의존성 없음 (Node 기본 모듈만). bench/web/server.js와 같은 방식.
'use strict';
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn, spawnSync } = require('child_process');
const { randomUUID } = require('crypto');

const ROOT = path.resolve(__dirname, '../../..');
const CONTEST = path.join(ROOT, 'loadtest/contest');
const RESULTS = path.join(CONTEST, 'results');
const PORT = Number(process.env.PORT || 8098);
const BASE_URL = (process.env.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const WIN = process.platform === 'win32';
const BOT_MAX_MIN = Number(process.env.BOT_MAX_MIN || 30); // 봇 하나가 이보다 오래 돌면 멈춘다

// ── 상태 ──────────────────────────────────────────────────────────
const STEPS = [
  ['check', '점검'], ['prep', '준비 (최신 코드·도커·DB 패치·Redis)'], ['app', '앱 켜기'], ['clean', '지난 기록 정리'], ['setup', '대회 운행 만들기'],
  ['signup', '봇 명의 만들기'], ['probe', '사람 흐름 확인'], ['play', '사람·봇 예매'],
  ['eval', '평가'], ['restore', '정리 (가입 제한 되돌리기)'],
];
let run = null;          // 진행 중인 대회 하나
const clients = new Set();
const recent = [];        // 화면을 늦게 열거나 새로 고쳐도 로그가 보이게 (최근 2,000줄)
const children = new Set();
let appChild = null;      // 이 화면이 켠 앱 (화면을 끄면 같이 꺼짐)

function fresh(opts) {
  return {
    opts, tag: opts.tag, runId: null, runDate: null, startedAt: Date.now(), done: false, failed: null,
    steps: Object.fromEntries(STEPS.map(([id]) => [id, 'wait'])),
    bots: {}, notes: {}, human: null, results: {}, skipWait: false, stop: false,
  };
}

function send(ev) {
  const data = `data: ${JSON.stringify(ev)}\n\n`;
  for (const c of clients) c.write(data);
}
function log(step, line) {
  const text = String(line).replace(/\r/g, '');
  recent.push({ step, line: text });
  if (recent.length > 2000) recent.shift();
  send({ type: 'log', step, line: text });
  if (run) {
    try { fs.appendFileSync(path.join(RESULTS, run.tag, 'log.txt'), `[${new Date().toLocaleTimeString('ko-KR')}] [${step}] ${text}\n`); } catch {}
  }
}
function setStep(id, status, note) {
  run.steps[id] = status;
  run.notes[id] = note || '';
  send({ type: 'step', id, status, note: note || '' });
}
function pushState() { send({ type: 'state', state: publicState() }); }
function publicState() {
  return {
    bots: discoverBots(), baseUrl: BASE_URL, steps: STEPS, appStartedHere: !!appChild,
    run: run && { tag: run.tag, runId: run.runId, runDate: run.runDate, steps: run.steps, notes: run.notes, bots: run.bots,
      human: run.human, results: run.results, done: run.done, failed: run.failed, opts: { ...run.opts, adminPw: undefined } },
  };
}

// ── 봇 찾기 ───────────────────────────────────────────────────────
// bot.json: {"name":"botb","owner":"길동현","blind":true,"run":["node","bot.cjs"],"needs":["playwright"],"env":{}}
// 봇은 두 곳에서 온다
//   folder  loadtest/contest/bot…/bot.json (깃에 있는 봇, 또는 화면의 "파일 올리기"로 만든 폴더 — addedBy: "web")
//   path    화면의 "폴더 지정"으로 등록한 다른 폴더. 등록은 이 PC에만 (web/bots.local.json, gitignore)
const LOCAL = path.join(__dirname, 'bots.local.json');
const NAME = /^bot[a-z]+$/;

function readLocal() {
  try { return JSON.parse(fs.readFileSync(LOCAL, 'utf8')); } catch { return []; }
}
function writeLocal(list) { fs.writeFileSync(LOCAL, JSON.stringify(list, null, 2)); }

function check(bot, m) {
  if (!NAME.test(bot.name)) return '이름은 bot + 영문 소문자 (평가가 이메일에서 이 이름을 읽음)';
  if (m.name !== bot.name) return `bot.json의 name(${m.name})이 폴더 이름(${bot.name})과 다름`;
  if (!Array.isArray(m.run) || !m.run.length) return 'run이 비었음 (예: ["node","bot.js"])';
  if (!fs.existsSync(bot.dir)) return `폴더가 없음: ${bot.dir}`;
  return '';
}

function discoverBots() {
  const out = [];
  for (const d of fs.readdirSync(CONTEST, { withFileTypes: true })) {
    if (!d.isDirectory() || !d.name.startsWith('bot')) continue;
    const dir = path.join(CONTEST, d.name);
    const f = path.join(dir, 'bot.json');
    const bot = { name: d.name, dir, source: 'folder', ok: false, problem: '' };
    if (!fs.existsSync(f)) { bot.problem = 'bot.json 없음 (+ 봇 추가 또는 README "봇 꽂기")'; out.push(bot); continue; }
    try {
      const m = JSON.parse(fs.readFileSync(f, 'utf8'));
      Object.assign(bot, { owner: m.owner || '', blind: !!m.blind, cmd: m.run, needs: m.needs || [], env: m.env || {},
        source: m.addedBy === 'web' ? 'upload' : 'folder' });
      bot.problem = check(bot, m);
      bot.ok = !bot.problem;
    } catch (e) { bot.problem = `bot.json을 읽지 못함: ${e.message}`; }
    out.push(bot);
  }
  for (const m of readLocal()) {
    const bot = { name: m.name, dir: m.dir, source: 'path', owner: m.owner || '', blind: !!m.blind, cmd: m.run,
      needs: m.needs || [], env: m.env || {}, ok: false };
    bot.problem = out.some((b) => b.name === m.name) ? `같은 이름의 봇 폴더가 이미 있음 (loadtest/contest/${m.name})` : check(bot, m);
    bot.ok = !bot.problem;
    out.push(bot);
  }
  return out.sort((a, b) => a.name.localeCompare(b.name));
}

// 봇들이 Playwright 같은 패키지를 같이 쓰게: 봇 폴더들의 node_modules와 전역 npm 폴더를 NODE_PATH로.
// 덕분에 한 봇 폴더에만 설치해도 화면으로 올린 새 봇이 바로 돈다
let globalRoot;
function nodePath(bots) {
  if (globalRoot === undefined) {
    const r = spawnSync(WIN ? 'npm.cmd' : 'npm', ['root', '-g'], { encoding: 'utf8', windowsHide: true, shell: WIN });
    globalRoot = r.status === 0 ? r.stdout.trim() : '';
  }
  const dirs = bots.map((b) => path.join(b.dir, 'node_modules')).filter((d) => fs.existsSync(d));
  if (globalRoot) dirs.push(globalRoot);
  if (process.env.NODE_PATH) dirs.push(process.env.NODE_PATH);
  return [...new Set(dirs)].join(path.delimiter);
}

// 실행 명령 한 줄 → 배열 (따옴표로 묶은 칸 허용: node "my bot.js")
function splitCmd(line) {
  return (String(line).match(/"[^"]*"|'[^']*'|\S+/g) || []).map((t) => t.replace(/^["']|["']$/g, ''));
}

function addBot(b) {
  const name = String(b.name || '').trim();
  if (!NAME.test(name)) throw new Error('이름은 bot + 영문 소문자 (예: bote)');
  if (discoverBots().some((x) => x.name === name)) throw new Error(`${name}는 이미 있습니다`);
  const run = splitCmd(b.run);
  if (!run.length) throw new Error('실행 명령을 넣으세요 (예: node bot.js)');
  const needs = String(b.needs || '').split(/[,\s]+/).filter(Boolean);
  const meta = { name, owner: String(b.owner || '').trim(), blind: !!b.blind, run, needs, env: {} };
  if (b.mode === 'path') {
    const dir = path.resolve(String(b.dir || '').trim());
    if (!b.dir || !fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) throw new Error(`폴더가 없습니다: ${b.dir || '(빈칸)'}`);
    writeLocal([...readLocal(), { ...meta, dir }]);
    return;
  }
  const files = Array.isArray(b.files) ? b.files : [];
  if (!files.length) throw new Error('봇 파일을 고르세요');
  const dir = path.join(CONTEST, name);
  const saved = [];
  fs.mkdirSync(dir);
  try {
    for (const f of files) {
      const base = path.basename(String(f.name || ''));
      if (!/^[A-Za-z0-9._-]{1,80}$/.test(base) || base === 'bot.json' || base.startsWith('.')) throw new Error(`파일 이름은 영문·숫자·._- 만: ${f.name}`);
      const buf = Buffer.from(String(f.data || ''), 'base64');
      if (buf.length > 5 * 1024 * 1024) throw new Error(`${base}가 5MB를 넘습니다`);
      fs.writeFileSync(path.join(dir, base), buf);
      saved.push(base);
    }
    if (!saved.includes(run[run.length - 1]) && !run.some((t) => saved.includes(t))) {
      throw new Error(`실행 명령(${run.join(' ')})에 올린 파일 이름(${saved.join(', ')})이 없습니다`);
    }
    fs.writeFileSync(path.join(dir, 'bot.json'), JSON.stringify({ ...meta, addedBy: 'web' }, null, 2) + '\n');
  } catch (e) {
    fs.rmSync(dir, { recursive: true, force: true });
    throw e;
  }
}

function removeBot(name) {
  const bot = discoverBots().find((x) => x.name === name);
  if (!bot) throw new Error(`${name}가 없습니다`);
  if (bot.source === 'path') return writeLocal(readLocal().filter((m) => m.name !== name));
  if (bot.source === 'upload') return fs.rmSync(bot.dir, { recursive: true, force: true });
  throw new Error('깃에 있는 봇은 화면에서 지우지 않습니다 (체크를 빼면 대회에 안 나감)');
}

// ── 프로세스 ──────────────────────────────────────────────────────
function killTree(p) {
  if (!p || p.exitCode !== null) return;
  if (WIN) spawnSync('taskkill', ['/pid', String(p.pid), '/T', '/F']);
  else { try { process.kill(-p.pid, 'SIGTERM'); } catch { try { p.kill('SIGTERM'); } catch {} } }
}

// 명령 하나를 돌리고 줄마다 log. {code, out}을 돌려준다
function sh(step, cmd, args, { cwd = ROOT, env = {}, prefix = '', timeoutMin = 30, quiet = false, input = null } = {}) {
  return new Promise((resolve) => {
    const p = spawn(cmd, args, {
      cwd, env: { ...process.env, MSYS_NO_PATHCONV: '1', PYTHONIOENCODING: 'utf-8', ...env },
      detached: !WIN, windowsHide: true, shell: WIN && /\.(bat|cmd)$/i.test(cmd),
    });
    children.add(p);
    let out = '';
    let buf = '';
    const onData = (chunk) => {
      const s = chunk.toString('utf8');
      out += s;
      buf += s;
      const lines = buf.split('\n');
      buf = lines.pop();
      if (!quiet) for (const l of lines) if (l.trim()) log(step, prefix + l);
    };
    p.stdout.on('data', onData);
    p.stderr.on('data', onData);
    if (input != null) { p.stdin.on('error', () => {}); p.stdin.end(input); }
    const timer = setTimeout(() => { log(step, `${prefix}${timeoutMin}분이 지나 멈춥니다`); killTree(p); }, timeoutMin * 60000);
    p.on('error', (e) => { out += String(e.message); });
    p.on('close', (code) => {
      clearTimeout(timer);
      children.delete(p);
      if (buf.trim() && !quiet) log(step, prefix + buf);
      resolve({ code: code ?? -1, out });
    });
  });
}

function has(cmd, args = ['--version']) {
  const r = spawnSync(cmd, args, { encoding: 'utf8', windowsHide: true });
  return r.status === 0;
}
function python() {
  for (const c of ['python3', 'python', 'py']) if (has(c, ['-c', 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)'])) return c;
  return null;
}

// 저장소의 .env (관리자 계정·Oracle 비밀번호). 값은 메모리에서만 쓰고 로그·파일에 남기지 않는다
function readEnv() {
  const out = {};
  try {
    for (const line of fs.readFileSync(path.join(ROOT, '.env'), 'utf8').split(/\r?\n/)) {
      const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
      if (m) out[m[1]] = m[2].replace(/^(['"])(.*)\1$/, '$2');
    }
  } catch {}
  return out;
}
function git(args) {
  const r = spawnSync('git', args, { cwd: ROOT, encoding: 'utf8', windowsHide: true });
  return { code: r.status, out: (r.stdout || '').trim(), err: (r.stderr || '').trim() };
}
// 8080을 듣고 있는 프로세스 (다른 창·IntelliJ에서 켠 앱). 못 찾으면 []
function appPids() {
  const port = new URL(BASE_URL).port || '8080';
  if (WIN) {
    const r = spawnSync('netstat', ['-ano', '-p', 'TCP'], { encoding: 'utf8', windowsHide: true });
    return [...new Set((r.stdout || '').split(/\r?\n/).map((l) => l.trim().split(/\s+/))
      .filter((c) => c.length >= 5 && /LISTENING/i.test(c[3]) && c[1].endsWith(':' + port)).map((c) => c[4]))].filter((x) => x !== '0');
  }
  const r = spawnSync('sh', ['-c', `lsof -t -iTCP:${port} -sTCP:LISTEN 2>/dev/null || fuser ${port}/tcp 2>/dev/null`], { encoding: 'utf8' });
  return [...new Set((r.stdout || '').split(/\s+/).filter((x) => /^\d+$/.test(x)))];
}
async function dockerReady() { return spawnSync('docker', ['info'], { windowsHide: true, stdio: 'ignore' }).status === 0; }

async function get(p, opts = {}) {
  const r = await fetch(BASE_URL + p, { ...opts, signal: AbortSignal.timeout(opts.timeout || 10000) });
  return r;
}
async function healthy() {
  try { return (await get('/actuator/health', { timeout: 3000 })).ok; } catch { return false; }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function checkStop() { if (run.stop) throw new Error('중지 버튼을 눌러 멈췄습니다'); }

// ── 단계 ──────────────────────────────────────────────────────────
async function stepCheck() {
  const o = run.opts;
  const problems = [];
  const major = Number(process.versions.node.split('.')[0]);
  if (major < 18) problems.push(`Node ${process.versions.node} → 18 이상이 필요합니다`);
  if (!has('docker', ['compose', 'version'])) problems.push('docker가 없습니다 (Docker Desktop을 설치하세요)');
  else if (!(await dockerReady())) {
    // Docker Desktop이 꺼져 있으면 켜 본다 (Windows 기본 설치 위치)
    const exe = 'C:\\Program Files\\Docker\\Docker\\Docker Desktop.exe';
    if (WIN && fs.existsSync(exe)) {
      log('check', 'Docker Desktop이 꺼져 있어 켭니다 (1~2분)');
      spawn(exe, [], { detached: true, stdio: 'ignore', windowsHide: true }).unref();
      for (let s = 0; s < 180 && !(await dockerReady()); s += 5) { checkStop(); await sleep(5000); }
    }
    if (!(await dockerReady())) problems.push('Docker가 꺼져 있습니다 → Docker Desktop을 켜고 "Engine running"이 되면 다시 [대회 시작]');
  }
  if (!has('bash')) problems.push('bash가 없습니다 (Windows는 Git Bash에서 이 화면을 실행하세요)');
  for (const b of run.chosen) {
    for (const n of b.needs) {
      const r = spawnSync(process.execPath, ['-e', `require.resolve(${JSON.stringify(n)})`],
        { cwd: b.dir, windowsHide: true, env: { ...process.env, NODE_PATH: nodePath(discoverBots()) } });
      if (r.status !== 0) {
        // 봇이 쓰는 패키지(보통 Playwright)가 없으면 그 봇 폴더에 설치한다. 모든 봇이 NODE_PATH로 같이 씀
        log('check', `${b.name}: ${n}가 없어 설치합니다 (처음 한 번, 2~5분)`);
        const npm = WIN ? 'npm.cmd' : 'npm';
        const npx = WIN ? 'npx.cmd' : 'npx';
        const i1 = await sh('check', npm, ['i', n], { cwd: b.dir, timeoutMin: 10 });
        const i2 = i1.code === 0 && n === 'playwright' ? await sh('check', npx, ['playwright', 'install', 'chromium'], { cwd: b.dir, timeoutMin: 15 }) : i1;
        if (i1.code !== 0 || i2.code !== 0) problems.push(`${b.name}: ${n} 설치 실패 → 봇 폴더에서 직접: npm i ${n} && npx ${n} install chromium`);
      }
    }
  }
  if (!python()) log('check', '참고: Python 3.9+가 없어 모델 비교(compare_dl.py)는 건너뜁니다');
  if (problems.length) throw new Error(problems.join(' / '));
  log('check', `봇 ${run.chosen.length}개: ${run.chosen.map((b) => b.name).join(', ')} · 태그 ${o.tag} · 사람 ${o.humanMin}분`);
}

// 1단계 "깨끗하게 시작"을 화면이 대신 한다 (2026-11-24): 최신 코드 → 켜져 있던 앱 끄기 → 도커 → DB 패치 → Redis 비우기
async function stepPrep() {
  const o = run.opts;
  // 1. 최신 코드 (main이고 고친 파일이 없을 때만 받음. 이 화면 코드가 바뀌면 화면을 다시 켜야 함)
  if (o.pull) {
    const branch = git(['rev-parse', '--abbrev-ref', 'HEAD']).out;
    if (git(['fetch', '-q', 'origin', 'main']).code !== 0) log('prep', '참고: git fetch 실패 (인터넷?) — 지금 코드로 진행합니다');
    else {
      const behind = Number(git(['rev-list', '--count', 'HEAD..origin/main']).out || 0);
      const dirty = git(['status', '--porcelain', '--untracked-files=no']).out;
      if (!behind) log('prep', `코드: 최신입니다 (${branch})`);
      else if (branch !== 'main' || dirty) log('prep', `참고: main보다 ${behind}개 뒤인데 ${branch !== 'main' ? `지금 브랜치가 ${branch}` : '고친 파일이 있어'} 자동으로 받지 않습니다`);
      else {
        const before = git(['rev-parse', 'HEAD']).out;
        const pull = await sh('prep', 'git', ['pull', '--ff-only', 'origin', 'main']);
        if (pull.code !== 0) throw new Error('git pull 실패 (위 로그) — 직접 git pull origin main 뒤 다시');
        const changed = git(['diff', '--name-only', before, 'HEAD']).out.split('\n');
        if (changed.some((f) => f.startsWith('loadtest/contest/web/'))) {
          throw new Error('최신 코드에 진행 화면이 바뀌었습니다 → 이 화면 창에서 Ctrl+C, node loadtest/contest/web/server.js를 다시 켜고 [대회 시작]');
        }
        run.codeChanged = true;
        log('prep', `코드: ${behind}개 새로 받음`);
      }
    }
  }
  checkStop();
  // 2. 켜져 있던 앱 끄기 — Redis를 비우거나 코드가 바뀌면 앱을 새로 켜야 한다 (켜 둔 앱이 Redis에 다시 쓰고, 옛 화면이면 봇이 멈춤)
  if ((o.flush || run.codeChanged) && appChild) {
    log('prep', '이 화면이 전에 켠 앱을 끕니다 (새로 켬)');
    killTree(appChild);
  }
  if ((o.flush || run.codeChanged || o.restartApp) && (await healthy())) {
    const pids = appPids();
    if (!pids.length) throw new Error('8080에 앱이 켜져 있는데 끄지 못했습니다 → 그 창(IntelliJ·bootRun)에서 직접 끄고 다시 [대회 시작]');
    log('prep', `다른 곳에서 켠 앱을 끕니다 (PID ${pids.join(', ')}) — 대회는 최신 화면으로 새로 켠 앱으로 합니다`);
    for (const pid of pids) {
      if (WIN) spawnSync('taskkill', ['/PID', pid, '/T', '/F'], { windowsHide: true });
      else { try { process.kill(Number(pid), 'SIGTERM'); } catch {} }
    }
    for (let s = 0; s < 60 && (await healthy()); s += 2) await sleep(2000);
    if (await healthy()) throw new Error('앱이 꺼지지 않았습니다 → 그 창에서 직접 끄고 다시');
  }
  checkStop();
  // 3. 도커 (Oracle·MongoDB·Redis·Kafka)
  log('prep', '도커 켜기: docker compose up -d --wait (Oracle이 처음이면 몇 분)');
  const up = await sh('prep', 'docker', ['compose', 'up', '-d', '--wait'], { timeoutMin: 15 });
  if (up.code !== 0) throw new Error('docker compose up 실패 (위 로그). Docker Desktop이 켜져 있는지, .env가 있는지 확인하세요');
  checkStop();
  // 4. DB 패치: db/patch의 모든 패치를 번호 순서대로 (패치는 다시 돌려도 안전하게 쓰는 것이 규칙).
  //    중간 패치가 예전 값을 넣으려다 실패해도(예: v24의 옛 규칙 목록) 뒤 패치가 최신으로 다시 만들어서, 끝 상태를 마지막에 확인한다
  const pw = readEnv().ORACLE_APP_PASSWORD;
  if (!pw) throw new Error('.env에 ORACLE_APP_PASSWORD가 없습니다');
  const ver = (f) => Number((f.match(/^v(\d+)/) || [0, 0])[1]);
  const files = fs.readdirSync(path.join(ROOT, 'db/patch')).filter((f) => /^v\d+.*\.sql$/.test(f)).sort((a, b) => ver(a) - ver(b) || a.localeCompare(b));
  const connect = `CONNECT jejari/"${pw}"@localhost:1521/FREEPDB1\n`;
  const sqlplus = (input) => sh('prep', 'docker', ['compose', 'exec', '-T', '-e', 'NLS_LANG=AMERICAN_AMERICA.AL32UTF8', 'oracle',
    'bash', '-lc', 'sqlplus -S -L /nolog'], { input, quiet: true, timeoutMin: 5 });
  const warned = [];
  for (const f of files) {
    const r = await sqlplus(connect + fs.readFileSync(path.join(ROOT, 'db/patch', f), 'utf8') + '\nEXIT\n');
    const errs = r.out.split('\n').filter((l) => /ORA-\d|SP2-\d/.test(l));
    if (errs.some((l) => /ORA-01017|ORA-12541|ORA-12514|SP2-0640|SP2-0306/.test(l))) throw new Error(`Oracle 접속 실패: ${errs[0].trim()}`);
    if (errs.length) warned.push(`${f}: ${errs[0].trim()}`);
  }
  log('prep', `DB 패치 ${files.length}개 적용 (${files[0]} ~ ${files[files.length - 1]})`);
  for (const w of warned) log('prep', `  참고 (뒤 패치가 다시 맞춤): ${w}`);
  const chk = await sqlplus(connect + "SET HEADING OFF FEEDBACK OFF\nSELECT 'CHECK=' || COUNT(*) FROM user_constraints WHERE constraint_name = 'CK_CASE_MARKER_RULE_CODE' AND status = 'ENABLED';\nEXIT\n");
  if (!/CHECK=1/.test(chk.out)) throw new Error('DB 패치 뒤 규칙 이름 제약(ck_case_marker_rule_code)이 없습니다 → 진행자에게 알려 주세요');
  checkStop();
  // 5. Redis 비우기 (앱이 꺼진 뒤에. 1차 대회 사람이 결제에서 막힌 원인이 남은 리허설 기록이었음). 원본은 Oracle에 있음
  if (o.flush) {
    if (await healthy()) throw new Error('앱이 아직 켜져 있어 Redis를 비우지 않았습니다');
    const r = await sh('prep', 'docker', ['compose', 'exec', '-T', 'redis', 'redis-cli', 'FLUSHALL']);
    if (r.code !== 0 || !/OK/.test(r.out)) throw new Error('Redis 비우기 실패 (위 로그)');
    log('prep', 'Redis를 비웠습니다 (대기열·좌석 표시·시도 횟수. 원본 예약은 Oracle에 그대로)');
  }
}

async function stepApp() {
  if (await healthy()) {
    log('app', `앱이 이미 떠 있습니다 (${BASE_URL})`);
  } else {
    log('app', '앱 빌드·실행: gradlew buildFrontend bootRun (처음이면 3~5분, 로그는 results/app.log)');
    const logFile = fs.openSync(path.join(RESULTS, 'app.log'), 'a');
    const [cmd, args] = WIN ? ['cmd', ['/c', 'gradlew.bat', 'buildFrontend', 'bootRun']] : ['./gradlew', ['buildFrontend', 'bootRun']];
    appChild = spawn(cmd, args, { cwd: ROOT, stdio: ['ignore', logFile, logFile], detached: !WIN, windowsHide: true });
    appChild.on('close', (code) => { log('app', `앱이 꺼졌습니다 (종료 코드 ${code})`); appChild = null; pushState(); });
    for (let s = 0; s < 600 && !(await healthy()); s += 5) {
      checkStop();
      if (!appChild) throw new Error('앱이 뜨다가 꺼졌습니다 → loadtest/contest/results/app.log 끝부분을 보세요');
      if (s % 30 === 0) log('app', `앱 기다리는 중 ${s}초`);
      await sleep(5000);
    }
    if (!(await healthy())) throw new Error('10분 안에 앱이 뜨지 않았습니다 → results/app.log');
    log('app', '앱이 떴습니다. 이 화면을 끄면 앱도 같이 꺼집니다');
    pushState();
  }
  const page = await get('/');
  const type = page.headers.get('content-type') || '';
  if (!type.includes('text/html')) throw new Error('이용자 화면이 없습니다 (봇·사람이 브라우저로 씀) → ./gradlew buildFrontend 뒤 앱 재시작');
  const login = await fetch(BASE_URL + '/api/v1/admin/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ loginId: run.opts.adminId, password: run.opts.adminPw }),
  });
  if (!login.ok) throw new Error(`관리자 로그인 실패 (HTTP ${login.status}). SUPERVISOR 관리자 ID·비밀번호를 확인하세요`);
  log('app', '관리자 로그인 확인');
}

// 1차 대회(11-19)에 사람 참가자가 결제에서 막힌 원인이 "Redis에 남은 리허설 기록"이었다.
// 여기서는 IP별 가입·로그인 시도 카운터만 비운다 (시간이 지나면 저절로 사라지는 값이라 지워도 다른 데이터에 영향이 없다).
// 그 밖의 원인은 다음 단계의 "사람 흐름 확인"이 사람 초대 전에 잡는다.
async function stepClean() {
  for (const pat of ['rate:signup:*', 'rate:login:*']) {
    const r = await sh('clean', 'docker', ['compose', 'exec', '-T', 'redis', 'sh', '-c',
      `n=$(redis-cli --scan --pattern '${pat}' | wc -l); redis-cli --scan --pattern '${pat}' | xargs -r redis-cli DEL >/dev/null; echo "${pat} $n개 지움"`]);
    if (r.code !== 0) throw new Error('Redis에 접속하지 못했습니다 (docker compose ps로 redis 확인)');
  }
}

async function stepSetup() {
  const r = await sh('setup', 'bash', ['loadtest/contest/day.sh', 'start'], {
    // 행사 이름은 영문으로 (Windows Git Bash의 curl이 한글 본문을 깨뜨려 400)
    env: { ADMIN_ID: run.opts.adminId, ADMIN_PW: run.opts.adminPw, BASE_URL, NAME: `bot-contest-${run.tag}` },
  });
  run.setupStarted = true;
  const m = r.out.match(/대회 운행 ID = (\d+)\s+\(행사 (\d+), (\d{4}-\d{2}-\d{2})\)/);
  if (r.code !== 0 || !m) throw new Error('day.sh start 실패 (위 로그)');
  run.runId = m[1];
  run.runDate = m[3];
  log('setup', `대회 운행 ${run.runId} · 운행일 ${run.runDate}`);
  pushState();
}

function botEnv(b, mode) {
  return { ...b.env, BASE_URL, RUN_ID: run.runId, TAG: run.tag, MODE: mode, NODE_PATH: nodePath(discoverBots()) };
}

async function stepSignup() {
  await Promise.all(run.chosen.map(async (b) => {
    run.bots[b.name] = { phase: 'signup', status: 'running' };
    pushState();
    const [cmd, ...args] = b.cmd;
    const r = await sh('signup', cmd, args, { cwd: b.dir, env: botEnv(b, 'signup'), prefix: `${b.name} │ `, timeoutMin: 20 });
    run.bots[b.name] = { phase: 'signup', status: r.code === 0 ? 'ok' : 'failed', code: r.code };
    pushState();
  }));
  checkStop();
  const failed = run.chosen.filter((b) => run.bots[b.name].status !== 'ok');
  if (failed.length === run.chosen.length) throw new Error('모든 봇이 명의를 만들지 못했습니다 (위 로그)');
  if (failed.length) {
    log('signup', `명의를 못 만든 봇은 이번 대회에서 뺍니다: ${failed.map((b) => b.name).join(', ')}`);
    run.chosen = run.chosen.filter((b) => !failed.includes(b));
  }
}

// 사람 한 명이 하는 일(가입 → 로그인 → 대기열 → 좌석 → 결제 → 취소)을 API로 한 번 해 본다.
// 태그 뒤에 p를 붙인 계정이라(lt{태그}p.probe…) 평가(lt{태그}.…만 셈)에 들어가지 않는다. 좌석은 취소해 돌려놓는다.
async function stepProbe() {
  const H = { 'Content-Type': 'application/json', 'X-Load-Test': '1' };
  const call = async (method, p, body, extra = {}) => {
    const r = await fetch(BASE_URL + p, { method, headers: { ...H, ...extra }, body: body == null ? undefined : JSON.stringify(body) });
    const text = await r.text();
    let j = {};
    try { j = JSON.parse(text); } catch {}
    return { status: r.status, j, text: text.slice(0, 200) };
  };
  const must = (r, what, ok = [200, 201]) => {
    if (!ok.includes(r.status)) throw new Error(`사람 흐름이 "${what}"에서 막힘: HTTP ${r.status} ${r.j.code || ''} ${r.j.message || r.text}`);
    return r;
  };
  const digits = String(Math.floor(Math.random() * 1e7)).padStart(7, '0') + '1';
  const email = `lt${run.tag}p.probe.g1.1@loadtest.example.com`;
  const password = `Hq7!${run.tag}Wp#${digits.slice(0, 4)}xK`;
  const v = must(await call('POST', '/api/v1/auth/verify', { name: '테스트점검', phone: `010${digits}`, provider: 'MOCK_PASS' }), '본인인증');
  const s = await call('POST', '/api/v1/auth/signup', { verificationToken: v.j.verificationToken, email, password });
  if (s.status === 409) throw new Error(`점검 계정 ${email}이 이미 있습니다 → 태그를 바꾸세요 (같은 태그로 두 번 연 대회는 섞입니다)`);
  must(s, '가입');
  const token = must(await call('POST', '/api/v1/auth/login', { email, password }), '로그인').j.accessToken;
  const auth = { Authorization: `Bearer ${token}` };
  let q = must(await call('POST', `/api/v1/runs/${run.runId}/queue`, null, auth), '대기열 진입').j;
  for (let t = 0; q.state === 'WAITING' && t < 60; t++) {
    await sleep(Math.max(500, (q.pollIntervalSec || 1) * 1000));
    q = must(await call('GET', `/api/v1/runs/${run.runId}/queue/me`, null, auth), '대기열').j;
  }
  if (q.state !== 'ADMITTED') throw new Error(`사람 흐름이 대기열에서 입장하지 못함 (state ${q.state})`);
  const from = '3900023';
  const to = '3900114';
  const pass = { ...auth, 'X-Queue-Token': q.passToken };
  const map = must(await call('GET', `/api/v1/runs/${run.runId}/seats?from=${from}&to=${to}`, null, pass), '좌석 배치도').j;
  const seats = (map.cars || []).flatMap((c) => (c.seats || []).filter((x) => x.state === 'EMPTY').map((x) => x.seatId));
  if (!seats.length) throw new Error('빈 좌석이 없습니다');
  const seatId = seats[Math.floor(Math.random() * seats.length)];
  const h = must(await call('POST', '/api/v1/reservations',
    { runId: Number(run.runId), fromStationId: from, toStationId: to, seats: [{ seatId, passengerType: 'ADULT' }], channel: 'WEB' },
    { ...pass, 'Idempotency-Key': randomUUID() }), '좌석 선점').j;
  const p = must(await call('POST', `/api/v1/reservations/${h.reservationId}/payments`, { cardNumber: '4111222233334441' },
    { ...auth, 'Idempotency-Key': randomUUID() }), '결제').j;
  if (p.paymentStatus !== 'APPROVED') throw new Error(`사람 흐름의 결제가 승인되지 않음 (${p.paymentStatus})`);
  await call('DELETE', `/api/v1/runs/${run.runId}/queue/me`, null, auth);
  const c = await call('POST', `/api/v1/reservations/${h.reservationId}/cancel`, null, auth);
  log('probe', `가입·로그인·대기열·좌석·결제 통과 (예약 ${h.reservationId}${c.status < 300 ? ', 취소해 좌석 돌려놓음' : ', 취소는 실패 — 좌석 1석이 남음'})`);
}

function lanUrls() {
  const port = new URL(BASE_URL).port || '8080';
  const out = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    if (/docker|vethernet|wsl|br-|veth|virtualbox|vmware|loopback/i.test(name)) continue;
    for (const a of list || []) if (a.family === 'IPv4' && !a.internal) out.push(`http://${a.address}:${port}`);
  }
  return out;
}

async function stepPlay() {
  const o = run.opts;
  const until = Date.now() + o.humanMin * 60000;
  run.human = { urls: lanUrls(), until, runDate: run.runDate, email: `lt${run.tag}.human.g{번호}.{번호}@loadtest.example.com` };
  pushState();
  log('play', `사람 참가 ${o.humanMin}분 시작. 접속 주소: ${run.human.urls.join(' · ') || '(못 찾음 — ipconfig로 확인)'}`);
  log('play', `사람 이메일: ${run.human.email} · 서울 → 부산 · ${run.runDate}`);

  const botsDone = Promise.all(run.chosen.map(async (b) => {
    if (o.botDelayMin > 0) await sleep(o.botDelayMin * 60000);
    if (run.stop) return;
    run.bots[b.name] = { phase: 'buy', status: 'running' };
    pushState();
    const [cmd, ...args] = b.cmd;
    const r = await sh('play', cmd, args, { cwd: b.dir, env: botEnv(b, 'buy'), prefix: `${b.name} │ `, timeoutMin: BOT_MAX_MIN });
    run.bots[b.name] = { phase: 'buy', status: r.code === 0 ? 'ok' : 'failed', code: r.code };
    pushState();
  }));
  let finished = false;
  botsDone.then(() => { finished = true; log('play', '봇이 모두 끝났습니다'); });
  while (!run.stop && !run.skipWait && Date.now() < until) await sleep(1000);
  if (!finished && !run.stop) log('play', '사람 시간이 끝났습니다. 아직 도는 봇을 기다립니다');
  while (!finished && !run.stop) await sleep(1000);
  checkStop();
  run.human.until = null;
  pushState();
}

async function stepEval() {
  const dir = path.join(RESULTS, run.tag);
  const e = await sh('eval', 'bash', ['loadtest/tools/eval_contest.sh', run.tag, run.runId], { timeoutMin: 15 });
  fs.writeFileSync(path.join(dir, 'eval.txt'), e.out);
  run.results.eval = e.out;
  pushState();
  if (e.code !== 0) throw new Error('규칙 엔진 평가 실패 (위 로그)');
  const ml = `data/ml/contest-${run.tag}`;
  const x = await sh('eval', 'bash', ['tools/ml/export_live.sh', run.tag, ml], { timeoutMin: 10 });
  if (x.code !== 0) { log('eval', '행동 로그 꺼내기 실패 — 모델 비교는 건너뜁니다'); return; }
  const py = python();
  if (!py) { log('eval', 'Python이 없어 모델 비교를 건너뜁니다'); return; }
  const blind = run.chosen.filter((b) => b.blind).map((b) => b.name);
  const args = ['-I', 'tools/ml/compare_dl.py', ml, ...(blind.length ? ['--bots', blind.join(',')] : [])];
  const c = await sh('eval', py, args, { timeoutMin: 20 });
  const md = path.join(ROOT, ml, 'compare.md');
  if (c.code === 0 && fs.existsSync(md)) {
    run.results.compare = fs.readFileSync(md, 'utf8');
    fs.copyFileSync(md, path.join(dir, 'compare.md'));
  } else log('eval', '모델 비교 실패 (위 로그). 규칙 엔진 결과는 그대로 유효합니다');
  pushState();
}

async function stepRestore() {
  if (!run.setupStarted) { log('restore', '가입 제한을 바꾸기 전이라 되돌릴 것이 없습니다'); return; }
  const r = await sh('restore', 'bash', ['loadtest/contest/day.sh', 'restore'], {
    env: { ADMIN_ID: run.opts.adminId, ADMIN_PW: run.opts.adminPw, BASE_URL },
  });
  if (r.code !== 0) throw new Error('되돌리기 실패 → 직접: ADMIN_ID=… ADMIN_PW=… loadtest/contest/day.sh restore');
}

async function pipeline() {
  const steps = { check: stepCheck, prep: stepPrep, app: stepApp, clean: stepClean, setup: stepSetup, signup: stepSignup,
    probe: stepProbe, play: stepPlay, eval: stepEval };
  try {
    for (const [id] of STEPS) {
      if (id === 'restore') break;
      checkStop();
      setStep(id, 'running');
      try {
        await steps[id]();
        setStep(id, 'ok');
      } catch (e) {
        const why = run.stop ? '중지 버튼을 눌러 멈췄습니다' : e.message;
        setStep(id, 'failed', why);
        throw new Error(why);
      }
    }
  } catch (e) {
    run.failed = e.message;
    log('restore', `멈춤: ${e.message}`);
    for (const p of children) killTree(p);
  } finally {
    setStep('restore', 'running');
    try { await stepRestore(); setStep('restore', 'ok'); } catch (e) { setStep('restore', 'failed', e.message); }
    run.done = true;
    run.opts.adminPw = null; // 끝나면 비밀번호를 지운다
    try {
      fs.writeFileSync(path.join(RESULTS, run.tag, 'summary.json'), JSON.stringify({
        tag: run.tag, runId: run.runId, runDate: run.runDate, startedAt: new Date(run.startedAt).toISOString(),
        endedAt: new Date().toISOString(), failed: run.failed, steps: run.steps, bots: run.bots,
      }, null, 2));
    } catch {}
    pushState();
  }
}

// ── HTTP ──────────────────────────────────────────────────────────
function body(req) {
  return new Promise((resolve) => {
    let s = '';
    req.on('data', (c) => { s += c; if (s.length > 40 * 1024 * 1024) req.destroy(); });
    req.on('end', () => { try { resolve(JSON.parse(s || '{}')); } catch { resolve({}); } });
  });
}
function json(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(obj));
}

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  if (req.method === 'GET' && url.pathname === '/') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(fs.readFileSync(path.join(__dirname, 'index.html')));
  }
  if (req.method === 'GET' && url.pathname === '/api/state') return json(res, 200, publicState());
  if (req.method === 'GET' && url.pathname === '/api/guide') {
    res.writeHead(200, { 'Content-Type': 'text/markdown; charset=utf-8' });
    return res.end(fs.readFileSync(path.join(CONTEST, '진행-화면-사용법.md')));
  }
  if (req.method === 'POST' && (url.pathname === '/api/bots' || url.pathname === '/api/bots/remove')) {
    if (run && !run.done) return json(res, 409, { error: '대회 중에는 봇을 바꿀 수 없습니다' });
    const b = await body(req);
    try {
      if (url.pathname === '/api/bots') addBot(b);
      else removeBot(String(b.name || ''));
    } catch (e) { return json(res, 400, { error: e.message }); }
    pushState();
    return json(res, 200, { ok: true });
  }
  if (req.method === 'GET' && url.pathname === '/api/events') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
    res.write(`data: ${JSON.stringify({ type: 'state', state: publicState() })}\n\n`);
    for (const l of recent) res.write(`data: ${JSON.stringify({ type: 'log', ...l })}\n\n`);
    clients.add(res);
    const ping = setInterval(() => res.write(': ping\n\n'), 15000);
    return req.on('close', () => { clearInterval(ping); clients.delete(res); });
  }
  if (req.method === 'POST' && url.pathname === '/api/start') {
    if (run && !run.done) return json(res, 409, { error: '이미 대회가 진행 중입니다' });
    const b = await body(req);
    const tag = String(b.tag || '').trim();
    if (!/^[a-z0-9]{1,7}$/.test(tag)) return json(res, 400, { error: '태그는 영문 소문자·숫자 1~7자' });
    if (fs.existsSync(path.join(RESULTS, tag, 'summary.json'))) return json(res, 400, { error: `태그 ${tag}는 이미 쓴 적이 있습니다. 다른 태그를 고르세요` });
    const env = readEnv(); // 비워 두면 .env의 처음 관리자 계정 (JEJARI_ADMIN_BOOTSTRAP_ID / _PASSWORD)
    b.adminId = String(b.adminId || '').trim() || env.JEJARI_ADMIN_BOOTSTRAP_ID;
    b.adminPw = b.adminPw || env.JEJARI_ADMIN_BOOTSTRAP_PASSWORD;
    if (!b.adminId || !b.adminPw) return json(res, 400, { error: '관리자 ID와 비밀번호를 넣으세요 (.env에도 없습니다)' });
    const all = discoverBots().filter((x) => x.ok);
    const chosen = all.filter((x) => (b.bots || []).includes(x.name));
    if (!chosen.length) return json(res, 400, { error: '봇을 하나 이상 고르세요' });
    const humanMin = Math.min(120, Math.max(0, Number(b.humanMin ?? 10)));
    const botDelayMin = Math.min(60, Math.max(0, Number(b.botDelayMin ?? 0)));
    fs.mkdirSync(path.join(RESULTS, tag), { recursive: true });
    recent.length = 0;
    run = fresh({ tag, adminId: String(b.adminId), adminPw: String(b.adminPw), humanMin, botDelayMin, bots: chosen.map((x) => x.name),
      pull: b.pull !== false, flush: b.flush !== false, restartApp: !!b.restartApp });
    run.chosen = chosen;
    pushState();
    pipeline();
    return json(res, 200, { ok: true });
  }
  if (req.method === 'POST' && url.pathname === '/api/skip-wait') {
    if (run && !run.done) { run.skipWait = true; log('play', '사람 시간을 지금 끝냅니다 (진행자)'); }
    return json(res, 200, { ok: true });
  }
  if (req.method === 'POST' && url.pathname === '/api/stop') {
    if (run && !run.done) {
      run.stop = true;
      log('restore', '중지 요청 — 돌던 봇을 멈추고 정리합니다');
      for (const p of children) killTree(p);
    }
    return json(res, 200, { ok: true });
  }
  res.writeHead(404);
  res.end();
}).listen(PORT, '127.0.0.1', () => {
  fs.mkdirSync(RESULTS, { recursive: true });
  const bots = discoverBots();
  console.log(`봇 대회 진행 화면: http://localhost:${PORT}  (앱 ${BASE_URL})`);
  console.log(`봇: ${bots.map((b) => (b.ok ? b.name : `${b.name}(×: ${b.problem})`)).join(', ') || '없음'}`);
});

function shutdown() {
  for (const p of children) killTree(p);
  if (appChild) { console.log('이 화면이 켠 앱을 끕니다'); killTree(appChild); }
  process.exit(0);
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
