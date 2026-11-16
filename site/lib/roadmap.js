// 팀 공유 로드맵 보드. 모두 같은 데이터를 보도록 D1(AI_DB 바인딩, 같은 데이터베이스에 표만 따로)에 저장한다.
//   roadmap_item  카드 한 장 = 한 행 (v: 동시 수정 확인용 버전)
//   roadmap_log   누가 언제 무엇을 바꿨는지 + 개발자에게 남긴 말(사진 포함). 가장 큰 seq가 보드 전체 버전(rev)
//   roadmap_img   메모에 붙인 사진 (브라우저에서 줄여 보낸 base64)
//   roadmap_meta  처음 카드를 넣었는지 등
// 보드 = 기능 영역(area)별 칸, 카드 = 할 일, 카드 안 체크 = 맡은 파트(A~E)별 했다/안 했다.
import { MEMBERS } from './config.js';
import { SEED } from './roadmap-seed.js';

export const PARTS = {
  A: { owner: 'SeongWon', label: '대기열·HMAC' },
  B: { owner: 'DongGyun', label: '좌석 동시성·열차' },
  C: { owner: 'DongHyeon', label: 'Kafka·결제·Saga' },
  D: { owner: 'DaIn', label: '로그·위험 점수·취소·회원' },
  E: { owner: 'YoungJun', label: '인프라·CI·k6·JWT' },
};
const P5 = Object.keys(PARTS);
// 기능 영역 (보드의 칸). 코드 앞부분으로 처음 영역을 정한다
export const AREAS = [
  ['auth', '회원·인증', ['AUTH', 'SEC', 'PII']],
  ['train', '열차 조회', ['TRN']],
  ['queue', '대기열', ['QUE', 'HMAC']],
  ['seat', '좌석 예약', ['RSV', 'LOCK']],
  ['pay', '결제', ['PAY']],
  ['cancel', '취소·환불', ['CNL']],
  ['wait', '취소표', ['WL']],
  ['event', '이벤트 (Kafka·Outbox·Saga)', ['EVT']],
  ['risk', '위험 점수', ['RISK']],
  ['admin', '관리자 화면', ['ADM']],
  ['data', '데이터', ['DATA']],
  ['infra', '인프라·성능', ['INF', 'PERF']],
  ['test', '테스트·발표', ['TEST', 'DOC']],
  ['etc', '기타', []],
];
const AREA_KEYS = AREAS.map((a) => a[0]);
const areaOf = (code) => { const p = String(code || '').split('-')[0]; const a = AREAS.find((x) => x[2].includes(p)); return a ? a[0] : 'etc'; };
const PRIOS = ['P1', 'P2', 'P3'];
const LOG_LIMIT = 80;
const IMG_MAX = 1400000; // base64 글자 수 (약 1MB)

const SCHEMA = [
  'CREATE TABLE IF NOT EXISTS roadmap_item (id TEXT PRIMARY KEY, v INTEGER NOT NULL, at INTEGER NOT NULL, data TEXT NOT NULL)',
  `CREATE TABLE IF NOT EXISTS roadmap_log (
     seq  INTEGER PRIMARY KEY AUTOINCREMENT,
     at   INTEGER NOT NULL,
     who  TEXT NOT NULL,
     item TEXT,
     act  TEXT NOT NULL,
     data TEXT NOT NULL
   )`,
  'CREATE INDEX IF NOT EXISTS ix_roadmap_log_item ON roadmap_log (item, seq)',
  'CREATE TABLE IF NOT EXISTS roadmap_img (id TEXT PRIMARY KEY, item TEXT, at INTEGER NOT NULL, who TEXT NOT NULL, mime TEXT NOT NULL, data TEXT NOT NULL)',
  'CREATE TABLE IF NOT EXISTS roadmap_meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)',
];

const rid = (p) => p + [...crypto.getRandomValues(new Uint8Array(6))].map((b) => b.toString(36).padStart(2, '0')).join('').slice(0, 10);
const parse = (s) => { try { return JSON.parse(s); } catch { return null; } };
const isStr = (v, max, min = 0) => typeof v === 'string' && v.length <= max && v.trim().length >= min;

