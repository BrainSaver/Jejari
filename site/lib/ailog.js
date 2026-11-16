// 로그인한 사람의 AI 질문 기록 저장소.
// D1 바인딩(AI_DB)이 있으면 D1(무료 하루 쓰기 10만)에 대화 한 건을 한 행으로, 없으면 KV(AUTH, 하루 쓰기 1천)에 이름별 배열로 저장한다.
export const AI_LOG_MAX = 40;

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS ai_conv (
     owner TEXT NOT NULL,
     id    TEXT NOT NULL,
     at    INTEGER NOT NULL,
     data  TEXT NOT NULL,
     PRIMARY KEY (owner, id)
   )`,
  'CREATE INDEX IF NOT EXISTS ix_ai_conv_owner_at ON ai_conv (owner, at DESC)',
];

let d1Ready = null;
function d1Store(db) {
  const init = () => (d1Ready = d1Ready || db.batch(SCHEMA.map((q) => db.prepare(q))).catch((e) => { d1Ready = null; throw e; }));
  const upsert = (me, c, onlyNewer) => db.prepare(
    `INSERT INTO ai_conv (owner, id, at, data) VALUES (?1, ?2, ?3, ?4)
     ON CONFLICT (owner, id) DO UPDATE SET at = excluded.at, data = excluded.data${onlyNewer ? ' WHERE excluded.at > ai_conv.at' : ''}`,
  ).bind(me, c.id, c.at, JSON.stringify(c));
  // 최근 AI_LOG_MAX개만 남긴다
  const trim = (me) => db.prepare(
    `DELETE FROM ai_conv WHERE owner = ?1 AND id NOT IN (SELECT id FROM ai_conv WHERE owner = ?1 ORDER BY at DESC LIMIT ${AI_LOG_MAX})`,
  ).bind(me);
  const store = {
    kind: 'd1',
    async list(me) {
      await init();
      const r = await db.prepare(`SELECT data FROM ai_conv WHERE owner = ?1 ORDER BY at DESC LIMIT ${AI_LOG_MAX}`).bind(me).all();
      return (r.results || []).map((x) => { try { return JSON.parse(x.data); } catch { return null; } }).filter(Boolean);
    },
    async save(me, conv) { await init(); await db.batch([upsert(me, conv, false), trim(me)]); },
    async merge(me, convs) {
      await init();
      if (convs.length) await db.batch([...convs.map((c) => upsert(me, c, true)), trim(me)]);
      return store.list(me);
    },
    async remove(me, ids) {
      await init();
      if (!ids) { await db.prepare('DELETE FROM ai_conv WHERE owner = ?1').bind(me).run(); return; }
      if (ids.length) await db.batch(ids.map((id) => db.prepare('DELETE FROM ai_conv WHERE owner = ?1 AND id = ?2').bind(me, id)));
    },
  };
  return store;
}

function kvStore(kv) {
  const get = async (me) => { try { return JSON.parse((await kv.get(`ailog:${me}`)) || '[]'); } catch { return []; } };
  const put = async (me, list) => {
    list.sort((a, b) => b.at - a.at);
    const out = list.slice(0, AI_LOG_MAX);
    await kv.put(`ailog:${me}`, JSON.stringify(out));
    return out;
  };
  return {
    kind: 'kv',
    list: get,
    async save(me, conv) { await put(me, [...(await get(me)).filter((x) => x.id !== conv.id), conv]); },
    async merge(me, convs) {
      const map = new Map((await get(me)).map((x) => [x.id, x]));
      convs.forEach((c) => { const old = map.get(c.id); if (!old || old.at < c.at) map.set(c.id, c); });
      return put(me, [...map.values()]);
    },
    async remove(me, ids) { await put(me, ids ? (await get(me)).filter((x) => !ids.includes(x.id)) : []); },
  };
}

export const aiLogStore = (env) => (env.AI_DB ? d1Store(env.AI_DB) : kvStore(env.AUTH));
