import { useEffect, useState } from 'react';
import { refresh, subscribeAuth, tokenClaims } from '../api/client.js';

/**
 * 관리자 로그인 상태. 처음 열면(새로고침 포함) 메모리에 토큰이 없으니 리프레시 쿠키로 한 번 받아 본다.
 * @returns {{claims: object|null, checking: boolean}} claims.adm = VIEWER / REVIEWER / SUPERVISOR
 */
export function useAdminAuth() {
  const [claims, setClaims] = useState(tokenClaims());
  const [checking, setChecking] = useState(!claims);

  useEffect(() => {
    const off = subscribeAuth(setClaims);
    if (!tokenClaims()) {
      refresh().finally(() => setChecking(false));
    }
    return off;
  }, []);

  const isAdmin = claims?.role === 'ADMIN';
  return { claims: isAdmin ? claims : null, checking };
}

const RANK = { VIEWER: 1, REVIEWER: 2, SUPERVISOR: 3 };
/** 서버의 AdminRole과 같은 순서. 버튼을 끌 때만 쓰고, 실제 막는 건 서버(@PreAuthorize)다 */
export const hasRole = (claims, need) => (RANK[claims?.adm] || 0) >= RANK[need];