let ready = null;
async function init(db) {
  ready = ready || db.batch(SCHEMA.map((q) => db.prepare(q))).catch((e) => { ready = null; throw e; });
  await ready;
  const now = Date.now();
  const r = await db.prepare("INSERT OR IGNORE INTO roadmap_meta (k, v) VALUES ('seeded', ?1)").bind(String(now)).run();
  if (r.meta && r.meta.changes) await seed(db, now);
}

const emptyRoles = (parts) => Object.fromEntries(parts.map((p) => [p, { done: false, by: null, at: null }]));
async function seed(db, now) {
  const ids = Object.fromEntries(SEED.map(([code]) => [code, rid('r')]));
  const stmts = SEED.map(([code, title, part, prio, week, deps, withPart], i) => {
    const parts = part === 'ALL' ? P5 : [part, ...(withPart ? [withPart] : [])];
    const item = {
      id: ids[code], code, title, area: areaOf(code), roles: emptyRoles(parts), blocked: false, prio, week,
      deps: deps.map((c) => ids[c]).filter(Boolean), note: '', due: '',
      createdBy: 'seed', createdAt: now, updatedBy: 'seed', updatedAt: now, order: i,
    };
    return db.prepare('INSERT OR IGNORE INTO roadmap_item (id, v, at, data) VALUES (?1, 1, ?2, ?3)').bind(item.id, now, JSON.stringify(item));
  });
  stmts.push(db.prepare("INSERT INTO roadmap_log (at, who, item, act, data) VALUES (?1, 'seed', NULL, 'seed', ?2)")
    .bind(now, JSON.stringify({ text: `기능설계서에서 할 일 ${SEED.length}개를 가져왔어요.` })));
  await db.batch(stmts);
}

// 예전 모양(part·with·status·doneBy)으로 저장된 카드도 보드 모양(area·roles)으로 읽는다
function normalize(it) {
  if (!it.roles) {
    const parts = it.part === 'ALL' || !PARTS[it.part] ? P5 : [it.part, ...(it.with || []).filter((p) => PARTS[p] && p !== it.part)];
    const done = it.status === 'done';
    it.roles = Object.fromEntries(parts.map((p) => [p, { done, by: done ? it.doneBy || null : null, at: done ? it.doneAt || null : null }]));
    it.blocked = it.status === 'blocked';
  }
  if (!AREA_KEYS.includes(it.area)) it.area = areaOf(it.code);
  delete it.part; delete it.with; delete it.status; delete it.doneBy; delete it.doneAt; delete it.owner;
  return it;
}
const rowItem = (r) => { const it = parse(r.data); return it ? { ...normalize(it), v: r.v } : null; };
const rowLog = (r) => ({ seq: r.seq, at: r.at, who: r.who, item: r.item, act: r.act, ...(parse(r.data) || {}) });

async function getItem(db, id) {
  const r = await db.prepare('SELECT v, data FROM roadmap_item WHERE id = ?1').bind(id).first();
  return r ? rowItem(r) : null;
}
async function rev(db) {
  const r = await db.prepare('SELECT MAX(seq) AS s FROM roadmap_log').first();
  return (r && r.s) || 0;
}
const addLog = (db, who, item, act, data) => db.prepare('INSERT INTO roadmap_log (at, who, item, act, data) VALUES (?1, ?2, ?3, ?4, ?5)')
  .bind(Date.now(), who, item, act, JSON.stringify(data));

