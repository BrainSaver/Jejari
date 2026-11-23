import { useEffect, useState } from 'react';
import { get } from '../api/client.js';

/**
 * 오른쪽 위에 보여 줄 내 이름 (서버가 가린 값, 예: 홍*동).
 * 화면을 옮길 때마다 다시 받지 않도록 한 번 받아 두고, 내 정보에서 이름을 바꾸면 setMyName으로 바로 갱신한다.
 */
let cache = { sub: null, name: null };
const listeners = new Set();

export function setMyName(sub, name) {
  cache = { sub, name };
  listeners.forEach((f) => f(cache));
}

export function useMyName(member) {
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
      .then((me) => alive && setMyName(sub, me.name))
      .catch(() => {}); // 이름을 못 받아도 화면은 그대로 (자리에는 "내 정보"가 보인다)
    return () => {
      alive = false;
    };
  }, [sub]);
  return sub && cache.sub === sub ? cache.name : null;
}
