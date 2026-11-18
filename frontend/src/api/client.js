// 백엔드 호출은 전부 여기를 거친다.
//
// - 액세스 토큰은 메모리에만 둔다 (localStorage에 두면 XSS 한 번에 털린다).
//   새로고침하면 사라지므로 refresh()로 다시 받는다. 리프레시 토큰은 httpOnly 쿠키(jejari_rt)라 JS가 못 읽고,
//   브라우저가 /api/v1/auth/** 요청에만 알아서 붙인다.
// - 401이 오면 한 번만 재발급을 시도하고 같은 요청을 다시 보낸다. 동시에 여러 요청이 401을 받아도 재발급은 한 번만.
// - 요청마다 상태·걸린 시간·X-Trace-Id를 기록한다. 관리자 화면이 이 기록을 그대로 보여 준다
//   (traceId로 서버 로그를 바로 찾을 수 있게).

let accessToken = null;
let refreshing = null;
const authListeners = new Set();

/** 서버 오류 응답 {code, message, traceId}를 담는다 */
export class ApiError extends Error {
  constructor(status, body, traceId) {
    super(body?.message || `요청이 실패했어요 (${status})`);
    this.status = status;
    this.code = body?.code || 'HTTP_' + status;
    this.traceId = body?.traceId || traceId || null;
  }
}

// ------------------------------------------------------------ 요청 기록 (관리자 화면의 "요청 기록" 표)

const LOG_MAX = 30;
let log = [];
const logListeners = new Set();

function record(entry) {
  log = [entry, ...log].slice(0, LOG_MAX);
  logListeners.forEach((f) => f(log));
}

export function subscribeRequestLog(fn) {
  logListeners.add(fn);
  fn(log);
  return () => logListeners.delete(fn);
}

// ------------------------------------------------------------ 토큰

/** 토큰 안의 값 (서명 검증은 서버가 한다. 화면은 이름·권한 표시에만 쓴다) */
export function tokenClaims() {
  if (!accessToken) return null;
  try {
    const b64 = accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(decodeURIComponent(escape(atob(b64))));
  } catch {
    return null;
  }
}

export function subscribeAuth(fn) {
  authListeners.add(fn);
  return () => authListeners.delete(fn);
}

function setToken(token) {
  accessToken = token;
  authListeners.forEach((f) => f(tokenClaims()));
}

/** 리프레시 쿠키로 액세스 토큰을 다시 받는다. 성공하면 true */
export function refresh() {
  if (!refreshing) {
    refreshing = send('POST', '/api/v1/auth/refresh', undefined, { auth: false })
      .then((r) => {
        setToken(r.accessToken);
        return true;
      })
      .catch(() => {
        setToken(null);
        return false;
      })
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
}

export async function adminLogin(loginId, password) {
  const r = await send('POST', '/api/v1/admin/auth/login', { loginId, password }, { auth: false });
  setToken(r.accessToken);
  return tokenClaims();
}

/** 회원 로그인 (U-01). 관리자와 같은 리프레시 쿠키를 쓰므로 한 브라우저에서는 한 사람만 로그인한다 */
export async function memberLogin(email, password) {
  const r = await send('POST', '/api/v1/auth/login', { email, password }, { auth: false });
  setToken(r.accessToken);
  return tokenClaims();
}

export async function logout() {
  try {
    if (accessToken) await send('POST', '/api/v1/auth/logout');
  } finally {
    setToken(null);
  }
}

// ------------------------------------------------------------ 요청

/**
 * 기기 ID. 서버(BehaviorInterceptor)가 X-Device-Id를 해시해 행동 로그에 남기고, 같은 기기의 다계정을
 * 위험 점수로 묶는다 (MULTI_ACCOUNT_SYNC 등). 처음 열 때 무작위로 만들어 브라우저에 저장한다.
 * 클라이언트 값이라 마음먹은 봇은 바꾸거나 뺄 수 있다 — "같은 브라우저를 여러 계정이 쓰는" 경우를 잡는 장치다.
 * 저장소가 막혀 있으면(사생활 보호 창 등) 이 탭에서만 쓰는 ID로 대신한다.
 */
const DEVICE_KEY = 'jejari.device';
let deviceId = null;
function device() {
  if (deviceId) return deviceId;
  const fresh = () =>
    (crypto.randomUUID?.() || `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`).replace(/[^A-Za-z0-9_-]/g, '');
  try {
    deviceId = localStorage.getItem(DEVICE_KEY);
    if (!/^[A-Za-z0-9_-]{8,128}$/.test(deviceId || '')) {
      deviceId = fresh();
      localStorage.setItem(DEVICE_KEY, deviceId);
    }
  } catch {
    deviceId = fresh();
  }
  return deviceId;
}

async function send(method, path, body, { auth = true, extra } = {}) {
  const headers = { Accept: 'application/json', ...(extra || {}) };
  // 이용자 요청에만 붙인다. 관리자 조회는 이용자 행동 분석에 섞이지 않게 (AdminDashboardController 주석과 같은 이유)
  if (!path.startsWith('/api/v1/admin')) headers['X-Device-Id'] = device();
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth && accessToken) headers.Authorization = 'Bearer ' + accessToken;

  const started = performance.now();
  let res;
  try {
    res = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'same-origin',
    });
  } catch (e) {
    record({ at: new Date(), method, path, status: 0, ms: Math.round(performance.now() - started), traceId: null });
    throw new ApiError(0, { code: 'NETWORK', message: '서버에 연결하지 못했어요.' });
  }
  const ms = Math.round(performance.now() - started);
  const traceId = res.headers.get('X-Trace-Id');
  record({ at: new Date(), method, path, status: res.status, ms, traceId });

  const text = await res.text();
  const data = text ? safeJson(text) : null;
  if (!res.ok) throw new ApiError(res.status, data, traceId);
  return data;
}

function safeJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/**
 * 토큰을 붙여 부르고, 401이면 한 번 재발급 후 다시 부른다.
 * @param headers 더 붙일 헤더 (예: X-Queue-Token, Idempotency-Key)
 */
export async function api(method, path, body, headers) {
  try {
    return await send(method, path, body, { extra: headers });
  } catch (e) {
    if (e instanceof ApiError && e.status === 401 && (await refresh())) {
      return send(method, path, body, { extra: headers });
    }
    throw e;
  }
}

export const get = (path, headers) => api('GET', path, undefined, headers);
export const post = (path, body, headers) => api('POST', path, body ?? {}, headers);
export const put = (path, body) => api('PUT', path, body);
export const del = (path) => api('DELETE', path);
