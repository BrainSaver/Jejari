import { useEffect, useState } from 'react';
import { get } from '../api/client.js';

/**
 * 오른쪽 위에 보여 줄 내 이름 (서버가 가린 값, 예: 홍*동)과 비회원 여부.
 * 화면을 옮길 때마다 다시 받지 않도록 한 번 받아 두고, 내 정보에서 이름을 바꾸면 setMyName으로 바로 갱신한다.
 */
let cache = { sub: null, name: null, guest: false };
const listeners = new Set();

export function setMyName(sub, name, guest = cache.sub === sub ? cache.guest : false) {
  cache = { sub, name, guest };
  listeners.forEach((f) => f(cache));
}

/** @returns {{name: string|null, guest: boolean}} */
export function useMyProfile(member) {
  const sub = member?.sub ?? null;
  const [, tick] = useState(0);
  useEffect(() => {
    const f = () => tick((n) => n + 1);
    listeners.add(f);
    return () => listeners.delete(f);
  }, []);
  useEffect(() => {
    if (!sub || cache.sub === sub) return;
    let alive = true;
    get('/api/v1/me')
      .then((me) => alive && setMyName(sub, me.name, !!me.guest))
      .catch(() => {}); // 이름을 못 받아도 화면은 그대로 (자리에는 "내 정보"가 보인다)
    return () => {
      alive = false;
    };
  }, [sub]);
  return sub && cache.sub === sub ? { name: cache.name, guest: cache.guest } : { name: null, guest: false };
}

export const useMyName = (member) => useMyProfile(member).name;
