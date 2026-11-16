// 팀 공유 로드맵. 모두 같은 데이터를 보도록 D1(AI_DB 바인딩, 같은 데이터베이스에 표만 따로)에 저장한다.
//   roadmap_item  항목 한 개 = 한 행 (v: 동시 수정 확인용 버전)
//   roadmap_log   누가 언제 무엇을 바꿨는지 + 진행 메모(사진 포함). 가장 큰 seq가 로드맵 전체 버전(rev)
//   roadmap_img   진행 메모에 붙인 사진 (브라우저에서 줄여 보낸 base64)
//   roadmap_meta  처음 항목을 넣었는지 등
import { MEMBERS } from './config.js';
import { SEED } from './roadmap-seed.js';

export const PARTS = {
  A: { owner: 'SeongWon', label: '대기열·HMAC' },
  B: { owner: 'DongGyun', label: '좌석 동시성·열차' },
  C: { owner: 'DongHyeon', label: 'Kafka·결제·Saga' },
  D: { owner: 'DaIn', label: '로그·위험 점수·취소·회원' },
  E: { owner: 'YoungJun', label: '인프라·CI·k6·JWT' },
  ALL: { owner: '', label: '공통' },
};
export const STATUSES = ['todo', 'doing', 'blocked', 'done'];
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

async function seed(db, now) {
  const ids = Object.fromEntries(SEED.map(([code]) => [code, rid('r')]));
  const stmts = SEED.map(([code, title, part, prio, week, deps, withPart], i) => {
    const item = {
      id: ids[code], code, title, part, owner: PARTS[part].owner, status: 'todo', prio, week,
      deps: deps.map((c) => ids[c]).filter(Boolean), with: withPart ? [withPart] : [], note: '', due: '',
      doneBy: null, doneAt: null, createdBy: 'seed', createdAt: now, updatedBy: 'seed', updatedAt: now, order: i,
    };
    return db.prepare('INSERT OR IGNORE INTO roadmap_item (id, v, at, data) VALUES (?1, 1, ?2, ?3)').bind(item.id, now, JSON.stringify(item));
  });
  stmts.push(db.prepare("INSERT INTO roadmap_log (at, who, item, act, data) VALUES (?1, 'seed', NULL, 'seed', ?2)")
    .bind(now, JSON.stringify({ text: `기능설계서에서 항목 ${SEED.length}개를 가져왔어요.` })));
  await db.batch(stmts);
}

const rowItem = (r) => { const it = parse(r.data); return it ? { ...it, v: r.v } : null; };
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

// 들어온 항목을 검사해 저장할 모양으로 만든다. 문제가 있으면 문자열(메시지)을 돌려준다.
function clean(input, ids) {
  if (!input || typeof input !== 'object') return '항목 형식이 맞지 않아요.';
  const it = {};
  if (!isStr(input.title, 200, 1)) return '제목은 1~200자로 써 주세요.';
  it.title = input.title.trim();
  it.code = isStr(input.code || '', 24) ? String(input.code || '').trim().toUpperCase() : null;
  if (it.code == null || /\s/.test(it.code)) return '코드는 띄어쓰기 없이 24자 이내예요 (예: QUE-01).';
  if (!PARTS[input.part]) return '파트를 골라 주세요.';
  it.part = input.part;
  it.owner = input.owner || '';
  if (it.owner && !MEMBERS[it.owner]) return '담당자는 팀원 이름만 고를 수 있어요.';
  if (!STATUSES.includes(input.status)) return '상태가 맞지 않아요.';
  it.status = input.status;
  it.prio = PRIOS.includes(input.prio) ? input.prio : 'P1';
  it.week = [0, 1, 2, 3, 4].includes(Number(input.week)) ? Number(input.week) : 0;
  it.due = /^\d{4}-\d{2}-\d{2}$/.test(input.due || '') ? input.due : '';
  if (!isStr(input.note || '', 2000)) return '설명은 2000자까지예요.';
  it.note = String(input.note || '');
  const deps = Array.isArray(input.deps) ? input.deps : [];
  if (deps.length > 20) return '선행 작업은 20개까지예요.';
  it.deps = [...new Set(deps.filter((d) => typeof d === 'string' && ids.has(d)))];
  it.with = Array.isArray(input.with) ? [...new Set(input.with.filter((p) => PARTS[p] && p !== 'ALL' && p !== it.part))] : [];
  if (input.doneBy && !MEMBERS[input.doneBy]) return '완료한 사람은 팀원 이름만 고를 수 있어요.';
  it.doneBy = input.doneBy || null;
  return it;
}

const FIELDS = ['title', 'code', 'part', 'owner', 'status', 'prio', 'week', 'due', 'note', 'deps', 'with', 'doneBy'];
function diff(a, b) {
  const out = [];
  for (const f of FIELDS) {
    const x = a[f], y = b[f];
    if (JSON.stringify(x == null ? '' : x) === JSON.stringify(y == null ? '' : y)) continue;
    if (f === 'note') out.push([f]);
    else if (f === 'deps') out.push([f, (x || []).length, (y || []).length]);
    else out.push([f, x == null ? '' : x, y == null ? '' : y]);
  }
  return out;
}

