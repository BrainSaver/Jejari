// 이름 + 4자리 PIN 로그인. PIN은 PBKDF2 해시로만 KV(AUTH)에 저장한다.
//   pin:{이름}   {salt, hash, iter, setAt}
//   fail:{이름}  {count, until}
//   sess:{토큰}  이름 (7일 TTL)
import { MEMBERS, PIN_RE, PIN_MAX_FAIL, PIN_LOCK_SEC, SESSION_SEC, PBKDF2_ITER, adminOf } from './config.js';

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

export async function pinStatus(env) {
  const out = {};
  await Promise.all(Object.keys(MEMBERS).map(async (n) => { out[n] = !!(await env.AUTH.get(`pin:${n}`)); }));
  return out;
}

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

// 결과: {ok, firstSet, sid} 또는 {error, ...}
export async function login(env, name, pin) {
  if (!MEMBERS[name]) return { error: 'BAD_NAME', message: '이름 브랜치에 있는 이름만 쓸 수 있어요.' };
  if (!PIN_RE.test(pin || '')) return { error: 'BAD_PIN', message: 'PIN은 숫자 4자리예요.' };

  const now = Date.now();
  const fail = JSON.parse((await env.AUTH.get(`fail:${name}`)) || '{"count":0,"until":0}');
  if (fail.until > now) {
    return { error: 'LOCKED', message: `PIN을 ${PIN_MAX_FAIL}번 틀려서 잠겼어요. ${Math.ceil((fail.until - now) / 60000)}분 뒤에 다시 해 주세요.` };
  }

  const recRaw = await env.AUTH.get(`pin:${name}`);
  let firstSet = false;
  if (!recRaw) {
    const salt = randHex(16);
    const hash = await pbkdf2(pin, salt, PBKDF2_ITER, env.PIN_PEPPER);
    await env.AUTH.put(`pin:${name}`, JSON.stringify({ salt, hash, iter: PBKDF2_ITER, setAt: new Date().toISOString() }));
    firstSet = true;
  } else {
    const rec = JSON.parse(recRaw);
    const hash = await pbkdf2(pin, rec.salt, rec.iter, env.PIN_PEPPER);
    if (!sameStr(hash, rec.hash)) {
      const count = fail.count + 1;
      const next = count >= PIN_MAX_FAIL ? { count: 0, until: now + PIN_LOCK_SEC * 1000 } : { count, until: 0 };
      await env.AUTH.put(`fail:${name}`, JSON.stringify(next), { expirationTtl: 86400 });
      const left = PIN_MAX_FAIL - count;
      return next.until
        ? { error: 'LOCKED', message: `PIN을 ${PIN_MAX_FAIL}번 틀려서 ${PIN_LOCK_SEC / 60}분 동안 잠겼어요.` }
        : { error: 'WRONG_PIN', message: `PIN이 달라요. ${left}번 더 틀리면 ${PIN_LOCK_SEC / 60}분 잠겨요.` };
    }
  }
  if (fail.count) await env.AUTH.delete(`fail:${name}`);
  const sid = randHex(32);
  await env.AUTH.put(`sess:${sid}`, name, { expirationTtl: SESSION_SEC });
  return { ok: true, name, firstSet, sid };
}

export async function logout(env, req) {
  const sid = cookieOf(req, 'sid');
  if (sid) await env.AUTH.delete(`sess:${sid}`);
}

export async function resetPin(env, me, target) {
  if (me !== adminOf(env)) return { error: 'FORBIDDEN', message: 'PIN 초기화는 팀장만 할 수 있어요.' };
  if (!MEMBERS[target]) return { error: 'BAD_NAME', message: '없는 이름이에요.' };
  await env.AUTH.delete(`pin:${target}`);
  await env.AUTH.delete(`fail:${target}`);
  return { ok: true };
}
