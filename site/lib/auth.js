// 이름 + 4자리 PIN 로그인. PIN은 PBKDF2 해시로만 KV(AUTH)에 저장한다.
//   pin:{이름}   {salt, hash, iter, setAt}
//   pin:{이름}   temp:true면 팀장이 정한 임시 PIN (들어올 때 새 PIN으로 바꿔야 함)
//   sess:{토큰}  이름 (7일 TTL)
//   PIN 실패 잠금은 lib/guard.js (D1, 없으면 KV fail:{이름})
import { MEMBERS, PIN_RE, SESSION_SEC, PBKDF2_ITER, adminOf } from './config.js';
import { pinLock } from './guard.js';

const enc = new TextEncoder();
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
const randHex = (n) => hex(crypto.getRandomValues(new Uint8Array(n)));

async function pbkdf2(pin, saltHex, iter, pepper) {
  const key = await crypto.subtle.importKey('raw', enc.encode(`${pin}:${pepper || ''}`), 'PBKDF2', false, ['deriveBits']);
  const salt = new Uint8Array(saltHex.match(/../g).map((h) => parseInt(h, 16)));
  return hex(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: iter }, key, 256));
}

function sameStr(a, b) {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

// 이름별 PIN 상태: false(설정 전), true(설정됨), 'temp'(임시 PIN, 들어올 때 바꿈)
export async function pinStatus(env) {
  const out = {};
  await Promise.all(Object.keys(MEMBERS).map(async (n) => {
    const raw = await env.AUTH.get(`pin:${n}`);
    out[n] = raw ? (JSON.parse(raw).temp ? 'temp' : true) : false;
  }));
  return out;
}
const pinRec = async (env, pin) => {
  const salt = randHex(16);
  return { salt, hash: await pbkdf2(pin, salt, PBKDF2_ITER, env.PIN_PEPPER), iter: PBKDF2_ITER, setAt: new Date().toISOString() };
};

export function cookieOf(req, name) {
  const m = (req.headers.get('Cookie') || '').match(new RegExp(`(?:^|;\\s*)${name}=([^;]+)`));
  return m ? m[1] : null;
}

export async function currentUser(env, req) {
  const sid = cookieOf(req, 'sid');
  if (!sid || !/^[0-9a-f]{64}$/.test(sid)) return null;
  const name = await env.AUTH.get(`sess:${sid}`);
  return name && MEMBERS[name] ? name : null;
}

const secure = (req) => new URL(req.url).protocol === 'https:' ? '; Secure' : '';
export const sessionCookie = (req, sid) => `sid=${sid}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${SESSION_SEC}${secure(req)}`;
export const clearCookie = (req) => `sid=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0${secure(req)}`;

// 결과: {ok, firstSet, changed, sid} 또는 {error, ...}
// newPin: 임시 PIN으로 들어올 때 바꿀 새 PIN
export async function login(env, name, pin, newPin) {
  if (!MEMBERS[name]) return { error: 'BAD_NAME', message: '이름 브랜치에 있는 이름만 쓸 수 있어요.' };
  if (!PIN_RE.test(pin || '')) return { error: 'BAD_PIN', message: 'PIN은 숫자 4자리예요.' };

  const recRaw = await env.AUTH.get(`pin:${name}`);
  let firstSet = false, changed = false;
  if (!recRaw) {
    // 아직 아무도 PIN을 정하지 않은 이름 (처음 한 번). 팀장 초기화는 임시 PIN을 남기므로 여기로 오지 않는다
    await env.AUTH.put(`pin:${name}`, JSON.stringify(await pinRec(env, pin)));
    firstSet = true;
  } else {
    const lock = pinLock(env);
    const st = await lock.begin(name);
    if (st.locked) return { error: 'LOCKED', message: st.message };
    const rec = JSON.parse(recRaw);
    const hash = await pbkdf2(pin, rec.salt, rec.iter, env.PIN_PEPPER);
    if (!sameStr(hash, rec.hash)) {
      const w = await lock.wrong(name, st);
      return w.locked ? { error: 'LOCKED', message: w.message } : { error: 'WRONG_PIN', message: w.message };
    }
    await lock.clear(name);
    if (rec.temp) {
      if (!PIN_RE.test(newPin || '')) return { error: 'NEED_NEW_PIN', message: '임시 PIN이 맞아요. 새 PIN(숫자 4자리)을 정해 주세요.' };
      if (newPin === pin) return { error: 'NEED_NEW_PIN', message: '새 PIN은 임시 PIN과 달라야 해요.' };
      await env.AUTH.put(`pin:${name}`, JSON.stringify(await pinRec(env, newPin)));
      changed = true;
    }
  }
  const sid = randHex(32);
  await env.AUTH.put(`sess:${sid}`, name, { expirationTtl: SESSION_SEC });
  return { ok: true, name, firstSet, changed, sid };
}

export async function logout(env, req) {
  const sid = cookieOf(req, 'sid');
  if (sid) await env.AUTH.delete(`sess:${sid}`);
}

// 팀장이 임시 PIN을 정해 준다. 예전처럼 지우기만 하면 그 사이 아무나 먼저 들어와 PIN을 차지할 수 있다.
export async function resetPin(env, me, target, tempPin) {
  if (me !== adminOf(env)) return { error: 'FORBIDDEN', message: 'PIN 초기화는 팀장만 할 수 있어요.' };
  if (!MEMBERS[target]) return { error: 'BAD_NAME', message: '없는 이름이에요.' };
  if (!PIN_RE.test(tempPin || '')) return { error: 'BAD_PIN', message: '임시 PIN(숫자 4자리)을 정해 주세요.' };
  await env.AUTH.put(`pin:${target}`, JSON.stringify({ ...(await pinRec(env, tempPin)), temp: true }));
  await pinLock(env).clear(target);
  return { ok: true };
}
