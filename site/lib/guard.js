// 남용 막기: IP·이름별 횟수 제한과 PIN 실패 잠금.
// D1(AI_DB)이 있으면 D1에 센다. D1은 쓰기가 한 곳에서 차례로 처리돼, 동시에 여러 요청을 보내도 숫자가 새지 않는다.
// (KV는 쓴 값이 늦게 퍼져서 동시에 쏘면 제한을 넘길 수 있다.) D1이 없으면 인스턴스 메모리로 대략 막는다.
//   rate_hit (k, n, reset)          k = '{종류}:{IP 또는 이름}', reset까지 n번
//   pin_fail (name, count, locks, until)  count = 이번 잠금 전까지 틀린 수, locks = 지금까지 잠긴 횟수

const SCHEMA = [
  'CREATE TABLE IF NOT EXISTS rate_hit (k TEXT PRIMARY KEY, n INTEGER NOT NULL, reset INTEGER NOT NULL)',
  'CREATE TABLE IF NOT EXISTS pin_fail (name TEXT PRIMARY KEY, count INTEGER NOT NULL, locks INTEGER NOT NULL, until INTEGER NOT NULL)',
];
let ready = null;
const init = (db) => (ready = ready || db.batch(SCHEMA.map((q) => db.prepare(q))).catch((e) => { ready = null; throw e; }));

export const ipOf = (req) => req.headers.get('CF-Connecting-IP') || 'local';

// 횟수 제한. 기본값은 env로 바꿀 수 있다 (RATE_{종류}=횟수/초, 예: RATE_ASK=15/60).
export const LIMITS = {
  login: [20, 600],      // IP당 10분에 로그인 시도 20번 (이름을 바꿔 가며 찍는 것도 막음)
  ask: [15, 60],         // IP당 1분에 AI 질문 15번
  askAll: [120, 60],     // 로그인 안 한 사람 전체 합쳐 1분에 AI 질문 120번 (IP를 바꿔 가며 쓰는 것 막기)
  context: [10, 60],     // IP당 1분에 LLM용 복사 10번
  read: [120, 60],       // 로그인 안 한 사람이 GitHub를 부르는 읽기 (캐시에 없을 때만 셈)
};
function limitOf(env, kind) {
  const v = env[`RATE_${kind.toUpperCase()}`];
  const m = /^(\d+)\/(\d+)$/.exec(v || '');
  return m ? [Number(m[1]), Number(m[2])] : LIMITS[kind];
}

const mem = new Map();
function memHit(k, max, sec, now) {
  const e = mem.get(k);
  if (!e || e.reset <= now) { mem.set(k, { n: 1, reset: now + sec * 1000 }); if (mem.size > 5000) mem.clear(); return { ok: true, wait: 0 }; }
  e.n++;
  return { ok: e.n <= max, wait: Math.ceil((e.reset - now) / 1000) };
}

// 한 번 센다. 결과: {ok, wait(초)}
export async function hit(env, kind, who) {
  const [max, sec] = limitOf(env, kind);
  const k = `${kind}:${who}`;
  const now = Date.now();
  const db = env.AI_DB;
  if (!db) return memHit(k, max, sec, now);
  await init(db);
  const r = await db.prepare(
    `INSERT INTO rate_hit (k, n, reset) VALUES (?1, 1, ?2)
     ON CONFLICT (k) DO UPDATE SET
       n = CASE WHEN rate_hit.reset <= ?3 THEN 1 ELSE rate_hit.n + 1 END,
       reset = CASE WHEN rate_hit.reset <= ?3 THEN ?2 ELSE rate_hit.reset END
     RETURNING n, reset`,
  ).bind(k, now + sec * 1000, now).first();
  // 지난 기록은 가끔 지운다
  if (Math.random() < 0.01) await db.prepare('DELETE FROM rate_hit WHERE reset <= ?1').bind(now).run().catch(() => {});
  return { ok: r.n <= max, wait: Math.max(1, Math.ceil((r.reset - now) / 1000)) };
}