// 들어온 카드를 검사해 저장할 모양으로 만든다. 문제가 있으면 문자열(메시지)을 돌려준다.
function clean(input, ids) {
  if (!input || typeof input !== 'object') return '카드 형식이 맞지 않아요.';
  const it = {};
  if (!isStr(input.title, 200, 1)) return '제목은 1~200자로 써 주세요.';
  it.title = input.title.trim();
  it.code = isStr(input.code || '', 24) ? String(input.code || '').trim().toUpperCase() : null;
  if (it.code == null || /\s/.test(it.code)) return '코드는 띄어쓰기 없이 24자 이내예요 (예: QUE-01).';
  it.area = AREA_KEYS.includes(input.area) ? input.area : areaOf(it.code);
  const roles = input.roles && typeof input.roles === 'object' ? input.roles : {};
  const parts = P5.filter((p) => roles[p]);
  if (!parts.length) return '맡을 파트를 하나 이상 골라 주세요.';
  it.roles = Object.fromEntries(parts.map((p) => [p, { done: !!roles[p].done }]));
  it.blocked = !!input.blocked;
  it.prio = PRIOS.includes(input.prio) ? input.prio : 'P1';
  it.week = [0, 1, 2, 3, 4].includes(Number(input.week)) ? Number(input.week) : 0;
  it.due = /^\d{4}-\d{2}-\d{2}$/.test(input.due || '') ? input.due : '';
  if (!isStr(input.note || '', 2000)) return '설명은 2000자까지예요.';
  it.note = String(input.note || '');
  const deps = Array.isArray(input.deps) ? input.deps : [];
  if (deps.length > 20) return '선행 작업은 20개까지예요.';
  it.deps = [...new Set(deps.filter((d) => typeof d === 'string' && ids.has(d)))];
  return it;
}
// 체크한 파트에 누가·언제를 붙인다 (원래 체크돼 있던 건 그대로)
function stampRoles(next, prev, me, now) {
  const out = {};
  for (const [p, r] of Object.entries(next)) {
    const o = prev && prev[p];
    out[p] = r.done ? (o && o.done ? o : { done: true, by: me, at: now }) : { done: false, by: null, at: null };
  }
  return out;
}

const FIELDS = ['title', 'code', 'area', 'blocked', 'prio', 'week', 'due', 'note', 'deps'];
function diff(a, b) {
  const out = [];
  for (const f of FIELDS) {
    const x = a[f], y = b[f];
    if (JSON.stringify(x == null ? '' : x) === JSON.stringify(y == null ? '' : y)) continue;
    if (f === 'note') out.push([f]);
    else if (f === 'deps') out.push([f, (x || []).length, (y || []).length]);
    else out.push([f, x == null ? '' : x, y == null ? '' : y]);
  }
  const ra = a.roles || {}, rb = b.roles || {};
  for (const p of P5) {
    if (!ra[p] && rb[p]) out.push(['role+', p]);
    else if (ra[p] && !rb[p]) out.push(['role-', p]);
    else if (ra[p] && rb[p] && ra[p].done !== rb[p].done) out.push(['check', p, rb[p].done]);
  }
  return out;
}

