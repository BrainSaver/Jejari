import { useEffect, useRef, useState } from 'react';

/**
 * fetcher를 intervalMs마다 부른다. 탭이 숨겨지면 멈추고, 다시 보이면 바로 한 번 부른 뒤 이어 간다
 * (안 보는 화면이 서버를 두드리지 않게). deps가 바뀌면 처음부터 다시.
 *
 * @returns {{data, error, loading, refreshNow}}
 */
export function usePolling(fetcher, intervalMs, deps = []) {
  const [state, setState] = useState({ data: null, error: null, loading: true });
  const timer = useRef(null);
  const alive = useRef(true);
  const tick = useRef(null);

  useEffect(() => {
    alive.current = true;
    setState({ data: null, error: null, loading: true });

    const run = async () => {
      clearTimeout(timer.current);
      try {
        const data = await fetcher();
        if (alive.current) setState({ data, error: null, loading: false });
      } catch (error) {
        if (alive.current) setState((s) => ({ data: s.data, error, loading: false }));
      }
      if (alive.current && document.visibilityState === 'visible') {
        timer.current = setTimeout(run, intervalMs);
      }
    };
    tick.current = run;

    const onVisible = () => {
      if (document.visibilityState === 'visible') run();
      else clearTimeout(timer.current);
    };
    document.addEventListener('visibilitychange', onVisible);
    run();
    return () => {
      alive.current = false;
      clearTimeout(timer.current);
      document.removeEventListener('visibilitychange', onVisible);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return { ...state, refreshNow: () => tick.current?.() };
}
