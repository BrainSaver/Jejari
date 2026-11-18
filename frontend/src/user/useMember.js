import { useEffect, useState } from 'react';
import { refresh, subscribeAuth, tokenClaims } from '../api/client.js';

/**
 * 회원 로그인 상태. 새로고침하면 메모리의 토큰이 사라지므로 리프레시 쿠키로 한 번 받아 본다.
 * @returns {{member: object|null, checking: boolean}} member.sub = 회원 ID
 */
export function useMember() {
  const [claims, setClaims] = useState(tokenClaims());
  const [checking, setChecking] = useState(!claims);
  useEffect(() => {
    const off = subscribeAuth(setClaims);
    if (!tokenClaims()) refresh().finally(() => setChecking(false));
    return off;
  }, []);
  return { member: claims?.role === 'MEMBER' ? claims : null, checking };
}