export function roadmap(env) {
  const db = env.AI_DB;
  if (!db) return null;

  async function write(me, cur, item, now) {
    const changes = diff(cur, item);
    if (!changes.length) return { item: cur, same: true };
    const data = { ...item, updatedBy: me, updatedAt: now };
    delete data.v; delete data.notes;
    const r = await db.prepare('UPDATE roadmap_item SET v = v + 1, at = ?1, data = ?2 WHERE id = ?3 AND v = ?4')
      .bind(now, JSON.stringify(data), cur.id, cur.v).run();
    if (!r.meta || !r.meta.changes) return null;
    const act = changes.every((c) => c[0] === 'check') ? 'check' : 'edit';
    await addLog(db, me, cur.id, act, { title: data.title, code: data.code, changes }).run();
    return { item: { ...data, v: cur.v + 1 } };
  }

  return {
    async all() {
      await init(db);
      const [items, logs, r, notes] = await Promise.all([
        db.prepare('SELECT v, data FROM roadmap_item').all(),
        db.prepare(`SELECT seq, at, who, item, act, data FROM roadmap_log WHERE act <> 'unnote' ORDER BY seq DESC LIMIT ${LOG_LIMIT}`).all(),
        rev(db),
        db.prepare("SELECT seq, at, who, item, data FROM roadmap_log WHERE act = 'note' ORDER BY seq").all(),
      ]);
      // 카드마다 남긴 말 수, 파트별로 받은 말 수
      const nc = new Map(); const msgs = [];
      for (const x of notes.results || []) {
        const d = parse(x.data) || {};
        if (d.to) msgs.push({ seq: x.seq, at: x.at, who: x.who, item: x.item, to: d.to, text: d.text, img: d.img, title: d.title, code: d.code });
        const c = nc.get(x.item) || { n: 0, to: {} };
        c.n++; if (d.to) c.to[d.to] = (c.to[d.to] || 0) + 1;
        nc.set(x.item, c);
      }
      return {
        rev: r, parts: PARTS, areas: AREAS.map(([k, label]) => ({ k, label })),
        items: (items.results || []).map(rowItem).filter(Boolean).map((it) => ({ ...it, notes: (nc.get(it.id) || {}).n || 0, notesTo: (nc.get(it.id) || {}).to || {} })),
        log: (logs.results || []).map(rowLog),
        msgs: msgs.reverse().slice(0, 60),
      };
    },
    async rev() { await init(db); return rev(db); },
    async itemLog(id) {
      await init(db);
      const r = await db.prepare('SELECT seq, at, who, item, act, data FROM roadmap_log WHERE item = ?1 ORDER BY seq DESC LIMIT 200').bind(id).all();
      return (r.results || []).map(rowLog);
    },

    // 새 카드(id 없음) 또는 수정(id + v). 결과: {item} 또는 {error, status, current?}
    async save(me, input, v) {
      await init(db);
      const idRows = await db.prepare('SELECT id FROM roadmap_item').all();
      const ids = new Set((idRows.results || []).map((x) => x.id));
      const it = clean(input, ids);
      if (typeof it === 'string') return { error: 'BAD_ITEM', status: 400, message: it };
      const now = Date.now();

      if (!input.id) {
        if (ids.size >= 500) return { error: 'TOO_MANY', status: 400, message: '카드는 500개까지예요.' };
        const item = { id: rid('r'), ...it, roles: stampRoles(it.roles, null, me, now), createdBy: me, createdAt: now, updatedBy: me, updatedAt: now, order: now };
        await db.batch([
          db.prepare('INSERT INTO roadmap_item (id, v, at, data) VALUES (?1, 1, ?2, ?3)').bind(item.id, now, JSON.stringify(item)),
          addLog(db, me, item.id, 'add', { title: item.title, code: item.code }),
        ]);
        return { item: { ...item, v: 1 } };
      }

      const cur = await getItem(db, input.id);
      if (!cur) return { error: 'GONE', status: 404, message: '그 사이 누가 이 카드를 지웠어요.' };
      if (Number(v) !== cur.v) return { error: 'STALE', status: 409, message: '그 사이 누가 이 카드를 바꿨어요. 최신 내용을 불러왔어요.', current: cur };
      it.deps = it.deps.filter((d) => d !== cur.id);
      const item = { ...cur, ...it, roles: stampRoles(it.roles, cur.roles, me, now) };
      const r = await write(me, cur, item, now);
      return r || { error: 'STALE', status: 409, message: '그 사이 누가 이 카드를 바꿨어요. 최신 내용을 불러왔어요.', current: await getItem(db, cur.id) };
    },

    // 파트 체크 하나만 바꾼다. 다른 사람이 같은 카드의 다른 칸을 동시에 눌러도 겹치지 않게 버전을 다시 읽어 재시도
    async check(me, id, part, done) {
      await init(db);
      for (let i = 0; i < 4; i++) {
        const cur = await getItem(db, id);
        if (!cur) return { error: 'GONE', status: 404, message: '그 사이 누가 이 카드를 지웠어요.' };
        if (!cur.roles[part]) return { error: 'NO_ROLE', status: 400, message: '이 카드를 맡은 파트가 아니에요.' };
        const now = Date.now();
        const roles = { ...cur.roles, [part]: done ? (cur.roles[part].done ? cur.roles[part] : { done: true, by: me, at: now }) : { done: false, by: null, at: null } };
        const r = await write(me, cur, { ...cur, roles }, now);
        if (r) return r;
      }
      return { error: 'BUSY', status: 409, message: '여러 사람이 동시에 바꾸고 있어요. 다시 눌러 주세요.' };
    },

    async remove(me, id) {
      await init(db);
      const cur = await getItem(db, id);
      if (!cur) return { error: 'GONE', status: 404, message: '이미 지워진 카드예요.' };
      // 이 카드를 선행 작업으로 둔 다른 카드에서 빼 준다
      const all = await db.prepare('SELECT v, data FROM roadmap_item').all();
      const stmts = [db.prepare('DELETE FROM roadmap_item WHERE id = ?1').bind(id)];
      for (const row of all.results || []) {
        const it = rowItem(row);
        if (!it || it.id === id || !(it.deps || []).includes(id)) continue;
        const next = { ...it, deps: it.deps.filter((d) => d !== id) };
        delete next.v;
        stmts.push(db.prepare('UPDATE roadmap_item SET v = v + 1, data = ?1 WHERE id = ?2').bind(JSON.stringify(next), it.id));
      }
      stmts.push(db.prepare('DELETE FROM roadmap_img WHERE item = ?1').bind(id));
      stmts.push(addLog(db, me, id, 'delete', { title: cur.title, code: cur.code }));
      await db.batch(stmts);
      return { ok: true };
    },

    // 개발자에게 남기는 말 (to: 받는 파트 A~E, 없으면 모두에게). img는 data:image/...;base64,...
    async note(me, id, text, img, to) {
      await init(db);
      const cur = await getItem(db, id);
      if (!cur) return { error: 'GONE', status: 404, message: '그 사이 누가 이 카드를 지웠어요.' };
      if (!isStr(text || '', 2000)) return { error: 'BAD_NOTE', status: 400, message: '메모는 2000자까지예요.' };
      if (to && !PARTS[to]) return { error: 'BAD_NOTE', status: 400, message: '받는 파트가 맞지 않아요.' };
      let imgId = null; let stmtImg = null;
      if (img) {
        const m = /^data:(image\/(?:png|jpeg|webp|gif));base64,([A-Za-z0-9+/=]+)$/.exec(img);
        if (!m) return { error: 'BAD_IMG', status: 400, message: '사진은 PNG·JPEG·WebP·GIF만 돼요.' };
        if (m[2].length > IMG_MAX) return { error: 'BIG_IMG', status: 400, message: '사진이 너무 커요 (1MB 이하).' };
        imgId = rid('i');
        stmtImg = db.prepare('INSERT INTO roadmap_img (id, item, at, who, mime, data) VALUES (?1, ?2, ?3, ?4, ?5, ?6)').bind(imgId, id, Date.now(), me, m[1], m[2]);
      }
      if (!String(text || '').trim() && !imgId) return { error: 'BAD_NOTE', status: 400, message: '메모나 사진이 필요해요.' };
      const stmts = [addLog(db, me, id, 'note', { title: cur.title, code: cur.code, text: String(text || '').trim(), img: imgId, to: to || '' })];
      if (stmtImg) stmts.unshift(stmtImg);
      await db.batch(stmts);
      return { ok: true };
    },
    async noteDelete(me, seq) {
      await init(db);
      const r = await db.prepare("SELECT seq, who, data FROM roadmap_log WHERE seq = ?1 AND act = 'note'").bind(Number(seq)).first();
      if (!r) return { error: 'GONE', status: 404, message: '이미 지워진 메모예요.' };
      if (r.who !== me) return { error: 'NOT_MINE', status: 403, message: '내가 쓴 메모만 지울 수 있어요.' };
      const d = parse(r.data) || {};
      // 지운 것도 다른 사람 화면이 새로 불러오도록 기록을 남긴다 (화면 피드에서는 숨김)
      const stmts = [db.prepare('DELETE FROM roadmap_log WHERE seq = ?1').bind(r.seq), addLog(db, me, null, 'unnote', { seq: r.seq })];
      if (d.img) stmts.push(db.prepare('DELETE FROM roadmap_img WHERE id = ?1').bind(d.img));
      await db.batch(stmts);
      return { ok: true };
    },
    async img(id) {
      await init(db);
      return db.prepare('SELECT mime, data FROM roadmap_img WHERE id = ?1').bind(id).first();
    },
  };
}