export function roadmap(env) {
  const db = env.AI_DB;
  if (!db) return null;
  return {
    async all() {
      await init(db);
      const [items, logs, r, notes] = await Promise.all([
        db.prepare('SELECT v, data FROM roadmap_item').all(),
        db.prepare(`SELECT seq, at, who, item, act, data FROM roadmap_log WHERE act <> 'unnote' ORDER BY seq DESC LIMIT ${LOG_LIMIT}`).all(),
        rev(db),
        db.prepare("SELECT item, COUNT(*) AS c FROM roadmap_log WHERE act = 'note' GROUP BY item").all(),
      ]);
      const nc = new Map((notes.results || []).map((x) => [x.item, x.c]));
      return {
        rev: r, parts: PARTS,
        items: (items.results || []).map(rowItem).filter(Boolean).map((it) => ({ ...it, notes: nc.get(it.id) || 0 })),
        log: (logs.results || []).map(rowLog),
      };
    },
    async rev() { await init(db); return rev(db); },
    async itemLog(id) {
      await init(db);
      const r = await db.prepare('SELECT seq, at, who, item, act, data FROM roadmap_log WHERE item = ?1 ORDER BY seq DESC LIMIT 200').bind(id).all();
      return (r.results || []).map(rowLog);
    },

    // 새 항목(id 없음) 또는 수정(id + v). 결과: {item} 또는 {error, status, current?}
    async save(me, input, v) {
      await init(db);
      const idRows = await db.prepare('SELECT id FROM roadmap_item').all();
      const ids = new Set((idRows.results || []).map((x) => x.id));
      const it = clean(input, ids);
      if (typeof it === 'string') return { error: 'BAD_ITEM', status: 400, message: it };
      const now = Date.now();

      if (!input.id) {
        if (ids.size >= 500) return { error: 'TOO_MANY', status: 400, message: '항목은 500개까지예요.' };
        const item = { id: rid('r'), ...it, createdBy: me, createdAt: now, updatedBy: me, updatedAt: now, order: now };
        if (item.status === 'done') { item.doneBy = item.doneBy || me; item.doneAt = now; } else { item.doneBy = null; item.doneAt = null; }
        await db.batch([
          db.prepare('INSERT INTO roadmap_item (id, v, at, data) VALUES (?1, 1, ?2, ?3)').bind(item.id, now, JSON.stringify(item)),
          addLog(db, me, item.id, 'add', { title: item.title, code: item.code }),
        ]);
        return { item: { ...item, v: 1 } };
      }

      const cur = await getItem(db, input.id);
      if (!cur) return { error: 'GONE', status: 404, message: '그 사이 누가 이 항목을 지웠어요.' };
      if (Number(v) !== cur.v) return { error: 'STALE', status: 409, message: '그 사이 누가 이 항목을 바꿨어요. 최신 내용을 불러왔어요.', current: cur };
      it.deps = it.deps.filter((d) => d !== cur.id);
      const item = { ...cur, ...it, updatedBy: me, updatedAt: now };
      delete item.v;
      if (item.status === 'done') {
        if (cur.status !== 'done') { item.doneBy = it.doneBy || me; item.doneAt = now; }
        else item.doneBy = it.doneBy || cur.doneBy || me;
      } else { item.doneBy = null; item.doneAt = null; }
      const changes = diff(cur, item);
      if (!changes.length) return { item: cur, same: true };
      const r = await db.prepare('UPDATE roadmap_item SET v = v + 1, at = ?1, data = ?2 WHERE id = ?3 AND v = ?4')
        .bind(now, JSON.stringify(item), cur.id, cur.v).run();
      if (!r.meta || !r.meta.changes) {
        return { error: 'STALE', status: 409, message: '그 사이 누가 이 항목을 바꿨어요. 최신 내용을 불러왔어요.', current: await getItem(db, cur.id) };
      }
      const act = changes.length === 1 && changes[0][0] === 'status' ? 'status' : 'edit';
      await addLog(db, me, cur.id, act, { title: item.title, code: item.code, changes }).run();
      return { item: { ...item, v: cur.v + 1 } };
    },

    async remove(me, id) {
      await init(db);
      const cur = await getItem(db, id);
      if (!cur) return { error: 'GONE', status: 404, message: '이미 지워진 항목이에요.' };
      // 이 항목을 선행 작업으로 둔 다른 항목에서 빼 준다
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

    // 진행 메모 (카톡에 스크린샷 올리던 것 대신). img는 data:image/...;base64,...
    async note(me, id, text, img) {
      await init(db);
      const cur = await getItem(db, id);
      if (!cur) return { error: 'GONE', status: 404, message: '그 사이 누가 이 항목을 지웠어요.' };
      if (!isStr(text || '', 2000)) return { error: 'BAD_NOTE', status: 400, message: '메모는 2000자까지예요.' };
      let imgId = null; let stmtImg = null;
      if (img) {
        const m = /^data:(image\/(?:png|jpeg|webp|gif));base64,([A-Za-z0-9+/=]+)$/.exec(img);
        if (!m) return { error: 'BAD_IMG', status: 400, message: '사진은 PNG·JPEG·WebP·GIF만 돼요.' };
        if (m[2].length > IMG_MAX) return { error: 'BIG_IMG', status: 400, message: '사진이 너무 커요 (1MB 이하).' };
        imgId = rid('i');
        stmtImg = db.prepare('INSERT INTO roadmap_img (id, item, at, who, mime, data) VALUES (?1, ?2, ?3, ?4, ?5, ?6)').bind(imgId, id, Date.now(), me, m[1], m[2]);
      }
      if (!String(text || '').trim() && !imgId) return { error: 'BAD_NOTE', status: 400, message: '메모나 사진이 필요해요.' };
      const stmts = [addLog(db, me, id, 'note', { title: cur.title, code: cur.code, text: String(text || '').trim(), img: imgId })];
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
