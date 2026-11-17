// 대기열 입장 토큰 (U-04가 받아 U-05가 쓴다). 탭을 닫으면 사라지게 sessionStorage에 둔다 (새로고침에는 남음).
// 토큰 자체는 서버(Redis queue:pass)가 검증하므로 여기 값은 "화면에 남은 시간을 보여 주는" 용도다.
const key = (runId) => `jejari-pass:${runId}`;

export function savePass(runId, token, expiresAt) {
  try {
    sessionStorage.setItem(key(runId), JSON.stringify({ token, expiresAt }));
  } catch {
    /* 저장 못 하면 이 화면에서만 쓴다 */
  }
  memory[runId] = { token, expiresAt };
}

const memory = {};

/** @returns {{token: string, expiresAt: string} | null} 만료됐으면 null */
export function loadPass(runId) {
  let p = memory[runId];
  if (!p) {
    try {
      p = JSON.parse(sessionStorage.getItem(key(runId)) || 'null');
    } catch {
      p = null;
    }
  }
  if (!p || !p.token || Date.parse(p.expiresAt) <= Date.now()) return null;
  return p;
}

export function clearPass(runId) {
  delete memory[runId];
  try {
    sessionStorage.removeItem(key(runId));
  } catch {
    /* 무시 */
  }
}