// ---------- PIN 실패 잠금 ----------
// 잠길 때마다 길어진다: 10분 → 1시간 → 6시간 → 24시간(그 뒤로 계속). 맞히면 모두 풀린다.
export const PIN_MAX_FAIL = 5;
const LOCK_STEPS = [600, 3600, 6 * 3600, 24 * 3600];
export const lockSec = (locks) => LOCK_STEPS[Math.min(Math.max(locks, 1), LOCK_STEPS.length) - 1];
const minText = (ms) => { const m = Math.ceil(ms / 60000); return m >= 60 ? `${Math.ceil(m / 60)}시간` : `${m}분`; };

// KV 대신 쓸 PIN 잠금 저장소. D1이 없으면 KV(예전 방식, 동시 요청에는 약함)
export function pinLock(env) {
  const db = env.AI_DB;
  const kv = env.AUTH;
  const kvGet = async (name) => JSON.parse((await kv.get(`fail:${name}`)) || '{"count":0,"locks":0,"until":0}');
  return {
    // 확인 전에 부른다. 잠겼으면 {locked, message}. 아니면 시도 수를 먼저 올려 두고 {ok}.
    // 먼저 올리기 때문에 동시에 여러 번 보내도 PIN_MAX_FAIL번 넘게는 확인하지 않는다.
    async begin(name) {
      const now = Date.now();
      if (db) {
        await init(db);
        const r = await db.prepare(
          `INSERT INTO pin_fail (name, count, locks, until) VALUES (?1, 1, 0, 0)
           ON CONFLICT (name) DO UPDATE SET count = CASE WHEN pin_fail.until > ?2 THEN pin_fail.count ELSE pin_fail.count + 1 END
           RETURNING count, locks, until`,
        ).bind(name, now).first();
        if (r.until > now) return { locked: true, message: `PIN을 여러 번 틀려서 잠겼어요. ${minText(r.until - now)} 뒤에 다시 해 주세요.` };
        if (r.count > PIN_MAX_FAIL) {
          // 동시에 몰아 보낸 시도이거나, 앞 시도가 결과를 남기기 전에 끊긴 경우: 바로 잠근다
          const sec = lockSec(r.locks + 1);
          await db.prepare('UPDATE pin_fail SET count = 0, locks = ?2, until = ?3 WHERE name = ?1').bind(name, r.locks + 1, now + sec * 1000).run();
          return { locked: true, message: `PIN을 너무 많이 시도해서 ${minText(sec * 1000)} 동안 잠겼어요.` };
        }
        return { ok: true, count: r.count, locks: r.locks };
      }
      const f = await kvGet(name);
      if (f.until > now) return { locked: true, message: `PIN을 여러 번 틀려서 잠겼어요. ${minText(f.until - now)} 뒤에 다시 해 주세요.` };
      return { ok: true, count: (f.count || 0) + 1, locks: f.locks || 0 };
    },
    // 틀렸을 때. 결과 {locked, message} 또는 {left}
    async wrong(name, st) {
      const now = Date.now();
      if (st.count >= PIN_MAX_FAIL) {
        const locks = st.locks + 1;
        const sec = lockSec(locks);
        if (db) await db.prepare('UPDATE pin_fail SET count = 0, locks = ?2, until = ?3 WHERE name = ?1').bind(name, locks, now + sec * 1000).run();
        else await kv.put(`fail:${name}`, JSON.stringify({ count: 0, locks, until: now + sec * 1000 }), { expirationTtl: 7 * 86400 });
        return { locked: true, message: `PIN을 ${PIN_MAX_FAIL}번 틀려서 ${minText(sec * 1000)} 동안 잠겼어요.` };
      }
      if (!db) await kv.put(`fail:${name}`, JSON.stringify({ count: st.count, locks: st.locks, until: 0 }), { expirationTtl: 7 * 86400 });
      const left = PIN_MAX_FAIL - st.count;
      return { left, message: `PIN이 달라요. ${left}번 더 틀리면 ${minText(lockSec(st.locks + 1) * 1000)} 잠겨요.` };
    },
    // 맞았거나 팀장이 초기화했을 때
    async clear(name) {
      if (db) { await init(db); await db.prepare('DELETE FROM pin_fail WHERE name = ?1').bind(name).run(); }
      await kv.delete(`fail:${name}`);
    },
  };
}
